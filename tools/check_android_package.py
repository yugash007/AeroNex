"""Verify a AeroNex release APK: identity, signing, ABIs, 16 KiB packaging, native libraries and JNI symbols.

Run after every release build, on the fat APK and on each `--split-per-abi` APK. This inspects the delivered
artifact rather than unoptimized test classes. Android SDK build-tools and a JDK are required.

Release APKs ship armeabi-v7a and arm64-v8a only (x86_64 is debug-only, spec 4.19). Each ABI must carry exactly the
runtime libraries in EXPECTED_LIBRARIES: Flutter, Vosk + JNA (grammar mode until Phase 3) and sherpa-onnx + ONNX
Runtime. arm64-v8a also carries the v0.4.0 AI libraries (AI_LIBRARIES: the LFM planner on a static llama.cpp and the
Cactus Needle shim), which must export their JNI / C entry points, and whose JNI class must survive R8; no other ABI
may carry them. whisper.cpp, the shared llama.cpp/ggml, other Cactus builds, the C MAVLink codec and sherpa's unused
C/C++ API libraries fail the check by name. 64-bit libraries need 16 KiB ELF LOAD alignment; 32-bit devices use 4 KiB
pages.
Flutter split APKs add 1000 x ABI code to the version code (armeabi-v7a 1, arm64-v8a 2): pass e.g. 1012 / 2012.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

from check_jni_contract import check_apk, dex_definitions, missing_members

PACKAGE = 'com.aeronex.app'
RELEASE_ABIS = ('armeabi-v7a', 'arm64-v8a')
# (ELF class, e_machine) per ABI: 2 = ELFCLASS64, 1 = ELFCLASS32.
ABI_ELF = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'x86_64': (2, 62), 'x86': (1, 3)}
EXPECTED_LIBRARIES = ('libapp.so', 'libflutter.so', 'libvosk.so', 'libjnidispatch.so',
                      'libsherpa-onnx-jni.so', 'libonnxruntime.so')
# libc++_shared.so: v0.2.0 shipped it; the v0.4.0 AI libraries link libc++ statically.
REMOVED_LIBRARIES = re.compile(r'whisper|llama|ggml|cactus|aeronex_native|aeronex_inference|mavlink|sherpa-onnx-c-api|'
                               r'sherpa-onnx-cxx-api|c\+\+_shared')
# v0.4.0 AI (spec 2026-10-15 A6): arm64-v8a only. llama.cpp is linked statically into libaeronex_ai.so.
AI_ABI = 'arm64-v8a'
AI_LIBRARIES = ('libaeronex_ai.so', 'libaeronex_cactus.so')
_AI_JNI = 'Java_com_aeronex_app_ai_NativeAiEngine_'
AI_EXPORTS = {
    'libaeronex_ai.so': [_AI_JNI + name for name in ('reset', 'cancel', 'plan', 'warmUp', 'unload', 'toolCall')],
    'libaeronex_cactus.so': ['aeronex_cactus_run'],
}
# The Kotlin side of those JNI functions (ai/AiEngine.kt), as R8 must keep it (proguard-rules.pro).
AI_JNI_CONTRACT = {'com/aeronex/app/ai/NativeAiEngine': {'methods': [
    'reset()V', 'cancel()V', 'plan([B[BII)[B', 'warmUp([BI)D', 'unload()V', 'toolCall([B[B)[B']}}
# Licence notices for the AI runtimes (Needle Apache-2.0, llama.cpp MIT); --require-ai-notices makes them mandatory.
AI_NOTICES = ('assets/flutter_assets/assets/licenses/cactus.txt', 'assets/flutter_assets/assets/licenses/llama_cpp.txt')
PAGE_16_KIB = 16384
DEFAULT_SHERPA_AAR = Path(__file__).resolve().parents[2] / 'tooling' / 'sherpa-onnx' / 'sherpa-onnx-1.13.8.aar'


def load_segments(data):
    """(ELF class, e_machine, [(file offset, virtual address, alignment)] of every PT_LOAD)."""
    if data[:4] != b'\x7fELF' or data[5] != 1:
        raise ValueError('not a little-endian ELF file')
    elf_class, machine = data[4], struct.unpack_from('<H', data, 18)[0]
    if elf_class == 2:
        table, (entry_size, count) = struct.unpack_from('<Q', data, 32)[0], struct.unpack_from('<HH', data, 54)
        fields = lambda at: (struct.unpack_from('<QQ', data, at + 8) + struct.unpack_from('<Q', data, at + 48))
    elif elf_class == 1:
        table, (entry_size, count) = struct.unpack_from('<I', data, 28)[0], struct.unpack_from('<HH', data, 42)
        fields = lambda at: (struct.unpack_from('<II', data, at + 4) + struct.unpack_from('<I', data, at + 28))
    else:
        raise ValueError('unknown ELF class')
    segments = [fields(table + index * entry_size) for index in range(count)
                if struct.unpack_from('<I', data, table + index * entry_size)[0] == 1]
    return elf_class, machine, segments


def exported_symbols(data):
    """Names of the defined GLOBAL/WEAK symbols in a 64-bit ELF's .dynsym (its exports)."""
    shoff = struct.unpack_from('<Q', data, 40)[0]
    entry_size, count = struct.unpack_from('<HH', data, 58)
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, shoff + index * entry_size) for index in range(count)] \
        if shoff else []
    names = set()
    for _, kind, _, _, offset, size, link, _, _, symbol_size in sections:
        if kind != 11 or not symbol_size:  # SHT_DYNSYM
            continue
        strings_offset = sections[link][4]
        for at in range(offset + symbol_size, offset + size, symbol_size):
            name, info, _, section = struct.unpack_from('<IBBH', data, at)
            if section != 0 and info >> 4 in (1, 2):
                end = data.index(0, strings_offset + name)
                names.add(data[strings_offset + name:end].decode('ascii', errors='replace'))
    return names


def check_native_libraries(archive, abis):
    """Per ABI: its libraries (bytes, LOAD alignments) and total bytes. Raises ValueError on any violation."""
    result = {abi: {'libraries': [], 'bytes': 0} for abi in abis}
    for name in sorted(archive.namelist()):
        if not name.startswith('lib/'):
            continue
        parts = name.split('/')
        file = parts[-1]
        require(len(parts) == 3 and file.endswith('.so'), 'Unexpected file in lib/: ' + name)
        abi = parts[1]
        if file in AI_LIBRARIES:
            require(abi == AI_ABI, f'AI library outside {AI_ABI}: {name}')
        else:
            require(not REMOVED_LIBRARIES.search(file), 'Removed native library still packaged: ' + name)
        require(abi in abis, f'Unsupported ABI library: {name} (this APK ships {", ".join(abis)} only)')
        require(file in EXPECTED_LIBRARIES or file in AI_LIBRARIES, 'Unexpected native library: ' + name)
        data = archive.read(name)
        try:
            elf_class, machine, segments = load_segments(data)
        except (ValueError, struct.error) as error:
            raise ValueError(f'Unreadable ELF {name}: {error}') from error
        require((elf_class, machine) == ABI_ELF[abi], f'Not {abi} ELF: {name}')
        require(segments, 'No ELF LOAD segments: ' + name)
        if elf_class == 2:
            for offset, virtual, alignment in segments:
                require(alignment >= PAGE_16_KIB and (virtual - offset) % PAGE_16_KIB == 0,
                        'Incompatible 16 KiB ELF LOAD segment: ' + name)
        if file in AI_EXPORTS:
            try:
                exports = exported_symbols(data)
            except (ValueError, struct.error, IndexError) as error:
                raise ValueError(f'Unreadable ELF symbols {name}: {error}') from error
            absent = [symbol for symbol in AI_EXPORTS[file] if symbol not in exports]
            require(not absent, f'{file} does not export {", ".join(absent)}')
        result[abi]['libraries'].append({'file': name, 'bytes': len(data),
                                         'load_alignments': [segment[2] for segment in segments]})
        result[abi]['bytes'] += len(data)
    for abi in abis:
        present = {item['file'].rsplit('/', 1)[1] for item in result[abi]['libraries']}
        required = EXPECTED_LIBRARIES + (AI_LIBRARIES if abi == AI_ABI else ())
        missing = [file for file in required if file not in present]
        require(not missing, f'Missing native libraries for {abi}: {", ".join(missing)}')
    return result


def check_badging(badging, version, version_code, abis):
    require(f"package: name='{PACKAGE}' versionCode='{version_code}' versionName='{version}'" in badging,
            'APK package or version does not match this delivery')
    require("sdkVersion:'26'" in badging and "targetSdkVersion:'36'" in badging, 'Unexpected SDK levels')
    abi_line = re.search(r'^native-code: (.+)$', badging, re.MULTILINE)
    advertised = set(re.findall(r"'([^']+)'", abi_line.group(1))) if abi_line else set()
    require(advertised == set(abis),
            f'APK advertises ABIs {sorted(advertised)}, expected {sorted(abis)}')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--certificate', required=True, help='Expected certificate SHA-256')
    parser.add_argument('--abis', default=','.join(RELEASE_ABIS),
                        help='Comma-separated ABIs this APK must ship exactly (one for a split APK)')
    parser.add_argument('--sherpa-aar', type=Path, default=DEFAULT_SHERPA_AAR,
                        help='The sherpa-onnx AAR the app was built with (its JNI contract is checked)')
    parser.add_argument('--require-ai-notices', action='store_true',
                        help='Fail unless the AI licence notices (AI_NOTICES) are packaged with the AI libraries')
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    abis = tuple(abi.strip() for abi in args.abis.split(',') if abi.strip())
    require(abis and set(abis) <= set(RELEASE_ABIS), f'Release ABIs are {", ".join(RELEASE_ABIS)}')
    log_path = args.report.with_suffix('.log')
    logs = []
    executable_suffix = '.exe' if os.name == 'nt' else ''
    java = (str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java' + executable_suffix))
            if os.environ.get('JAVA_HOME') else shutil.which('java'))
    if not java:
        raise RuntimeError('Set JAVA_HOME or put java on PATH')

    def run(command):
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, check=False)
        logs.append(result.stdout)
        log_path.write_text('\n'.join(logs), encoding='utf-8')
        if result.returncode:
            raise RuntimeError(f'{command[0]} failed; see {log_path}')
        return result.stdout

    check_badging(run([str(args.build_tools / ('aapt' + executable_suffix)), 'dump', 'badging', str(args.apk)]),
                  args.version, args.version_code, abis)
    signature = run([java, '-jar', str(args.build_tools / 'lib/apksigner.jar'),
                     'verify', '--verbose', '--print-certs', str(args.apk)])
    require(f'Signer #1 certificate SHA-256 digest: {args.certificate.lower()}' in signature,
            'APK certificate differs from the expected upgrade key')
    run([str(args.build_tools / ('zipalign' + executable_suffix)), '-c', '-P', '16', '-v', '4', str(args.apk)])
    with zipfile.ZipFile(args.apk) as archive:
        names = set(archive.namelist())
        require('assets/flutter_assets/assets/licenses/vosk.txt' in names, 'Missing Vosk/JNA notices')
        native = check_native_libraries(archive, abis)
        ai_notices = {notice.rsplit('/', 1)[1]: notice in names for notice in AI_NOTICES}
        ai_dex = {}
        if AI_ABI in abis:
            for dex in names:
                if re.fullmatch(r'classes(?:[1-9][0-9]*)?\.dex', dex):
                    ai_dex.update(dex_definitions(archive.read(dex)))
    ai_missing = missing_members(ai_dex, AI_JNI_CONTRACT) if AI_ABI in abis else []
    require(not ai_missing, 'AI JNI class renamed or removed by R8: ' + '; '.join(ai_missing))
    if AI_ABI in abis and not all(ai_notices.values()):
        absent = ', '.join(name for name, present in ai_notices.items() if not present)
        require(not args.require_ai_notices, 'Missing AI licence notices: ' + absent)
        print(f'WARNING: AI libraries ship without licence notices: {absent}')
    require(args.sherpa_aar.is_file(), f'sherpa-onnx AAR not found: {args.sherpa_aar} (pass --sherpa-aar)')
    contract = check_apk(args.apk, args.sherpa_aar)
    require(contract['passed'], 'Broken JNI contract: ' + '; '.join(contract['missing']))
    report = {'file': str(args.apk), 'package': PACKAGE, 'version': args.version,
              'version_code': args.version_code, 'min_sdk': 26, 'target_sdk': 36,
              'abis': list(abis), 'bytes': args.apk.stat().st_size, 'sha256': contract['sha256'],
              'signing_certificate_sha256': args.certificate.lower(), 'signature_verified': True,
              'zip_and_elf_alignment_16kb_verified': True, 'jni_contract': contract,
              # Checks that do not apply (no AI libraries in this APK) are null.
              'ai': {'abi': AI_ABI, 'libraries_present': AI_ABI in abis,
                     'exports_verified': True if AI_ABI in abis else None,
                     'jni_class_kept': (not ai_missing) if AI_ABI in abis else None,
                     'licence_notices': ai_notices if AI_ABI in abis else None},
              'native_bytes_per_abi': {abi: native[abi]['bytes'] for abi in abis},
              'native_libraries': [item for abi in abis for item in native[abi]['libraries']]}
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: report[key] for key in ['file', 'version', 'version_code', 'abis', 'bytes',
                                                  'native_bytes_per_abi', 'sha256']}, indent=2))
    print(f'PASS: identity, certificate, ABIs {"+".join(abis)}, ZIP 16 KiB and 64-bit ELF 16 KiB alignment, '
          f'runtime libraries present{" (AI on " + AI_ABI + ")" if AI_ABI in abis else " (no AI libraries)"}, '
          f'removed libraries absent, optimized JNI contract '
          f'({contract["classes_checked"]} classes, {contract["members_checked"]} members)')


if __name__ == '__main__':
    main()

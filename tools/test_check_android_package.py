"""Unit tests for the release APK checks (armeabi-v7a + arm64-v8a, no removed native code, v0.4.0 AI on arm64 only).

Synthetic ELF/ZIP inputs only; the real APK run is `python tools/check_android_package.py <apk> ...`.
"""
import io
from pathlib import Path
import re
import struct
import zipfile

import pytest

import check_android_package as package
import check_jni_contract as contract

ELF = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'x86_64': (2, 62)}
SHERPA_AAR = Path(__file__).resolve().parents[2] / 'tooling' / 'sherpa-onnx' / 'sherpa-onnx-1.13.8.aar'


def elf(abi, align=16384, skew=0, exports=None):
    """A minimal little-endian ELF with two PT_LOAD headers (64-bit: and a .dynsym exporting `exports`)."""
    elf_class, machine = ELF[abi]
    if elf_class == 2:
        header = bytearray(64)
        header[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', header, 18, machine)
        struct.pack_into('<Q', header, 32, 64)
        struct.pack_into('<HH', header, 54, 56, 2)
        table = b''.join(struct.pack('<IIQQQQQQ', 1, 5, offset, offset + skew, offset, 16, 16, align)
                         for offset in (0, 0x8000))
        if exports is not None:
            return dynsym_elf(header, table, exports)
    else:
        header = bytearray(52)
        header[:6] = b'\x7fELF\x01\x01'
        struct.pack_into('<H', header, 18, machine)
        struct.pack_into('<I', header, 28, 52)
        struct.pack_into('<HH', header, 42, 32, 2)
        table = b''.join(struct.pack('<IIIIIIII', 1, offset, offset + skew, offset, 16, 16, 5, align)
                         for offset in (0, 0x8000))
    return bytes(header) + table


def dynsym_elf(header, table, exports):
    """Appends .dynstr and .dynsym sections (defined GLOBAL FUNC symbols) and their section headers."""
    strings = b'\0' + b''.join(name.encode() + b'\0' for name in exports)
    symbols = bytearray(24)  # the null symbol
    at = 1
    for name in exports:
        symbols += struct.pack('<IBBHQQ', at, 0x12, 0, 1, 0x1000, 8)
        at += len(name) + 1
    body = bytes(header) + table
    strtab_offset = len(body)
    symtab_offset = strtab_offset + len(strings)
    sections_offset = symtab_offset + len(symbols)
    sections = bytes(64)  # SHT_NULL
    sections += struct.pack('<IIQQQQIIQQ', 0, 3, 0, 0, strtab_offset, len(strings), 0, 0, 1, 0)
    sections += struct.pack('<IIQQQQIIQQ', 0, 11, 0, 0, symtab_offset, len(symbols), 1, 1, 8, 24)
    data = bytearray(body + strings + symbols + sections)
    struct.pack_into('<Q', data, 40, sections_offset)
    struct.pack_into('<HHH', data, 58, 64, 3, 0)
    return bytes(data)


AI_EXPORTS = {'libaeronex_ai.so': package.AI_EXPORTS['libaeronex_ai.so'],
              'libaeronex_cactus.so': package.AI_EXPORTS['libaeronex_cactus.so']}


def apk(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    buffer.seek(0)
    return zipfile.ZipFile(buffer)


def release(abis=package.RELEASE_ABIS, **overrides):
    entries = {f'lib/{abi}/{name}': elf(abi) for abi in abis for name in package.EXPECTED_LIBRARIES}
    if 'arm64-v8a' in abis:
        entries.update({f'lib/arm64-v8a/{name}': elf('arm64-v8a', exports=exports)
                        for name, exports in AI_EXPORTS.items()})
    entries.update(overrides)
    return apk({name: data for name, data in entries.items() if data is not None})


def test_release_ships_both_arm_abis_with_every_runtime_library():
    result = package.check_native_libraries(release(), package.RELEASE_ABIS)
    assert set(result) == {'arm64-v8a', 'armeabi-v7a'}
    for abi in package.RELEASE_ABIS:
        files = {item['file'].rsplit('/', 1)[1] for item in result[abi]['libraries']}
        ai = set(package.AI_LIBRARIES) if abi == 'arm64-v8a' else set()
        assert files == set(package.EXPECTED_LIBRARIES) | ai
        assert {'libsherpa-onnx-jni.so', 'libonnxruntime.so', 'libvosk.so', 'libjnidispatch.so',
                'libflutter.so', 'libapp.so'} <= files
        assert result[abi]['bytes'] == sum(item['bytes'] for item in result[abi]['libraries'])


def test_a_missing_abi_or_runtime_library_fails():
    with pytest.raises(ValueError, match='armeabi-v7a'):
        package.check_native_libraries(release(abis=['arm64-v8a']), package.RELEASE_ABIS)
    for name in ('libsherpa-onnx-jni.so', 'libonnxruntime.so', 'libvosk.so', 'libflutter.so'):
        with pytest.raises(ValueError, match=name):
            package.check_native_libraries(release(**{f'lib/armeabi-v7a/{name}': None}), package.RELEASE_ABIS)


def test_x86_64_is_debug_only():
    with pytest.raises(ValueError, match='x86_64'):
        package.check_native_libraries(release(**{'lib/x86_64/libvosk.so': elf('x86_64')}), package.RELEASE_ABIS)


@pytest.mark.parametrize('name', ['libwhisper.so', 'libllama.so', 'libggml-base.so', 'libcactus.so',
                                  'libaeronex_native.so', 'libaeronex_inference.so', 'libmavlink_codec.so',
                                  'libsherpa-onnx-c-api.so', 'libsherpa-onnx-cxx-api.so', 'libc++_shared.so'])
def test_removed_libraries_fail_by_name(name):
    with pytest.raises(ValueError, match='Removed native library.*' + re.escape(name)):
        package.check_native_libraries(release(**{f'lib/arm64-v8a/{name}': elf('arm64-v8a')}),
                                       package.RELEASE_ABIS)


def test_arm64_ships_both_ai_libraries():
    for name in package.AI_LIBRARIES:
        with pytest.raises(ValueError, match='Missing native libraries for arm64-v8a: ' + name.replace('.', r'\.')):
            package.check_native_libraries(release(**{f'lib/arm64-v8a/{name}': None}), package.RELEASE_ABIS)


@pytest.mark.parametrize('name', ['libaeronex_ai.so', 'libaeronex_cactus.so'])
def test_armeabi_v7a_never_ships_an_ai_library(name):
    with pytest.raises(ValueError, match='AI library outside arm64-v8a.*' + name.replace('.', r'\.')):
        package.check_native_libraries(release(**{f'lib/armeabi-v7a/{name}': elf('armeabi-v7a')}),
                                       package.RELEASE_ABIS)
    result = package.check_native_libraries(release(abis=['armeabi-v7a']), ['armeabi-v7a'])
    assert all(not item['file'].endswith(name) for item in result['armeabi-v7a']['libraries'])


def test_ai_libraries_need_16_kib_alignment_and_their_entry_points():
    with pytest.raises(ValueError, match='16 KiB.*libaeronex_ai.so'):
        package.check_native_libraries(
            release(**{'lib/arm64-v8a/libaeronex_ai.so': elf('arm64-v8a', align=4096, exports=[])}),
            package.RELEASE_ABIS)
    missing = AI_EXPORTS['libaeronex_ai.so'][:-1]
    with pytest.raises(ValueError, match='libaeronex_ai.so does not export.*toolCall'):
        package.check_native_libraries(
            release(**{'lib/arm64-v8a/libaeronex_ai.so': elf('arm64-v8a', exports=missing)}), package.RELEASE_ABIS)
    with pytest.raises(ValueError, match='libaeronex_cactus.so does not export aeronex_cactus_run'):
        package.check_native_libraries(
            release(**{'lib/arm64-v8a/libaeronex_cactus.so': elf('arm64-v8a', exports=['needle_load'])}),
            package.RELEASE_ABIS)


def test_exported_symbols_reads_defined_dynamic_symbols():
    assert package.exported_symbols(elf('arm64-v8a', exports=['a', 'aeronex_cactus_run'])) == {'a', 'aeronex_cactus_run'}
    assert package.exported_symbols(elf('arm64-v8a')) == set()


def test_the_ai_jni_contract_is_the_native_methods_of_native_ai_engine():
    engine = 'Lcom/aeronex/app/ai/NativeAiEngine;'
    methods = {'reset()V', 'cancel()V', 'plan([B[BII)[B', 'warmUp([BI)D', 'unload()V', 'toolCall([B[B)[B'}
    assert set(package.AI_JNI_CONTRACT['com/aeronex/app/ai/NativeAiEngine']['methods']) == methods
    assert contract.missing_members({engine: {'fields': set(), 'methods': methods}}, package.AI_JNI_CONTRACT) == []
    assert contract.missing_members({'La;': {'fields': set(), 'methods': set()}}, package.AI_JNI_CONTRACT) == [
        'class com/aeronex/app/ai/NativeAiEngine']
    # Every Kotlin native is a JNI export of libaeronex_ai.so.
    exported = {name.rsplit('_', 1)[1] for name in package.AI_EXPORTS['libaeronex_ai.so']}
    assert exported == {method.split('(')[0] for method in methods}


def test_an_unknown_native_library_fails():
    with pytest.raises(ValueError, match='Unexpected native library.*libfoo.so'):
        package.check_native_libraries(release(**{'lib/armeabi-v7a/libfoo.so': elf('armeabi-v7a')}),
                                       package.RELEASE_ABIS)


def test_64_bit_libraries_need_16_kib_load_alignment_and_32_bit_do_not():
    with pytest.raises(ValueError, match='16 KiB.*libvosk.so'):
        package.check_native_libraries(release(**{'lib/arm64-v8a/libvosk.so': elf('arm64-v8a', align=4096)}),
                                       package.RELEASE_ABIS)
    with pytest.raises(ValueError, match='16 KiB.*libonnxruntime.so'):
        package.check_native_libraries(
            release(**{'lib/arm64-v8a/libonnxruntime.so': elf('arm64-v8a', skew=4096)}), package.RELEASE_ABIS)
    # Vosk 0.3.75's armeabi-v7a libvosk.so is 4 KiB aligned; 16 KiB pages exist only on 64-bit devices.
    result = package.check_native_libraries(
        release(**{'lib/armeabi-v7a/libvosk.so': elf('armeabi-v7a', align=4096)}), package.RELEASE_ABIS)
    vosk = [item for item in result['armeabi-v7a']['libraries'] if item['file'].endswith('libvosk.so')]
    assert vosk[0]['load_alignments'] == [4096, 4096]


def test_a_library_built_for_another_abi_fails():
    with pytest.raises(ValueError, match='Not armeabi-v7a ELF.*libapp.so'):
        package.check_native_libraries(release(**{'lib/armeabi-v7a/libapp.so': elf('arm64-v8a')}),
                                       package.RELEASE_ABIS)


def test_split_apk_checks_its_one_abi():
    result = package.check_native_libraries(release(abis=['armeabi-v7a']), ['armeabi-v7a'])
    assert set(result) == {'armeabi-v7a'}


BADGING = ("package: name='com.aeronex.app' versionCode='12' versionName='0.3.0' "
           "platformBuildVersionName='16'\nsdkVersion:'26'\ntargetSdkVersion:'36'\n"
           "native-code: 'arm64-v8a' 'armeabi-v7a'\n")


def test_badging_checks_identity_sdks_and_the_advertised_abis():
    package.check_badging(BADGING, '0.3.0', 12, package.RELEASE_ABIS)
    with pytest.raises(ValueError, match='version'):
        package.check_badging(BADGING, '0.2.0', 11, package.RELEASE_ABIS)
    with pytest.raises(ValueError, match='ABIs'):
        package.check_badging(BADGING.replace("'armeabi-v7a'", "'armeabi-v7a' 'x86_64'"), '0.3.0', 12,
                              package.RELEASE_ABIS)
    with pytest.raises(ValueError, match='ABIs'):
        package.check_badging(BADGING.replace(" 'armeabi-v7a'", ''), '0.3.0', 12, package.RELEASE_ABIS)


def test_vosk_contract_covers_the_t11_adapter():
    vosk = set(contract.CONTRACT['org/vosk/LibVosk']['methods'])
    assert {'vosk_recognizer_set_max_alternatives(Lcom/sun/jna/Pointer;I)V',
            'vosk_recognizer_set_partial_words(Lcom/sun/jna/Pointer;Z)V',
            'vosk_recognizer_reset(Lcom/sun/jna/Pointer;)V'} <= vosk


def test_missing_members_names_renamed_fields_and_absent_classes():
    expected = {'com/k2fsa/sherpa/onnx/FeatureConfig': {'fields': ['sampleRate:I'], 'methods': []},
                'com/k2fsa/sherpa/onnx/OfflineRecognizer': {'methods': ['decode(JJ)V']}}
    classes = {'Lcom/k2fsa/sherpa/onnx/FeatureConfig;': {'fields': {'a:I'}, 'methods': set()}}
    assert contract.missing_members(classes, expected) == [
        'com/k2fsa/sherpa/onnx/FeatureConfig fields: sampleRate:I',
        'class com/k2fsa/sherpa/onnx/OfflineRecognizer']


@pytest.mark.skipif(not SHERPA_AAR.is_file(), reason='sherpa-onnx 1.13.8 AAR not provisioned')
def test_sherpa_contract_is_every_field_and_native_method_in_the_aar():
    sherpa = contract.sherpa_contract(SHERPA_AAR)
    feature = sherpa['com/k2fsa/sherpa/onnx/FeatureConfig']['fields']
    assert {'sampleRate:I', 'featureDim:I', 'dither:F'} <= set(feature)
    recognizer = sherpa['com/k2fsa/sherpa/onnx/OfflineRecognizer']['methods']
    assert recognizer and all('(' in method for method in recognizer)
    assert 'com/k2fsa/sherpa/onnx/KeywordSpotterConfig' in sherpa
    assert 'com/k2fsa/sherpa/onnx/OfflineNemoEncDecCtcModelConfig' in sherpa


def test_the_certificate_must_be_the_whole_digest_of_the_only_signer():
    digest = "ab" * 32
    one = f"Verifies\nSigner #1 certificate DN: CN=AeroNex\nSigner #1 certificate SHA-256 digest: {digest}\n"
    assert package.certificate_matches(one, digest)
    assert package.certificate_matches(one, digest.upper())
    assert not package.certificate_matches(one, "")
    assert not package.certificate_matches(one, digest[:20])
    assert not package.certificate_matches(one, "cd" * 32)
    two = one + f"Signer #2 certificate SHA-256 digest: {'cd' * 32}\n"
    assert not package.certificate_matches(two, digest)


def test_newer_apksigner_names_the_signer_per_scheme():
    # build-tools 35+ print one block per signing scheme instead of "Signer #1".
    digest = "ab" * 32
    v2 = (f"Verifies\nNumber of signers: 1\nV2 Signer: certificate DN: CN=AeroNex\n"
          f"V2 Signer: certificate SHA-256 digest: {digest}\nV2 Signer: certificate MD5 digest: {'12' * 16}\n")
    assert package.certificate_matches(v2, digest)
    assert not package.certificate_matches(v2, "cd" * 32)
    assert not package.certificate_matches(v2, digest[:20])
    both = v2 + f"V3 Signer: certificate SHA-256 digest: {digest}\n"
    assert package.certificate_matches(both, digest)
    assert not package.certificate_matches(v2 + f"V3 Signer: certificate SHA-256 digest: {'cd' * 32}\n", digest)
    assert not package.certificate_matches(v2.replace("Number of signers: 1", "Number of signers: 2"), digest)
    assert not package.certificate_matches("Verifies\nNumber of signers: 1\n", digest), "no signer line: no match"

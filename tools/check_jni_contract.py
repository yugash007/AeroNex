"""Check JNI/reflection symbols in the actual APK DEX, after R8 optimization.

JNA 5.18.1 native/dispatch.c initIDs resolves these members by literal name.
Vosk uses JNA direct registration and NativeMapped constructors. sherpa-onnx's
JNI reads its Kotlin config classes' fields by name and binds its natives by
class and method name, so every field and native method of every class in the
sherpa-onnx AAR must survive R8 unchanged (`--sherpa-aar`). Checking only
native .so presence or unoptimized JVM classes cannot detect this failure.
Uses Python's standard library; does not execute Android or test microphone audio.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import zipfile


def dex_definitions(data):
    if data[:4] != b'dex\n' or struct.unpack_from('<I', data, 40)[0] != 0x12345678:
        raise ValueError('Expected a standard little-endian Android DEX file')

    def uleb(offset):
        result = 0
        for shift in range(0, 35, 7):
            byte = data[offset]
            offset += 1
            result |= (byte & 127) << shift
            if byte < 128:
                return result, offset
        raise ValueError('Invalid DEX ULEB128')

    string_count, string_offset = struct.unpack_from('<II', data, 56)
    strings = []
    for index in range(string_count):
        offset = struct.unpack_from('<I', data, string_offset + index * 4)[0]
        _, offset = uleb(offset)
        # Contract identifiers are ASCII; other MUTF-8 string constants are unused.
        strings.append(data[offset:data.index(0, offset)].decode('utf-8', errors='replace'))
    type_count, type_offset = struct.unpack_from('<II', data, 64)
    types = [strings[struct.unpack_from('<I', data, type_offset + i * 4)[0]]
             for i in range(type_count)]
    proto_count, proto_offset = struct.unpack_from('<II', data, 72)
    protos = []
    for index in range(proto_count):
        _, returns, parameters = struct.unpack_from('<III', data, proto_offset + index * 12)
        count = struct.unpack_from('<I', data, parameters)[0] if parameters else 0
        args = ''.join(types[struct.unpack_from('<H', data, parameters + 4 + n * 2)[0]]
                       for n in range(count))
        protos.append(f'({args}){types[returns]}')
    _, field_offset = struct.unpack_from('<II', data, 80)
    _, method_offset = struct.unpack_from('<II', data, 88)
    class_count, class_offset = struct.unpack_from('<II', data, 96)
    classes = {}
    for index in range(class_count):
        definition = class_offset + index * 32
        class_index = struct.unpack_from('<I', data, definition)[0]
        offset = struct.unpack_from('<I', data, definition + 24)[0]
        members = {'fields': set(), 'methods': set()}
        classes[types[class_index]] = members
        if not offset:
            continue
        counts = []
        for _ in range(4):
            count, offset = uleb(offset)
            counts.append(count)
        # Read defined members, not field/method reference tables alone.
        for kind, count in enumerate(counts):
            member_index = 0
            for _ in range(count):
                delta, offset = uleb(offset)
                member_index += delta
                _, offset = uleb(offset)  # access flags
                if kind < 2:
                    _, type_index, name = struct.unpack_from('<HHI', data, field_offset + member_index * 8)
                    members['fields'].add(strings[name] + ':' + types[type_index])
                else:
                    _, offset = uleb(offset)  # code offset
                    _, proto_index, name = struct.unpack_from('<HHI', data, method_offset + member_index * 8)
                    members['methods'].add(strings[name] + protos[proto_index])
    return classes


# All com.sun.jna lookups in JNA 5.18.1 dispatch.c Native_initIDs (including
# FFI type initialization), plus the LibVosk natives behind speech/VoskNative.kt (T11).
# This is a symbol-preservation check, not execution of JNI or its semantics.
CONTRACT = {
    'com/sun/jna/Pointer': {'fields': ['peer:J'], 'methods': ['<init>(J)V']},
    'com/sun/jna/Native': {'methods': [
        'initIDs()V', 'dispose()V',
        'fromNative(Ljava/lang/Class;Ljava/lang/Object;)Lcom/sun/jna/NativeMapped;',
        'fromNative(Ljava/lang/reflect/Method;Ljava/lang/Object;)Lcom/sun/jna/NativeMapped;',
        'nativeType(Ljava/lang/Class;)Ljava/lang/Class;',
        'toNative(Lcom/sun/jna/ToNativeConverter;Ljava/lang/Object;)Ljava/lang/Object;',
        'fromNative(Lcom/sun/jna/FromNativeConverter;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/Object;',
    ]},
    'com/sun/jna/Structure': {
        'fields': ['memory:Lcom/sun/jna/Pointer;', 'typeInfo:J'],
        'methods': ['getTypeInfo()Lcom/sun/jna/Pointer;',
                    'newInstance(Ljava/lang/Class;J)Lcom/sun/jna/Structure;', 'autoRead()V', 'autoWrite()V'],
    },
    'com/sun/jna/Structure$ByValue': {},
    'com/sun/jna/Callback': {},
    'com/sun/jna/CallbackReference': {'methods': [
        'getCallback(Ljava/lang/Class;Lcom/sun/jna/Pointer;Z)Lcom/sun/jna/Callback;',
        'getFunctionPointer(Lcom/sun/jna/Callback;Z)Lcom/sun/jna/Pointer;',
        'getNativeString(Ljava/lang/Object;Z)Lcom/sun/jna/Pointer;',
        'initializeThread(Lcom/sun/jna/Callback;Lcom/sun/jna/CallbackReference$AttachOptions;)Ljava/lang/ThreadGroup;',
    ]},
    'com/sun/jna/CallbackReference$AttachOptions': {},
    'com/sun/jna/WString': {'methods': ['<init>(Ljava/lang/String;)V']},
    'com/sun/jna/NativeMapped': {'methods': ['toNative()Ljava/lang/Object;']},
    'com/sun/jna/IntegerType': {'fields': ['value:J']},
    'com/sun/jna/PointerType': {
        'fields': ['pointer:Lcom/sun/jna/Pointer;'],
        'methods': ['<init>()V', 'getPointer()Lcom/sun/jna/Pointer;'],
    },
    'com/sun/jna/JNIEnv': {},
    'com/sun/jna/Native$ffi_callback': {'methods': ['invoke(JJJ)V']},
    'com/sun/jna/FromNativeConverter': {'methods': ['nativeType()Ljava/lang/Class;']},
    'com/sun/jna/Structure$FFIType$FFITypes': {'fields': [
        f'ffi_type_{kind}:Lcom/sun/jna/Pointer;' for kind in (
            'void', 'float', 'double', 'longdouble', 'uint8', 'sint8',
            'uint16', 'sint16', 'uint32', 'sint32', 'uint64', 'sint64', 'pointer',
        )
    ]},
    'org/vosk/Model': {'methods': ['<init>()V', '<init>(Ljava/lang/String;)V', 'close()V']},
    'org/vosk/LibVosk': {'methods': [
        'vosk_model_new(Ljava/lang/String;)Lcom/sun/jna/Pointer;',
        'vosk_model_free(Lcom/sun/jna/Pointer;)V',
        'vosk_recognizer_new(Lorg/vosk/Model;F)Lcom/sun/jna/Pointer;',
        'vosk_recognizer_new_grm(Lcom/sun/jna/Pointer;FLjava/lang/String;)Lcom/sun/jna/Pointer;',
        'vosk_recognizer_set_max_alternatives(Lcom/sun/jna/Pointer;I)V',
        'vosk_recognizer_set_partial_words(Lcom/sun/jna/Pointer;Z)V',
        'vosk_recognizer_reset(Lcom/sun/jna/Pointer;)V',
        'vosk_recognizer_accept_waveform_s(Lcom/sun/jna/Pointer;[SI)Z',
        'vosk_recognizer_result(Lcom/sun/jna/Pointer;)Ljava/lang/String;',
        'vosk_recognizer_partial_result(Lcom/sun/jna/Pointer;)Ljava/lang/String;',
        'vosk_recognizer_final_result(Lcom/sun/jna/Pointer;)Ljava/lang/String;',
        'vosk_recognizer_free(Lcom/sun/jna/Pointer;)V',
    ]},
}


def class_definitions(data):
    """Fields and native methods defined by one Java class file: ('Lname;', {'fields', 'methods'})."""
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Expected a Java class file')
    count = struct.unpack_from('>H', data, 8)[0]
    utf8, classes, offset, index = {}, {}, 10, 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4,
             19: 2, 20: 2}
    while index < count:
        tag = data[offset]
        if tag == 1:
            length = struct.unpack_from('>H', data, offset + 1)[0]
            utf8[index] = data[offset + 3:offset + 3 + length].decode('utf-8', errors='replace')
            offset += 3 + length
        else:
            if tag == 7:
                classes[index] = struct.unpack_from('>H', data, offset + 1)[0]
            offset += 1 + sizes[tag]
        index += 2 if tag in (5, 6) else 1  # long and double take two pool slots
    this_class = struct.unpack_from('>H', data, offset + 2)[0]
    interfaces = struct.unpack_from('>H', data, offset + 6)[0]
    offset += 8 + 2 * interfaces
    members = {'fields': set(), 'methods': set()}
    for kind in ('fields', 'methods'):
        count = struct.unpack_from('>H', data, offset)[0]
        offset += 2
        for _ in range(count):
            flags, name, descriptor, attributes = struct.unpack_from('>HHHH', data, offset)
            offset += 8
            for _ in range(attributes):
                offset += 6 + struct.unpack_from('>I', data, offset + 2)[0]
            if kind == 'fields':
                members['fields'].add(utf8[name] + ':' + utf8[descriptor])
            elif flags & 0x0100:  # ACC_NATIVE: bound by its JNI name
                members['methods'].add(utf8[name] + utf8[descriptor])
    return 'L' + utf8[classes[this_class]] + ';', members


def sherpa_contract(aar):
    """Every field and native method of every com/k2fsa/sherpa/onnx class in the AAR's classes.jar."""
    with zipfile.ZipFile(aar) as outer:
        jar = zipfile.ZipFile(io.BytesIO(outer.read('classes.jar')))
    result = {}
    with jar:
        for name in jar.namelist():
            if name.startswith('com/k2fsa/sherpa/onnx/') and name.endswith('.class'):
                descriptor, members = class_definitions(jar.read(name))
                result[descriptor[1:-1]] = {kind: sorted(values) for kind, values in members.items()}
    if not result:
        raise ValueError(f'{aar} has no com/k2fsa/sherpa/onnx classes')
    return result


def missing_members(classes, contract):
    missing = []
    for name, expected in contract.items():
        actual = classes.get(f'L{name};')
        if actual is None:
            missing.append('class ' + name)
            continue
        for kind, members in expected.items():
            missing.extend(f'{name} {kind}: {member}' for member in members if member not in actual[kind])
    return missing


def check_apk(apk, sherpa_aar=None):
    classes, dex_files = {}, []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if re.fullmatch(r'classes(?:[1-9][0-9]*)?\.dex', name):
                dex_files.append(name)
                classes.update(dex_definitions(archive.read(name)))
    if not dex_files:
        raise ValueError('APK contains no DEX files')
    expected = dict(CONTRACT)
    if sherpa_aar is not None:
        expected.update(sherpa_contract(sherpa_aar))
    missing = missing_members(classes, expected)
    with Path(apk).open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    return {'apk': str(apk), 'sha256': digest, 'dex_files': dex_files,
            'classes_checked': len(expected),
            'members_checked': sum(len(members) for item in expected.values() for members in item.values()),
            'contract': 'JNA 5.18.1 Native_initIDs, LibVosk adapter natives'
                        + (', sherpa-onnx AAR fields and natives' if sherpa_aar is not None else ''),
            'missing': missing, 'passed': not missing,
            'scope': 'Optimized APK JNI symbol contract; does not execute Android JNI or microphone capture'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--sherpa-aar', type=Path, help='sherpa-onnx AAR whose classes the APK must keep')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    result = check_apk(args.apk, args.sherpa_aar)
    output = json.dumps(result, indent=2) + '\n'
    if args.report:
        args.report.write_text(output, encoding='utf-8')
    print(output)
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())

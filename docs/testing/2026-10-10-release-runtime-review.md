# Independent release/JNA review — 10 October 2026

Reviewed the saved `review.diff` against `scope.md`, with targeted reads of the current runtime adapter, screen actions, the saved JNA `dispatch.c`, R8 configuration/mapping, and the recorded test and package evidence. This review covers the new 0.1.6+7 work; the preexisting uncommitted application is not treated as a new diff. No application code, Git state, model files, or APKs were changed. Suites and builds were not repeated.

## Strengths

- The release fix addresses the demonstrated mechanism. The old R8 map renames `Pointer.peer` to `c`, and JNA's native `initIDs` looks up literal `peer:J` before reporting the screenshot's exact error. The saved old release fails 15 contract checks while the old debug control passes. The scoped `-keep` rules preserve both the JNA reflection/JNI surface and Vosk's direct native registration and `NativeMapped` constructors. The emitted release configuration contains these rules and only the four named AWT warning suppressions; application obfuscation remains enabled (`LocalInferenceBridge -> xb`).
- `LocalInferenceBridge.kt:143` and `:226` distinguish a fatal Vosk `LinkageError` from model, microphone, decode, cancellation, and memory failures. The known failure is cached on the main thread, repeated Vosk attempts return `SPEECH_RUNTIME`, and inventory remains unchanged. Cleanup and the directory lease still complete through the existing `use`/`finally` paths. Diagnostics retain the original class/message and log the throwable. Whisper and typed LFM operations do not consult the Vosk cache.
- `local_control_screen.dart:528`, `:675`, and `:788` keep readiness, microphone availability, fallback, and current command errors consistent. A known runtime error cannot turn into an Android language-download prompt. The recovery button explicitly changes the backend; if Whisper is missing, its existing model setup path remains responsible for installation. A later typed-command validation failure is visible above the continuing Vosk warning. The compact-screen test reaches the fallback at 360×780 with 1.3 text scaling.
- The new artifact checks inspect defined DEX members, including multidex APKs, rather than mistaking a reference table entry for an existing implementation. The class-data parser correctly resets the delta-encoded member index for each of the four member lists. Its standard little-endian DEX and ASCII contract-identifier assumptions fit these Android build outputs. APK verification combines actual signing verification, expected certificate/version/ABI checks, ZIP alignment, and ARM64 ELF LOAD-segment alignment.
- The UI wording remains explicit about dry-run behavior. This diff does not change the controller's default dry-run executor, connect model proposals to a flight transport, or introduce a RAM threshold. Existing tests for flight execution remaining locked in simulator mode still pass.

## Issues

### Critical — none

No current application crash, data-loss, flight-execution, or model-retention regression was found in the scoped implementation.

### Important — one artifact-guard coverage gap

**[P2] Complete the unconditional JNA bootstrap contract.**

File: `tools/check_jni_contract.py:100` (the contract section through line 103, plus the omitted classes below).

`CONTRACT` checks only class presence for `CallbackReference` and `NativeMapped`, and omits several later lookups that JNA 5.18.1 executes unconditionally in `Native.initIDs`. The package checker would therefore still report a passing JNI contract if one of these names were stripped or renamed. This is a coverage gap in the new release guard, not evidence that the current APK is broken: the current broad keep rules preserve these symbols. It matters because this guard is intended to catch the same category of optimized-release startup failure before a later delivery.

The exact missing checks, verified against the saved `jna-dispatch.c:2965` through `:3077`, are:

| Class (`com/sun/jna/` prefix) | Required definitions not currently checked |
| --- | --- |
| `CallbackReference` | `getCallback(Ljava/lang/Class;Lcom/sun/jna/Pointer;Z)Lcom/sun/jna/Callback;` |
| `CallbackReference` | `getFunctionPointer(Lcom/sun/jna/Callback;Z)Lcom/sun/jna/Pointer;` |
| `CallbackReference` | `getNativeString(Ljava/lang/Object;Z)Lcom/sun/jna/Pointer;` |
| `CallbackReference` | `initializeThread(Lcom/sun/jna/Callback;Lcom/sun/jna/CallbackReference$AttachOptions;)Ljava/lang/ThreadGroup;` |
| `WString` | Class and `<init>(Ljava/lang/String;)V` |
| `NativeMapped` | `toNative()Ljava/lang/Object;` |
| `IntegerType` | Class and field `value:J` |
| `PointerType` | Field `pointer:Lcom/sun/jna/Pointer;` |
| `JNIEnv` | Class |
| `Native$ffi_callback` | Class and `invoke(JJJ)V` |
| `FromNativeConverter` | Class and `nativeType()Ljava/lang/Class;` |
| `Structure$FFIType$FFITypes` | Class and the 13 fields listed below |

All 13 FFI fields have type `Lcom/sun/jna/Pointer;`: `ffi_type_void`, `ffi_type_float`, `ffi_type_double`, `ffi_type_longdouble`, `ffi_type_uint8`, `ffi_type_sint8`, `ffi_type_uint16`, `ffi_type_sint16`, `ffi_type_uint32`, `ffi_type_sint32`, `ffi_type_uint64`, `ffi_type_sint64`, and `ffi_type_pointer`.

Extend the contract to these unconditional lookups. Recheck the existing final release and debug APKs and use a narrow negative case for a newly covered member. This requires no application change or rebuild if the APKs satisfy the expanded contract.

### Minor — none

## Evidence and limits

- `2026-10-10-jna-release-red.json`: old optimized release fails the original 15 checks; `jna-debug-control.json`: old debug passes.
- `2026-10-10-jna-runtime-native-red.log`: the two new runtime-failure cases fail before implementation. `jna-error-priority-red.log`: the typed-error visibility case fails before its correction.
- `2026-10-10-jna-flutter-tests.log`: 94 passing tests. `jna-analysis.log`: no analysis issues.
- `2026-10-10-jna-native-summary.json`: 74 tests, 73 passed, one optional network-download test skipped, zero failures/errors. `jna-native-tests.log` reports `BUILD SUCCESSFUL`; the Windows Robolectric `.obsolete` TempDirectory cleanup exception is a worker-shutdown warning, not a failing application test.
- `2026-10-10-0.1.6-release-apk.json` and `debug-apk.json`, with their corresponding logs: version 0.1.6/code 7, expected existing certificate, ARM64 only, verified ZIP/ELF 16 KiB alignment, and the original 11-class contract passing. The release SHA-256 is `940ca934b219d4d28753bd581f2fcbe0c05006e7d531dda650dbde5d5121d916`; debug is `10da931dd5daff7b60ed05650d39e9e83399777986996ac7a27cec84491e6f6c`.
- No Android phone or installed emulator image was available. APK inspection and host tests do not establish actual Android JNI execution, microphone capture, or recognition accuracy. Those limits are stated accurately in the guard output and scope.

## Assessment

**Ready to merge? With fixes to the artifact guard.** The production R8, error-handling, and UI changes satisfy the scoped request, with no other actionable findings. Complete the deterministic bootstrap checks above to close the audit-tool gap; retain the Android device-execution limitation in delivery notes. This verdict does not authorize or request a commit, merge, or push.

## Scoped rereview — P2 resolved

Reviewed the change from `before/check_jni_contract.py` to the current `tools/check_jni_contract.py`, limited to the artifact guard and refreshed package evidence. The expanded contract at `tools/check_jni_contract.py:102` through `:124` now includes every missing class and exact field/method descriptor listed above. Comparing it with the saved JNA 5.18.1 `dispatch.c` confirms coverage of all unconditional `com.sun.jna` name lookups in `Native_initIDs`, including all 13 FFI type fields. The DEX parser and existing comparisons are unchanged. The report now names this contract and correctly counts 17 classes and 52 members; its scope still explicitly excludes Android execution and microphone capture.

The refreshed release and debug package reports both pass the expanded contract with no missing definitions. Their SHA-256 values match the previously reviewed APKs, so the evidence applies to the same delivered binaries and does not depend on an unreviewed application rebuild.

A narrow in-memory negative probe removed `PointerType.pointer:Lcom/sun/jna/Pointer;` from the parsed release DEX definitions before the contract comparison. The baseline checker incorrectly passed that simulated missing definition; the expanded checker failed with exactly `com/sun/jna/PointerType fields: pointer:Lcom/sun/jna/Pointer;`. The probe did not alter APK bytes or create files. No full test suite or build was repeated.

**Final verdict: Ready; no open actionable findings in this scoped review.** The earlier P2 and conditional verdict are resolved. The Android device-execution and microphone-accuracy limitations remain unchanged.

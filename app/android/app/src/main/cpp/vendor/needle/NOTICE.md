# Cactus Needle runtime

Upstream: https://huggingface.co/Cactus-Compute/needle3

Revision: `2ae11323dc000f5e70c49f7403efa6af12ba9e67`

The unmodified `android-arm64/needle.h` is vendored here under the accompanying Apache-2.0 license. Its SHA-256 is `914bbd00423939b02fc349f91cdb7c12531b3f5bb7105b743e0e93c7e409d7b1`.

The matching ARM64 static archive is not downloaded by the build (v0.4.0): CMake links the local copy named by the Gradle property `aeronexNeedleArchive` (default `tooling/cactus/android-arm64-libneedle.a` next to the worktrees) and fails unless its SHA-256 is `4522a16eebaaf569ebacd0ac682712a8d91acc88a2a50a44fd65f332c8ebfdbb`. The host evaluation (`tools/ai-eval`) links the same revision's `linux-x86_64/libneedle.a` (SHA-256 `8556785e3f3562bb6e402d48770f34c0797f23b9571c07457cdc577928b0b9d3`).

The app uses the C API directly (text tool calls only), without the Python package or its telemetry module. The adapter and dynamic-loading lifecycle are maintained by this project. Model files (`needle3.cact`) are installed separately through verified downloads/imports.

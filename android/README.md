# Android build and validation

**Current release: v0.1, ARM64 Bionic / FEX / Vulkan.** APK builds and Android
gameplay have been tested; this is no longer an unbuilt integration stage.
User installation and compatibility: [English](../README.md) /
[Português](../README.pt-BR.md). See [release notes](../RELEASE_NOTES.md).

## Android source

Use the [Android source tree](https://github.com/erickmendes22/tlou-remastered-android/tree/tlou-ui-approval-log32-20261105) when building. The historical Windows
branch is not the Android APK implementation.

```sh
git clone --recurse-submodules --branch tlou-ui-approval-log32-20261105 https://github.com/erickmendes22/tlou-remastered-android.git
cd tlou-remastered-android
```

The existing branch name is a development identifier, not a user-facing setting.

## Build workflow

The [verified Android workflow](https://github.com/erickmendes22/tlou-remastered-android/tree/tlou-ui-approval-log32-20261105/.github/workflows/tlou-native-apk.yml)
selects a full native build when native inputs change, or reuses a pinned,
verified native payload when allowed inputs remain compatible. It runs
preparation tests, Android UI tests and release lint, assembles and signs the
APK, then verifies ELF dependencies, JNI, installer components and drivers.

The source checks also execute the production x86 submesh-guard bytes under
Unicorn. APK signing identity is preserved for compatible updates.

For an executable-patch-table update, only the explicitly allowed Python source
member is refreshed in the installer archive. Other installer members and the
native/runtime/driver payload are compared byte-for-byte against the pinned
build. A renderer or runtime change still requires a native build.

Documentation edits do not require a new APK. This repository does not publish
a new APK solely because a microbenchmark is faster.

## Local native build

The scripts require a Linux build host, Android SDK/NDK, JDK 17, Gradle and the
build dependencies configured by the workflow. Consult that workflow for exact
versions and setup before running the commands.

```sh
export TLOU_NDK=/path/to/android-ndk-r29
bash android/bionic/build_deps.sh
bash android/bionic/build_fex.sh
bash android/bionic/build_gpu.sh
bash android/bionic/build_runtime.sh --link-only
bash android/bionic/build_python.sh
bash android/bionic/build_pkg.sh
python3 android/bionic/stage_tools.py
bash android/bionic/package_apk.sh
```

These are build steps, not instructions for players: the APK includes the
on-device preparation tools. Do not substitute another game's native binaries.

## Validation without game files

```sh
python3 -m pip install unicorn==2.1.4
python3 -m unittest discover -s android/tests -v
bash android/bionic/test_runtime_contracts.sh
bash android/bionic/test_fps.sh
python3 gow3_pc/tests/test_android_save_choice.py
gradle -p android-launcher :app:testReleaseUnitTest :app:lintVitalRelease
```

The Java tests require Android SDK setup. The final APK validation is:

```sh
python3 android/bionic/check_tools_apk.py out/release/TheLastOfUsRemastered-Android-v0.5-ContentBC-arm64.apk
```

The validated executable profile is SHA-256 checked and every patch requires
matching original instruction bytes. Unknown executables do not acquire known
patches merely through a matching title ID. Game files are neither test fixtures
nor release artifacts.

## Runtime boundaries

- Readback 1 is retained. The observed 01.00 physics output has its selective
  EOP readback path; do not restore global Readback 2.
- App language selection is independent of the game's own language menu.
- Save metadata and thumbnail handling must retain the selected native slot.
- The static-ray-hit bounds guard skips only invalid results and keeps the
  original path for valid hits; its source audit is retained in the Android tree.
- Automated tests and successful builds do not replace device regression tests.

CPU translation uses FEX; the TLOU runtime, renderer, Android presenter and
installer retain their respective source and third-party licensing obligations.

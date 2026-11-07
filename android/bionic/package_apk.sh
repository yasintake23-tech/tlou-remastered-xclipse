#!/usr/bin/env bash
# Package the real TLOU ARM64/FEX executable and GPU, not a placeholder APK.
set -Eeuo pipefail
trap 'echo "TLOU APK packaging failed at line $LINENO: $BASH_COMMAND" >&2' ERR
cd "$(dirname "$0")/../.."
root=$PWD
out=$root/out/android-bionic
ndk="$TLOU_NDK"
if [[ -z "$ndk" ]]; then ndk="$ANDROID_NDK_HOME"; fi
[[ -n "$ndk" ]] || { echo "Set TLOU_NDK or ANDROID_NDK_HOME" >&2; exit 2; }
llvm=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin
sdk="$ANDROID_HOME"
if [[ -z "$sdk" ]]; then sdk="$ANDROID_SDK_ROOT"; fi
[[ -n "$sdk" ]] || { echo "Set ANDROID_HOME or ANDROID_SDK_ROOT" >&2; exit 2; }
build_tools=35.0.0
android_tools="$sdk/build-tools/$build_tools"
[[ -s "$out/bin/gow3-probe" ]] || { echo "Missing actual TLOU FEX loader" >&2; exit 2; }
for library in libgow3cpu.so libgow3gpu.so libgow3present.so libc++_shared.so; do
  [[ -s "$out/lib/$library" ]] || { echo "Missing native $library" >&2; exit 2; }
done
"$llvm/llvm-readelf" -h "$out/bin/gow3-probe" | grep -q 'Machine:.*AArch64'
"$llvm/llvm-readelf" -l "$out/bin/gow3-probe" | grep -q '/system/bin/linker64'
for library in libgow3cpu.so libgow3gpu.so libgow3present.so libc++_shared.so; do
  "$llvm/llvm-readelf" -h "$out/lib/$library" | grep -q 'Machine:.*AArch64'
done
stage="$out/apk-jni/arm64-v8a"
assets="$out/apk-assets/runtime"
python3 android/bionic/stage_tools.py
mkdir -p "$stage" "$assets" "$root/out/release"
# Reproducible original v27 R1 for Adreno 6xx/7xx. Never use a rolling Mesa
# "latest" archive; the exact upstream asset digest is pinned for audit.
# This runs ONLY when packaging is explicitly requested, never during commits.
turnip_dir="$out/apk-assets/drivers"
turnip_zip="$turnip_dir/turnip-v27-r1-a6xx-a7xx.zip"
turnip_sha="07938ef843a73dd47ebb88ba9c2d3bca011667bd2b657e3b4a75d9ba8ed591a0"
turnip_gen8_zip="$turnip_dir/turnip-gen8-v38-patched-a8xx.zip"
turnip_gen8_sha="2bbb279a3da7680503a54a51a3051c7a4302c8fb2a089f8b183b4b5d6ba9ac17"
mkdir -p "$turnip_dir"
if [[ ! -s "$turnip_zip" ]] || ! echo "$turnip_sha  $turnip_zip" | sha256sum -c --status; then
  tmp="$turnip_zip.download"
  rm -f "$tmp"
  curl -fL --retry 3 --connect-timeout 20 --max-time 120 \
    "https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/download/v27.0.0-R1/Turnip_v27.0.0-R1.zip" \
    -o "$tmp"
  echo "$turnip_sha  $tmp" | sha256sum -c
  unzip -Z -1 "$tmp" | grep -q 'meta.json'
  unzip -Z -1 "$tmp" | grep -q 'libvulkan_freedreno.so'
  mv "$tmp" "$turnip_zip"
fi
echo "$turnip_sha  $turnip_zip" | sha256sum -c
# The Gen8 patched package is distinct from R1 and is pinned to the upstream digest.
if [[ ! -s "$turnip_gen8_zip" ]] || ! echo "$turnip_gen8_sha  $turnip_gen8_zip" | sha256sum -c --status; then
  temp="$turnip_gen8_zip.download"
  rm -f "$temp"
  curl -fL --retry 3 --connect-timeout 20 --max-time 120 \
    "https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/download/v27.0.0-R1/Turnip_Gen8_V38_Patched.zip" -o "$temp"
  echo "$turnip_gen8_sha  $temp" | sha256sum -c
  unzip -Z -1 "$temp" | grep -q 'meta.json'
  unzip -Z -1 "$temp" | grep -q 'libvulkan_freedreno.so'
  mv "$temp" "$turnip_gen8_zip"
fi
echo "$turnip_gen8_sha  $turnip_gen8_zip" | sha256sum -c
cp "$out/bin/gow3-probe" "$assets/gow3-probe"
for lib in libgow3cpu.so libgow3gpu.so libgow3present.so libc++_shared.so; do
  cp "$out/lib/$lib" "$stage/$lib"
done
gradle -p "$root/android-launcher" :app:assembleRelease --no-daemon --max-workers=2
unsigned="$root/android-launcher/app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -s "$unsigned" ]] || { echo "Gradle did not produce an unsigned APK" >&2; exit 2; }
aligned="$out/tlou-release-aligned.apk"
apk="$root/out/release/TheLastOfUsRemastered-Android-v0.1-arm64.apk"
"$android_tools/zipalign" -f -p 4 "$unsigned" "$aligned"
key="$out/experimental-development.jks"
if [[ ! -f $key ]]; then
  keytool -genkeypair -keystore "$key" -storepass 'TLOU-Bionic-development' \
    -alias tlou-bionic -keypass 'TLOU-Bionic-development' -keyalg RSA -keysize 3072 \
    -validity 3650 -dname 'CN=TLOU Experimental Android,O=Daedalus' -noprompt
fi
"$android_tools/apksigner" sign --ks "$key" --ks-key-alias tlou-bionic \
  --ks-pass pass:TLOU-Bionic-development --key-pass pass:TLOU-Bionic-development \
  --out "$apk" "$aligned"
"$android_tools/apksigner" verify --verbose "$apk"
"$android_tools/aapt" dump badging "$apk" | grep -q "package: name='com.daedalus.tlou.remastered'"
unzip -Z -1 "$apk" | grep -q 'lib/arm64-v8a/libgow3gpu.so'
unzip -Z -1 "$apk" | grep -q 'lib/arm64-v8a/libgow3cpu.so'
unzip -Z -1 "$apk" | grep -q 'lib/arm64-v8a/libgow3present.so'
unzip -Z -1 "$apk" | grep -q 'assets/runtime/gow3-probe'
unzip -Z -1 "$apk" | grep -q 'assets/tools.zip'
unzip -Z -1 "$apk" | grep -q 'lib/arm64-v8a/libpython3.13.so'
sha256sum "$apk" > "$apk.sha256"
echo "Experimental TLOU APK signed and validated: $apk"

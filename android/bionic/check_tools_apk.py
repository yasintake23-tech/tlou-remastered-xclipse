#!/usr/bin/env python3
"""Check that the installable APK really contains executable ARM64 preparation tools."""
import io
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import zipfile


def arm64(data, name):
    if data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError(f'{name} is not an ARM64 ELF')
    if b'/lib/ld-linux' in data or b'GLIBC_' in data:
        raise ValueError(f'{name} contains glibc references')


with zipfile.ZipFile(sys.argv[1]) as apk:
    with tempfile.TemporaryDirectory(prefix='tlou-apk-native-') as temp:
        root = Path(temp)
        names = ['assets/runtime/gow3-probe', *[f'lib/arm64-v8a/{lib}' for lib in
            ('libgow3cpu.so', 'libgow3gpu.so', 'libgow3present.so', 'libc++_shared.so')]]
        if b'g3PNjYKWqnQ#libSceRemoteplay' not in apk.read('assets/runtime/gow3-probe'):
            raise ValueError('Stale runtime: base TLOU Remote Play service is absent')
        probe = apk.read('assets/runtime/gow3-probe')
        for marker in (b'--vulkan-only', b'HEX:transfer_readback=', b'HEX:format.%s.game_image_create=', b'BC7_SRGB'):
            if marker not in probe:
                raise ValueError(f'Missing HEX pre-game Vulkan diagnostic marker: {marker!r}')
        allowed = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libnativewindow.so',
                   'libc++_shared.so', 'libgow3cpu.so', 'libgow3gpu.so', 'libvulkan.so'}
        for name in names:
            data = apk.read(name)
            arm64(data, name)
            path = root / Path(name).name
            path.write_bytes(data)
            dynamic = subprocess.check_output(['readelf', '-dW', str(path)], text=True)
            needed = set(re.findall(r'NEEDED.*?\[(.*?)\]', dynamic))
            if needed - allowed:
                raise ValueError(f'{name} needs unpackaged or non-Android libraries: {needed-allowed}')
        gpu_symbols=subprocess.check_output(['readelf','--dyn-syms','--wide',str(root/'libgow3gpu.so')],text=True)
        if any(name not in gpu_symbols for name in ('gow3gpu_save_choice_begin','gow3gpu_save_choice_cancel')):
            raise ValueError('Packaged GPU lacks structured Android save selection')
        interp = subprocess.check_output(['readelf', '-lW', str(root/'gow3-probe')], text=True)
        if '/system/bin/linker64' not in interp:
            raise ValueError('Runtime is not an Android executable')
        symbols = subprocess.check_output(['readelf', '--dyn-syms', '--wide', str(root/'libgow3present.so')], text=True)
        for method in ('nativeStart', 'nativeSetSurface', 'nativeFrames', 'nativeSetFullscreen', 'nativeSetAdrenoTurbo', 'nativeShutdown'):
            if 'Java_ro_cobrabm_fexdroid_Gow3Present_'+method not in symbols:
                raise ValueError(f'Packaged presenter lacks JNI {method}')
    # The v0.1 build must ship BOTH pinned Adreno generations plus localized
    # launcher resources; a successful Java compile alone is not enough.
    import hashlib
    drivers = {
        'assets/drivers/turnip-v27-r1-a6xx-a7xx.zip':
            '07938ef843a73dd47ebb88ba9c2d3bca011667bd2b657e3b4a75d9ba8ed591a0',
        'assets/drivers/turnip-gen8-v38-patched-a8xx.zip':
            '2bbb279a3da7680503a54a51a3051c7a4302c8fb2a089f8b183b4b5d6ba9ac17',
    }
    for name, expected in drivers.items():
        data = apk.read(name)
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f'Incorrect driver digest: {name}')
    ui_rows = apk.read('assets/ui-translations.tsv').decode('utf-8').splitlines()
    ui_rows = [line for line in ui_rows if line and not line.startswith('#')]
    if not ui_rows or any(len(line.split(chr(9))) != 10 for line in ui_rows):
        raise ValueError('Missing or malformed 10-language launcher translation table')
    arm64(apk.read('lib/arm64-v8a/libpython3.13.so'), 'libpython')
    with zipfile.ZipFile(io.BytesIO(apk.read('assets/tools.zip'))) as tools:
        for name in ('python/bin/python3.13', 'bin/orbis-pkg-util'):
            arm64(tools.read(name), name)
        for name in ('python/lib/python3.13/encodings/__init__.py',
                     'source/android/device_prepare.py', 'source/android/tlou_bionic.py',
                     'source/android/tlou_patches.py',
                     'source/gow3_pc/scripts/prepare.py', 'source/gow3_pc/scripts/link_modules.py',
                     'source/gow3_pc/scripts/link_libc.py', 'source/gow3_pc/scripts/content_profile.py',
                     'source/tools/probe_tlou.py'):
            if not tools.read(name):
                raise ValueError(f'Missing preparation component {name}')
        for module in ('_struct', '_posixsubprocess', '_sha2'):
            if not any('/lib-dynload/'+module+'.' in name for name in tools.namelist()):
                raise ValueError(f'Missing target Python extension {module}')
print('APK: Android ARM64 loader/dependencies, presenter JNI, PKG extractor, CPython and TLOU preparation verified')

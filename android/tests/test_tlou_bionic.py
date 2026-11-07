"""Host-only tests for Android TLOU title/update safety; no game dump needed."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / "tlou_bionic.py"
spec = importlib.util.spec_from_file_location("tlou_bionic", MODULE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def make_sfo(path, title="CUSA00552", version="01.11"):
    entries = (("TITLE_ID", title), ("APP_VER", version))
    keys = b"".join(k.encode() + b"\0" for k, _ in entries)
    vals = b"".join(v.encode() + b"\0" for _, v in entries)
    kstart = 20 + len(entries) * 16
    vstart = kstart + len(keys)
    buf = bytearray(b"\0PSF" + struct.pack("<4I", 0x101, kstart, vstart, len(entries)))
    ko = vo = 0
    for k, v in entries:
        buf.extend(struct.pack("<HHIII", ko, 0x0204, len(v)+1, len(v)+1, vo))
        ko += len(k)+1
        vo += len(v)+1
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(buf + keys + vals)


def make_game(root, *, title="CUSA00552", ver="01.11", include_libc=True):
    root.mkdir(parents=True, exist_ok=True)
    (root / "eboot.bin").write_bytes(b"test game")
    make_sfo(root / "sce_sys/param.sfo", title, ver)
    content = root / "dvdroot_ps4/build/ps4/main"
    content.mkdir(parents=True)
    (content / "boot.txt").write_text("unpacked resources fixture")
    (content / "boot1").mkdir()
    (content / "boot1/coin-3d.dds").write_bytes(b"coin")
    (content / "lut0").mkdir()
    (content / "lut0/default-color-gray.dds").write_bytes(b"lut")
    if include_libc:
        (root / "sce_module").mkdir(exist_ok=True)
        (root / "sce_module/libc.prx").write_bytes(b"libc")
    return root


class TestTLOUBionic(unittest.TestCase):
    def test_title_and_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = make_game(Path(tmp)/"game")
            self.assertEqual(m.require_game(base)["APP_VER"], "01.11")
            other = make_game(Path(tmp)/"other", title="CUSA01715")
            with self.assertRaisesRegex(m.PortError, "Wrong PS4 title"):
                m.require_game(other)

    def test_regional_sfo_requires_exact_known_executable(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = make_game(Path(tmp)/"regional", title="CUSA00556", ver="01.00")
            with self.assertRaisesRegex(m.PortError, "Wrong PS4 title"):
                m.require_game(base)
            # Simulate only the SHA returned by the byte-identical large user
            # executable; the normal dummy game fixture must still be rejected.
            with patch.object(m.hashlib, "sha256") as digest:
                digest.return_value.hexdigest.return_value = m.VERIFIED_SELF_SHA256
                profile = m.require_game(base)
                self.assertEqual(profile["PORT_PROFILE"], "TLOU-VERIFIED-SELF-SHA256")
                with self.assertRaisesRegex(m.PortError, "Wrong PS4 title"):
                    m.require_game(base, is_update=True)

    def test_verified_011_regions_and_early_content_integrity(self):
        with tempfile.TemporaryDirectory() as tmp:
            for title in sorted(m.REGIONAL_TITLES - {m.TITLE_ID}):
                game = make_game(Path(tmp)/title, title=title)
                with self.assertRaises(m.PortError):
                    m.require_game(game)
                with patch.object(m.hashlib, "sha256") as digest:
                    digest.return_value.hexdigest.return_value = next(iter(m.VERIFIED_SELF_111))
                    self.assertEqual(m.require_game(game)["PORT_PROFILE"], "TLOU-VERIFIED-SELF-SHA256")
            wrong = make_game(Path(tmp)/"wrong", title="CUSA99999")
            with patch.object(m.hashlib, "sha256") as digest:
                digest.return_value.hexdigest.return_value = next(iter(m.VERIFIED_SELF_111))
                with self.assertRaises(m.PortError): m.require_game(wrong)
            valid = make_game(Path(tmp)/"game")
            m.validate_game_assets(valid)
            root = valid/"dvdroot_ps4/build/ps4/main"
            archive = root/"core.psarc"
            archive.write_bytes(b"invalid"+bytes(64))
            with self.assertRaisesRegex(m.PortError,"PSARC inválido"):
                m.validate_game_assets(valid)
            # Valid minimal, internally bounded PSARC TOC without game data.
            archive.write_bytes(struct.pack(">4sI4sIIIII", b"PSAR", 0x10004, b"zlib",
                62, 30, 1, 65536, 0) + bytes(30))
            m.validate_game_assets(valid)
            archive.write_bytes(struct.pack(">4sI4sIIIII", b"PSAR", 0x10004, b"zlib",
                999999, 30, 1, 65536, 0) + bytes(30))
            with self.assertRaisesRegex(m.PortError, "índice incompleto"):
                m.validate_game_assets(valid)
            archive.unlink()
            self.assertEqual(m.validate_game_assets(valid), valid/"dvdroot_ps4")
            m.shutil.rmtree(valid/"dvdroot_ps4")
            with self.assertRaisesRegex(m.PortError, "raiz de recursos do jogo não foi localizada"):
                m.validate_game_assets(valid)
            self.assertEqual(m.require_game(valid)["TITLE_ID"], "CUSA00552")

    def test_content_roots_are_flattened_to_app0(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            game = make_game(root/"game")
            view = m.build_view(game, None, root/"out")
            self.assertTrue((view/"build").is_symlink())
            self.assertTrue((view/"build/ps4/main/boot.txt").is_file())
            self.assertTrue((view/"build/ps4/main/boot1/coin-3d.dds").is_symlink())
            self.assertFalse((view/"dvdroot_ps4").exists())

    def test_flat_app0_root_is_supported_without_dvdroot_folder(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            game = make_game(root/"game")
            import shutil
            shutil.copytree(game/"dvdroot_ps4/build", game/"build")
            shutil.rmtree(game/"dvdroot_ps4")
            self.assertEqual(m.validate_game_assets(game), game)
            view = m.build_view(game, None, root/"out")
            self.assertTrue((view/"build/ps4/main/boot.txt").is_symlink())

    def test_missing_resource_root_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            game = make_game(Path(tmp)/"game")
            import shutil
            shutil.rmtree(game/"dvdroot_ps4")
            with self.assertRaisesRegex(m.PortError, "raiz de recursos do jogo não foi localizada"):
                m.validate_game_assets(game)

    def test_update_overlay_never_mutates_game(self):
        with tempfile.TemporaryDirectory() as tmp:
            t = Path(tmp)
            base = make_game(t/"game", ver="01.00")
            update = make_game(t/"patch", ver="01.11", include_libc=False)
            (update / "eboot.bin").write_bytes(b"update eboot")
            (base / "dvdroot_ps4").mkdir(exist_ok=True)
            (base / "dvdroot_ps4/resource.bin").write_bytes(b"resource")
            view = m.build_view(base, update, t/"work")
            self.assertEqual((view/"eboot.bin").read_bytes(), b"update eboot")
            self.assertEqual((view/"sce_module/libc.prx").read_bytes(), b"libc")
            self.assertEqual((view/"resource.bin").read_bytes(), b"resource")
            self.assertEqual((base/"eboot.bin").read_bytes(), b"test game")
            self.assertTrue((view/"eboot.bin").is_symlink())
            self.assertFalse((view/"dvdroot_ps4").exists())
            self.assertTrue((view/"build").is_symlink())
            self.assertTrue((view/"build/ps4/main/boot.txt").is_file())
            self.assertTrue((view/"build/ps4/main/lut0/default-color-gray.dds").is_file())

    def test_invalid_sfo_and_missing_libc(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = make_game(Path(tmp)/"game", include_libc=False)
            with self.assertRaisesRegex(m.PortError, "Missing sce_module/libc.prx"):
                m.require_game(base)
            (base/"sce_sys/param.sfo").write_bytes(b"garbage")
            with self.assertRaises(m.PortError):
                m.read_sfo(base/"sce_sys/param.sfo")

    def test_output_must_not_be_inside_game(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = make_game(Path(tmp)/"game")
            with self.assertRaisesRegex(m.PortError, "outside the game"):
                m.prepare(Path(tmp), base, None, base / "out")

    def test_existing_signature_gates_patches(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(__file__).resolve().parents[2]
            if not (source / "tools/probe_tlou.py").is_file():
                self.skipTest("full repository not mounted in isolated module test")
            patcher = m.load_patch_module(source)
            elf = bytearray(0x300)
            struct.pack_into("<Q", elf, 0x20, 0x40)
            struct.pack_into("<HH", elf, 0x36, 56, 1)
            struct.pack_into("<IIQQQQQQ", elf, 0x40, 1, 5, 0x200, 0x1000, 0, 0x20, 0x20, 0x1000)
            elf[0x200:0x202] = bytes.fromhex("ABCD")
            input_path = Path(tmp) / "eboot.elf"
            output_path = Path(tmp) / "patches.bin"
            input_path.write_bytes(elf)
            patches = {"good": [(0x1000, "ABCD", "EF12")], "bad": [(0x1002, "DEAD", "BEAD")]}
            result = patcher.image_patches(input_path, output_path, patches)
            self.assertEqual(result, output_path)
            raw = output_path.read_bytes()
            self.assertEqual(raw[:8], b"G3PATCH2")
            self.assertEqual(struct.unpack_from("<Q", raw, 16)[0], 1)
            self.assertEqual(struct.unpack_from("<QQ", raw, 24), (0x1000, 2))
            self.assertEqual(raw[40:], bytes.fromhex("EF12"))

    def test_update_resources_are_never_silently_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = make_game(Path(tmp)/"game")
            update = make_game(Path(tmp)/"patch", include_libc=False)
            (update/"dvdroot_ps4").mkdir(exist_ok=True)
            with self.assertRaisesRegex(m.PortError, "native app0 overlay"):
                m.prepare(Path(tmp), base, update, Path(tmp)/"out")


if __name__ == "__main__":
    unittest.main()

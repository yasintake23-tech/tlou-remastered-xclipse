"""Device preparation cache and failure recovery without any game content."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import device_prepare as device
from test_tlou_bionic import make_game


class DevicePreparation(unittest.TestCase):
    def fake_prepare(self, source, game, update, out):
        (out / "boot-linked.bin").write_bytes(b"boot")
        (out / "content.bin").write_bytes(b"content")
        (out / "tlou-view").mkdir()
        (out / "tlou-view/eboot.bin").symlink_to(game / "eboot.bin")

    def test_prepared_game_without_dvdroot_still_launches(self):
        """Regression: user log (10) showed a working prepared 01.00 game
        blocked before guest launch by new required dvdroot_ps4 preflight."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            game = make_game(root / "CUSA00552", ver="01.00")
            import shutil
            shutil.copytree(game / "dvdroot_ps4/build", game / "build")
            shutil.rmtree(game / "dvdroot_ps4")
            out = root / "private/prepared"
            with patch.object(device, "prepare", side_effect=self.fake_prepare) as prepare, \
                 patch.object(device, "write_selected") as patches:
                # A previously supported layout must also prepare on first use.
                device.ensure(root, game, out, {"resolution": "720"})
                device.ensure(root, game, out, {"resolution": "720"})
                self.assertEqual(prepare.call_count, 1)
                self.assertEqual(patches.call_count, 2)
                self.assertEqual((out / "tlou-view/eboot.bin").read_bytes(), b"test game")

    def test_cache_and_modified_module(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            game = make_game(root / "game")
            out = root / "private/prepared"
            with patch.object(device, "prepare", side_effect=self.fake_prepare) as prepare:
                device.ensure(root, game, out)
                self.assertEqual((out / "tlou-view/eboot.bin").read_bytes(), b"test game")
                device.ensure(root, game, out)
                self.assertEqual(prepare.call_count, 1)
                (game / "sce_module/libc.prx").write_bytes(b"new libc")
                device.ensure(root, game, out)
                self.assertEqual(prepare.call_count, 2)
            self.assertEqual((game / "eboot.bin").read_bytes(), b"test game")

    def test_failed_preparation_preserves_previous_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            game = make_game(root / "game")
            out = root / "prepared"
            with patch.object(device, "prepare", side_effect=self.fake_prepare):
                device.ensure(root, game, out)
            original = (out / "device-ready.json").read_bytes()
            (game / "eboot.bin").write_bytes(b"different version")
            with patch.object(device, "prepare", side_effect=ValueError("unsupported executable")):
                with self.assertRaisesRegex(ValueError, "unsupported executable"):
                    device.ensure(root, game, out)
            self.assertEqual((out / "device-ready.json").read_bytes(), original)
            self.assertEqual(list(root.glob(".prepare-*")), [])

    def test_patch_options_do_not_repeat_preparation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);game=make_game(root/"game");out=root/"prepared"
            with patch.object(device,"prepare",side_effect=self.fake_prepare) as prepare, patch.object(device,"write_selected") as selected:
                device.ensure(root,game,out,{"resolution":"720"})
                device.ensure(root,game,out,{"resolution":"540"})
                self.assertEqual(prepare.call_count,1)
                self.assertEqual(selected.call_count,2)
                self.assertEqual(selected.call_args.args[2],{"resolution":"540"})

    def test_cannot_prepare_over_player_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            game = make_game(root / "game")
            for out in (game, game / "out", root):
                with self.assertRaisesRegex(ValueError, "separate"):
                    device.ensure(root, game, out)


if __name__ == "__main__":
    unittest.main()

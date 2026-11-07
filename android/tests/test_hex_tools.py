from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "android-launcher/app/src/main/java/ro/cobrabm/fexdroid"
ASSETS = ROOT / "android-launcher/app/src/main/assets"
class HexToolsTests(unittest.TestCase):
    def test_launcher_has_separate_hex_entrypoint(self):
        main=(JAVA/"MainActivity.java").read_text()
        manifest=(ROOT/"android-launcher/app/src/main/AndroidManifest.xml").read_text()
        self.assertIn('add(game,"HEX Ayarları"',main)
        self.assertIn('HexSettingsActivity',manifest)
        self.assertIn('android:exported="false"',manifest)

    def test_driver_diagnostics_are_pre_game_and_use_selected_vulkan_loader(self):
        probe=(ROOT/"gow3_pc/src/probe.c").read_text()
        smoke=(ROOT/"gow3_pc/src/vulkan_smoke.c").read_text()
        self.assertLess(probe.index('"--vulkan-only"'),probe.index("tlou_cpu_policy_prepare()"))
        self.assertIn("#define VK_NO_PROTOTYPES",smoke)
        self.assertIn("gow3_vk_get_instance_proc_addr()",smoke)
        self.assertIn("HEX:transfer_readback=",smoke)
        for fmt in ("BC4_UNORM","BC5_UNORM","BC6H_UFLOAT","BC7_SRGB","ASTC_4x4_UNORM","RGBA16F"):
            self.assertIn(fmt,smoke)

    def test_stable_xclipse_default_is_zero_workers(self):
        settings=(JAVA/"PortSettings.java").read_text()
        self.assertIn('draw_prep_workers","0"',settings)
        self.assertIn('String workers=p.getString("draw_prep_workers","0")',settings)
        self.assertIn('hex_stability_migration_v2',settings)

    def test_translations_have_unique_ten_column_rows(self):
        rows=[line.split("\t") for line in (ASSETS/"ui-translations.tsv").read_text().splitlines()
              if line and not line.startswith("#")]
        self.assertTrue(all(len(row)==10 and all(row) for row in rows))
        self.assertEqual(len(rows),len({row[0] for row in rows}))
        labels={row[0] for row in rows}
        self.assertIn("HEX Ayarları",labels)

if __name__ == "__main__":
    unittest.main()

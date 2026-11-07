package ro.cobrabm.fexdroid;

import java.util.Locale;
import java.util.Map;

/**
 * Conservative launch defaults for the pinned RADV Xclipse driver on Exynos 1580.
 *
 * The current black-output workaround is DCC-off. Driver diagnostics are enabled
 * so the selected RADV build can be identified during device validation.
 *
 * BC7 ASTC and BC6H RGBA16F conversions are intentionally not forced here:
 * BC7 ASTC roughly doubles texture upload time, while BC6H RGBA16F increases
 * memory use. The driver's compact defaults are retained until device evidence
 * shows either conversion is necessary. The RC1 combined depth-cache flush is
 * also retained; DBFLUSH=0 would force the older upstream path.
 */
final class XclipseRadvProfile {
    private XclipseRadvProfile() {}

    static boolean apply(Map<String,String> environment,boolean xclipse540,String driverName) {
        String name=driverName==null?"":driverName.toLowerCase(Locale.ROOT);
        // Do not affect a system driver, Adreno device, generic RADV build or
        // user-selected non-Xclipse custom package.
        if(!xclipse540 || !name.contains("radv") || !name.contains("xclipse")) return false;

        // Official RADV Xclipse compatibility switch for black/flickering output.
        environment.put("RADV_XCLIPSE_DCC","0");
        // Emits the driver's build and active-switch markers to logcat.
        environment.put("RADV_XCLIPSE_LOG","1");
        // Skip redundant full-frame swapchain clear when the game frame covers the screen.
        environment.put("GOW3_PRESENT_SKIP_CLEAR","1");
        // Never enable the slow per-submit hang tracer in normal gameplay.
        environment.remove("RADV_DEBUG");
        // Keep RC1 depth flush and compact BC4-BC7/BC6H defaults.
        environment.remove("RADV_XCLIPSE_DBFLUSH");
        environment.remove("RADV_XCLIPSE_BC7_ASTC");
        environment.remove("RADV_XCLIPSE_BC6H_RGBA16F");
        return true;
    }

    static String describe() {
        return "Xclipse 540 RADV profile: DCC=off (render-target compatibility), " +
            "RADV_XCLIPSE_LOG=1, PRESENT_SKIP_CLEAR=1; RC1 depth flush and compact BC conversion defaults retained.";
    }
}

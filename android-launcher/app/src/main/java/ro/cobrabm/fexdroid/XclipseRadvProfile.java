package ro.cobrabm.fexdroid;

import java.util.Locale;
import java.util.Map;

/**
 * Conservative compatibility defaults for the RADV Xclipse Android driver.
 *
 * These switches are documented by the RADV Xclipse build. They are applied
 * only to that driver on an actual Xclipse renderer; the Samsung system driver
 * and other GPUs are left untouched.
 */
final class XclipseRadvProfile {
    private XclipseRadvProfile() {}

    static boolean apply(Map<String,String> environment, String renderer, String driverName) {
        String gpu=renderer==null ? "" : renderer.toLowerCase(Locale.ROOT);
        String driver=driverName==null ? "" : driverName.toLowerCase(Locale.ROOT);
        if(!gpu.contains("xclipse") || !driver.contains("radv")) return false;

        // Avoid render-target compression on the initial compatibility pass.
        // This is the documented RADV workaround for black/flickering/corrupt output.
        environment.put("RADV_XCLIPSE_DCC","0");

        // Preserve higher-fidelity decoding for the BC formats seen in the device logs.
        // BC7->ASTC keeps the normal footprint/sampling rate; BC6H->RGBA16F costs more memory.
        environment.put("RADV_XCLIPSE_BC7_ASTC","1");
        environment.put("RADV_XCLIPSE_BC6H_RGBA16F","1");

        // Keep the Samsung gralloc linear shared-buffer path explicit for SurfaceFlinger.
        environment.put("MESA_GRALLOC_LINEAR","1");

        // Enable driver diagnostics without the very slow per-submit hang tracer.
        environment.put("RADV_XCLIPSE_LOG","1");
        return true;
    }
}

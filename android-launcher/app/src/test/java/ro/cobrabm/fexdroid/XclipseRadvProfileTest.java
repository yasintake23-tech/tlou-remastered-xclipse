package ro.cobrabm.fexdroid;

import java.util.HashMap;
import org.junit.Test;
import static org.junit.Assert.*;

public class XclipseRadvProfileTest {
    @Test public void detectsGalaxyA56ModelAndExynos1580Soc() {
        assertTrue(PortSettings.isXclipse540("SM-A566B",""));
        assertTrue(PortSettings.isXclipse540("SM-A566E",""));
        assertTrue(PortSettings.isXclipse540("SM-A566U",""));
        assertTrue(PortSettings.isXclipse540("SM-A5660",""));
        assertTrue(PortSettings.isXclipse540("SM-A566N",""));
        assertTrue(PortSettings.isXclipse540("SM-X520","Exynos 1580"));
        assertTrue(PortSettings.isXclipse540("unknown","s5e8855"));
        assertTrue(PortSettings.isXclipse540(null,"EXYNOS 1580"));
    }

    @Test public void rejectsOtherSamsungAndAdrenoDevices() {
        assertFalse(PortSettings.isXclipse540("SM-A556B","Exynos 1480"));
        assertFalse(PortSettings.isXclipse540("SM-A566B-CLONE",""));
        assertFalse(PortSettings.isXclipse540("Pixel","Snapdragon 8 Elite"));
        assertFalse(PortSettings.isXclipse540(null,null));
    }

    @Test public void usesConservativeJobWorkersOnXclipse540ToPreventDeadlock() {
        assertTrue(PortSettings.conservativeJobWorkers("SM-A566B",""));
        assertTrue(PortSettings.conservativeJobWorkers("SM-A566E","Exynos 1580"));
        assertTrue(PortSettings.conservativeJobWorkers("unknown","s5e8855"));
        assertTrue(PortSettings.conservativeJobWorkers("23049PCD8G","SM7475"));
        assertFalse(PortSettings.conservativeJobWorkers("CPH2691","SM8750"));
    }

    @Test public void appliesOnlyVerifiedXclipseRadvCompatibilityDefaults() {
        HashMap<String,String> env=new HashMap<>();
        assertTrue(XclipseRadvProfile.apply(env,true,"RADV Xclipse 27.0.0-RC1 · Xclipse 540"));
        assertFalse(env.containsKey("RADV_XCLIPSE_DCC"));
        assertEquals("1",env.get("RADV_XCLIPSE_LOG"));
        assertEquals("1",env.get("GOW3_PRESENT_SKIP_CLEAR"));
        assertEquals("0",env.get("GOW3_ASYNC_SHADERS"));
        assertFalse(env.containsKey("RADV_DEBUG"));
        assertFalse(env.containsKey("RADV_XCLIPSE_DBFLUSH"));
        assertFalse(env.containsKey("RADV_XCLIPSE_BC7_ASTC"));
        assertFalse(env.containsKey("RADV_XCLIPSE_BC6H_RGBA16F"));
    }

    @Test public void leavesSystemDriverAndOtherDriversUntouched() {
        HashMap<String,String> system=new HashMap<>();
        assertFalse(XclipseRadvProfile.apply(system,true,""));
        assertTrue(system.isEmpty());

        HashMap<String,String> genericRadv=new HashMap<>();
        assertFalse(XclipseRadvProfile.apply(genericRadv,true,"RADV Vulkan Driver"));
        assertTrue(genericRadv.isEmpty());

        HashMap<String,String> adreno=new HashMap<>();
        assertFalse(XclipseRadvProfile.apply(adreno,false,"RADV Xclipse"));
        assertTrue(adreno.isEmpty());
    }
}

package ro.cobrabm.fexdroid;

import static org.junit.Assert.*;
import java.util.HashMap;
import org.junit.Test;

public class XclipseRadvProfileTest {
    @Test public void appliesOnlyToXclipseWithRadv() {
        HashMap<String,String> env=new HashMap<>();
        assertTrue(XclipseRadvProfile.apply(env,"Samsung Xclipse 540","RADV Xclipse 27.0.0"));
        assertEquals("0",env.get("RADV_XCLIPSE_DCC"));
        assertEquals("0",env.get("RADV_XCLIPSE_DBFLUSH"));
        assertEquals("1",env.get("RADV_XCLIPSE_BC7_ASTC"));
        assertEquals("1",env.get("RADV_XCLIPSE_BC6H_RGBA16F"));
        assertEquals("1",env.get("MESA_GRALLOC_LINEAR"));
        assertEquals("1",env.get("RADV_XCLIPSE_LOG"));
    }

    @Test public void leavesSamsungSystemDriverUntouched() {
        HashMap<String,String> env=new HashMap<>();
        assertFalse(XclipseRadvProfile.apply(env,"Samsung Xclipse 540",""));
        assertTrue(env.isEmpty());
    }

    @Test public void leavesOtherGpuUntouched() {
        HashMap<String,String> env=new HashMap<>();
        assertFalse(XclipseRadvProfile.apply(env,"Adreno 830","RADV Xclipse"));
        assertTrue(env.isEmpty());
    }

    @Test public void requiresBothXclipseAndRadv() {
        HashMap<String,String> env=new HashMap<>();
        assertFalse(XclipseRadvProfile.apply(env,"Mali-G715","RADV"));
        assertTrue(env.isEmpty());
    }
}

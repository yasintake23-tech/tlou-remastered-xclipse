// Adapted from Bloodborne-Android 2e472653. Used only by the experimental Bionic launch path.
package ro.cobrabm.fexdroid;

import android.view.Surface;
import android.view.SurfaceHolder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The Bionic game process's frame path (tools/android/present/gow3_present_proto.h): libgow3present.so
 * listens on a unix socket, receives AHardwareBuffers from the game and shows them on the
 * SurfaceView with an ASurfaceControl. The game stays connected while the surface comes and goes:
 * with no surface its frames are dropped, with a new one they show again.
 */
public final class Gow3Present {
    static { System.loadLibrary("gow3present"); }
    private static native String nativeStart(String socketPath);
    private static native void nativeSetSurface(Surface surface);
    private static native boolean nativeSurfaceReady();
    private static native String nativeDiagnostics();
    private static native long nativeFrames();
    private static native void nativeSetFullscreen(boolean enabled);
    private static native void nativeShutdown();
    private static native boolean nativeSetAdrenoTurbo(boolean enabled);

    /** Rootless KGSL GPU max-power policy; unsupported devices return false. */
    static boolean adrenoTurbo(boolean enabled) {
        try { return nativeSetAdrenoTurbo(enabled); }
        catch (Throwable e) { android.util.Log.w("TLOU", "Adreno Turbo JNI unavailable",e); return false; }
    }

    private static SurfaceHolder.Callback callback;
    private static SurfaceHolder attached;
    private static volatile CountDownLatch initialSurfaceReady = new CountDownLatch(1);

    private static void attachSurface(Surface surface) {
        if (surface == null || !surface.isValid()) return;
        setSurface(surface);
        if (nativeSurfaceReady()) initialSurfaceReady.countDown();
    }

    /** Wait off the UI thread until the actual Android presentation layer exists. */
    public static boolean awaitSurface(long timeoutMs) throws InterruptedException {
        if (nativeSurfaceReady()) return true;
        CountDownLatch latch = initialSurfaceReady;
        return latch.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS) && nativeSurfaceReady();
    }

    public static String diagnostics() {
        try { return nativeDiagnostics(); }
        catch (Throwable e) { return "presentation diagnostics unavailable: " + e; }
    }

    /** Listens on socketPath (once) and shows the game on this surface until it is destroyed. */
    public static synchronized String start(SurfaceHolder holder, String socketPath) {
        return start(holder, socketPath, false);
    }

    public static synchronized String start(SurfaceHolder holder, String socketPath, boolean fullscreen) {
        initialSurfaceReady = new CountDownLatch(1);
        nativeSetFullscreen(fullscreen);
        String result = nativeStart(socketPath);
        if (!result.equals("present running")) return result;
        used = true;
        if (attached != null && callback != null) attached.removeCallback(callback);
        attached = holder;
        callback = new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { attachSurface(h.getSurface()); }
            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int height) {
                if (!nativeSurfaceReady()) attachSurface(h.getSurface());
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) { setSurface(null); }
        };
        holder.addCallback(callback);
        attachSurface(holder.getSurface());
        return "bridge running: bionic present on " + socketPath;
    }

    private static synchronized void setSurface(Surface surface) { nativeSetSurface(surface); }

    /** Frames the game has presented to the app so far. */
    public static long frames() { return nativeFrames(); }

    private static boolean used;
    /** frames() for the status poll: 0 until the Bionic path was started. */
    public static long framesIfLoaded() { return used ? nativeFrames() : 0; }

    public static synchronized void fullscreen(boolean enabled) { if(used)nativeSetFullscreen(enabled); }

    /** Ends the listener and the display (the app is closing). */
    public static synchronized void stop() { nativeShutdown(); }
}

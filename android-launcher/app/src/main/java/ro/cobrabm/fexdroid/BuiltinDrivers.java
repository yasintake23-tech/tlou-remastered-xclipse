package ro.cobrabm.fexdroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;

/** Install the pinned official Turnip 27.0.0-R1 only on detected Adreno 6xx/7xx. */
final class BuiltinDrivers {
    private static final String ASSET="drivers/turnip-v27-r1-a6xx-a7xx.zip";
    private static final String SHA="07938ef843a73dd47ebb88ba9c2d3bca011667bd2b657e3b4a75d9ba8ed591a0";
    private static final String ASSET_8XX="drivers/turnip-gen8-v38-patched-a8xx.zip";
    private static final String SHA_8XX="2bbb279a3da7680503a54a51a3051c7a4302c8fb2a089f8b183b4b5d6ba9ac17";

    private BuiltinDrivers() {}

    // Query an actual EGL renderer. Build.MODEL and Snapdragon model strings
    // cannot reliably distinguish Adreno 7xx from 8xx; do not guess.
    static String renderer() {
        EGLDisplay display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if(display==EGL14.EGL_NO_DISPLAY)return "";
        int[] version=new int[2];
        if(!EGL14.eglInitialize(display,version,0,version,1))return "";
        EGLContext context=EGL14.EGL_NO_CONTEXT;
        EGLSurface surface=EGL14.EGL_NO_SURFACE;
        try {
            int[] attributes={
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_NONE
            };
            EGLConfig[] config=new EGLConfig[1];
            int[] count={0};
            if(!EGL14.eglChooseConfig(display,attributes,0,config,0,1,count,0) ||
               count[0]<1)return "";
            context=EGL14.eglCreateContext(display,config[0],EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            if(context==EGL14.EGL_NO_CONTEXT)return "";
            surface=EGL14.eglCreatePbufferSurface(display,config[0],
                new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
            if(surface==EGL14.EGL_NO_SURFACE)return "";
            if(!EGL14.eglMakeCurrent(display,surface,surface,context))return "";
            String gpu=GLES20.glGetString(GLES20.GL_RENDERER);
            return gpu==null?"":gpu;
        } finally {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);
            if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);
            EGL14.eglTerminate(display);
        }
    }

    static synchronized void ensureDefault(Context context) throws Exception {
        SharedPreferences p=PortSettings.prefs(context);
        // Even an explicit "system driver" choice (empty path) must win.
        if(p.contains("driver_path"))return;
        final String gpu;
        try { gpu=renderer(); }
        catch(Throwable e) { return; } // unknown GPU: keep system default
        String name=gpu.toLowerCase(Locale.ROOT);
        if(PortSettings.isXclipse540() && name.contains("xclipse")) {
            installBundled(context,p,"drivers/radv-xclipse-27.0.0-devel-fe9b894.zip",
                "9325769617812ddca2051c40364e33a2d85c6b3fa8c6c3e6208eb251549d4745",
                "RADV Xclipse 27.0.0-RC1 · Xclipse 540","vulkan.radeon.so",34);
            return;
        }
        if(!name.contains("adreno"))return;
        boolean a8=name.matches(".*\\b8[0-9]{2}\\b.*");
        boolean a67=name.matches(".*\\b[67][0-9]{2}\\b.*");
        if(!a8 && !a67)return;
        String asset=a8?ASSET_8XX:ASSET;
        String digest=a8?SHA_8XX:SHA;
        String display=a8?"Turnip Gen8 V38 Patched · Adreno 8xx":"Turnip v27.0.0-R1 · Adreno 6xx/7xx";
        installBundled(context,p,asset,digest,display,"libvulkan_freedreno.so",26);
    }

    private static void installBundled(Context context,SharedPreferences prefs,String asset,
            String digest,String display,String fallbackLibrary,int fallbackApi) throws Exception {
        File drivers=new File(context.getFilesDir(),"drivers");
        try(InputStream archive=context.getAssets().open(asset)){
            DriverArchive.Installed installed=DriverArchive.install(
                drivers,archive,Build.VERSION.SDK_INT,file->{
                    JSONObject meta=new JSONObject(new String(Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
                    return new DriverArchive.Metadata(
                        meta.optString("name",display),
                        meta.optString("libraryName",fallbackLibrary),
                        meta.optInt("minApi",fallbackApi));
                });
            if(!digest.equals(installed.id))
                throw new IllegalStateException(display+" archive SHA-256 mismatch");
            prefs.edit().putString("driver_path",installed.library.getAbsolutePath())
                .putString("driver_name",display).apply();
        } catch(java.io.FileNotFoundException missingAsset) {
            // Developer checkout without staged APK assets: retain system driver.
        }
    }
}

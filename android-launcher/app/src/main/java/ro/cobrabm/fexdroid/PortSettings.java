package ro.cobrabm.fexdroid;

import android.content.Context;
import android.os.Build;
import java.util.Locale;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Log 32 launch profile. Experimental A/B switches are NOT user preferences. */
final class PortSettings {
    static final String[] EFFECTS={"ssao","motion_blur","depth_of_field","bloom","sunlight_shadows","subsurface"};
    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("tlou",Context.MODE_PRIVATE);
    }

    static boolean isXclipse540() {
        final String model=Build.MODEL==null?"":Build.MODEL;
        String soc=Build.VERSION.SDK_INT>=31 && Build.SOC_MODEL!=null
            ?Build.SOC_MODEL:"";
        if(soc.isEmpty()) {
            final String hw=Build.HARDWARE==null?"":Build.HARDWARE;
            final String board=Build.BOARD==null?"":Build.BOARD;
            soc=hw+" "+board;
        }
        return isXclipse540(model,soc);
    }

    // Kept pure so every supported A56/Exynos identifier can be regression-tested.
    static boolean isXclipse540(String model,String soc) {
        final String normalizedModel=model==null?"":model.toUpperCase(Locale.ROOT);
        final String normalizedSoc=soc==null?"":soc.toUpperCase(Locale.ROOT);
        return (normalizedModel.startsWith("SM-A566") && normalizedModel.length() <= 9) ||
            normalizedSoc.contains("EXYNOS 1580") || normalizedSoc.contains("S5E8855");
    }

    // Existing player-selected game, driver, patch and resolution preferences survive upgrades.
    // Old experiment preferences cannot silently reactivate discarded modes.
    static void retireTestPreferences(Context context) {
        SharedPreferences.Editor edit=prefs(context).edit();
        for(String key:new String[]{"memory_walk_once","ring_binding_cursor","draw_counter_trim",
            "compute_perm_memo","host_copy_batch","srt_hot_readbacks","fetch_no_block",
            "land_on_guest","static_min_512","stack_mapped_readback","inline_single_copy",
            "split_cs64","mapped_fault_readbacks","mapped_readbacks","gpu_profile",
            "prep_pool","cpu_trims","prefetch_pipelines","parallel_pipelines",
            "prep_workers","frames_ahead","descriptor_bulk","readback","sotc_precise_narrow",
            "compute_wait_fast","relaxed_skinning_fp"}) edit.remove(key);
        SharedPreferences current=prefs(context);
        if (isXclipse540()) {
            // v0.2 HEX stability migration: v0.1 enabled a speculative draw-prep
            // worker and changed the skin effect without a matched A/B result.
            // Restore the previous stable choices once; keep all unrelated settings.
            if (current.getBoolean("xclipse540_skin_lighting_v1",false) &&
                !current.getBoolean("hex_stability_migration_v2",false)) {
                edit.putBoolean("subsurface",true);
                edit.putString("draw_prep_workers","0");
                edit.putBoolean("hex_stability_migration_v2",true);
            }
            if (!current.contains("draw_prep_workers")) edit.putString("draw_prep_workers","0");
        }
        String limit=current.getString("fps_limit","60");
        if(!"30".equals(limit) && !"60".equals(limit))edit.putString("fps_limit","60");
        edit.apply();
    }

    static File patchOptions(Context context) throws Exception {
        SharedPreferences p=prefs(context);
        JSONObject options=new JSONObject();
        // GPU-heavy proxy scaling kept the game native at 1080p and resolved
        // intermediate targets repeatedly. Direct 01.00 executable scaling
        // reduces ALL scene/post allocations; the existing Vulkan UI composer
        // expands the completed LDR background before drawing the HUD at 1080p.
        // "proxy" is the earlier fallback with separately allocated Vulkan
        // targets. The user has tested the direct-performance method at 720p
        // and original 1080p, NOT the proxy fallback in this build.
        // Never silently change the saved resolution.
        final String resolution=p.getString("resolution","original");
        final boolean proxy="proxy".equals(p.getString("render_path","guest"));
        options.put("resolution",proxy?"original":resolution);
        // Xiaomi 23049PCD8G reported a real HALT after 806 seconds. The
        // OnePlus CPH2691 reported only a "long job" warning and a later
        // SIGTERM on user exit; it is NOT evidence of a guest deadlock.
        // Preserve original wake predicates only for the confirmed Xiaomi SoC.
        options.put("job_sleep",!conservativeJobWorkers());
        for(String key:EFFECTS) options.put(key,p.getBoolean(key,false));
        File file=new File(context.getFilesDir(),"patch-options.json");
        Files.write(file.toPath(),options.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    static boolean conservativeJobWorkers() {
        final String model=Build.MODEL==null?"":Build.MODEL;
        String soc=Build.VERSION.SDK_INT>=31 && Build.SOC_MODEL!=null
            ?Build.SOC_MODEL:"";
        if(soc.isEmpty()) {
            final String hw=Build.HARDWARE==null?"":Build.HARDWARE;
            final String board=Build.BOARD==null?"":Build.BOARD;
            soc=hw+" "+board;
        }
        return conservativeJobWorkers(model,soc);
    }

    static boolean conservativeJobWorkers(String model,String soc) {
        final String normalizedModel=model==null?"":model.toUpperCase(Locale.ROOT);
        final String normalizedSoc=soc==null?"":soc.toUpperCase(Locale.ROOT);
        // Galaxy A56 5G (Exynos 1580 / Xclipse 540) and Xiaomi 23049PCD8G hit
        // HALT: Job System Deadlock Detected (corelib/job/job-scheduling.cpp:746)
        // when job-sleep forces workers onto a condvar without patching counter-decrement wakes.
        return isXclipse540(model,soc) || normalizedSoc.contains("SM7475") || normalizedModel.equals("23049PCD8G");
    }

    static void environment(Context context,Map<String,String> env) {
        SharedPreferences p=prefs(context);
        String resolution=p.getString("resolution","original");
        // Direct game mode: scene, gameplay HUD, crosshair and depth all
        // use the selected resolution. The game's full/half post targets
        // also follow this resolution, while the source movie decoding stays
        // untouched. No extra Vulkan 720p->1080p HUD composition is inserted.
        // "proxy" remains a separate optional compatibility method.
        final String scaledPreset=resolution.equals("720")?"1":
            resolution.equals("540")?"3":resolution.equals("360")?"4":null;
        final String renderPath=p.getString("render_path","guest");
        final boolean proxy="proxy".equals(renderPath);
        env.put("TLOU_EXPECT_SCENE_SIZE","1920x1080");
        env.put("GOW3_UPSCALER","off");
        env.put("GOW3_OUTPUT_RES","1920x1080");
        if(scaledPreset!=null) {
            final String actualSize=resolution.equals("720")?"1280x720":
                resolution.equals("540")?"960x540":"640x360";
            env.put("TLOU_EXPECT_HOST_SCENE",actualSize);
            if(proxy) {
                env.remove("GOW3_RENDER_RES");
                env.remove("TLOU_UNIFIED_GUEST_UI");
                env.remove("GOW3_MOVIE_SIZE");
                env.put("GOW3_UPSCALE_PRESET",scaledPreset);
                env.put("TLOU_SPATIAL_SCENE","1");
                env.put("TLOU_HOST_SCENE_MODE","1");
            } else {
                // Scene/HUD/aim are patched to the same actualSize in eboot.
                // Deliberately do not request any guest upscaler: that was
                // forcing a second 1920x1080 HUD attachment and mixing it
                // with 720p scene depth even with FSR temporal switched off.
                env.remove("GOW3_RENDER_RES");
                env.remove("GOW3_UPSCALE_PRESET");
                env.remove("TLOU_SPATIAL_SCENE");
                env.remove("TLOU_HOST_SCENE_MODE");
                env.put("TLOU_UNIFIED_GUEST_UI","1");
                env.put("TLOU_EXPECT_SCENE_SIZE",actualSize);
                // Video-only NV12 adaptation: decoder stays native 1080p,
                // while the movie's Y+UV sampling planes use the display's
                // 720p layout. This flag is ignored by every gameplay shader.
                env.put("GOW3_MOVIE_SIZE",actualSize);
            }
        } else {
            env.remove("GOW3_RENDER_RES");
            env.remove("TLOU_UNIFIED_GUEST_UI");
            env.remove("GOW3_MOVIE_SIZE");
            env.remove("TLOU_SPATIAL_SCENE");
            env.remove("TLOU_HOST_SCENE_MODE");
            env.remove("TLOU_EXPECT_HOST_SCENE");
            env.remove("GOW3_UPSCALE_PRESET");
        }

        // Exact flags observed in launch.log of TLOU-Android-Logs (32).zip.
        // Its preceding launch-previous.log used different v0.31 trial flags:
        // the more recent actual run had the three below OFF.
        env.put("TLOU_MEMORY_WALK_ONCE","1");
        env.put("TLOU_RING_BINDING_CURSOR","1");
        env.put("TLOU_DRAW_COUNTER_TRIM","0");
        env.put("GOW3_TLOU_COMPUTE_MEMO","0");
        env.put("GOW3_TLOU_HOST_COPY_BATCH","0");
        env.put("TLOU_SRT_HOT_READBACKS","1");
        env.put("TLOU_SRT_DEP_BATCH","0");
        env.put("GOW3_FETCH_NO_BLOCK","1");
        env.put("GOW3_LAND_ON_GUEST","1");
        env.put("GOW3_STATIC_MIN_BYTES","512");
        env.put("TLOU_STACK_MAPPED_READBACK","1");
        env.put("TLOU_INLINE_SINGLE_COPY","1");
        env.put("TLOU_SPLIT_CS64","1");
        env.put("TLOU_MAPPED_FAULT_READBACKS","1");
        env.put("TLOU_MAPPED_READBACKS","1");
        // Eight bounded CPU samples per second per draw/dispatch role. The latest
        // gameplay log has 95% command and 84% draw CPU, but not the phase
        // breakdown needed to avoid another speculative APK. This sampler
        // uses CPU-thread clocks only and remains disableable by an env override.
        // The native Android default is ON unless explicitly disabled.
        env.put("TLOU_CPU_PHASE_SAMPLES","1");
        env.put("TLOU_FAST_RENDER_CORES","1");
        // Leave profiling truly disabled. Some native switches check presence,
        // so setting "0" instead of removing is incorrect.
        for(String name:new String[]{"GOW3_GPU_PROFILE","GOW3_FRAME_STATS","GOW3_PERF_DIAG"})
            env.remove(name);

        env.put("GOW3_READBACKS","1"); // Precise=2 caused artifacts; never resurrect.
        env.put("GOW3_EOP_READBACK","1"); // Selective readbacks, not Precise/Readback 2.
        // Readback 1 stays on at all levels. Every exact physics EOP result
        // already calls DownloadRangesAsync(..., protect=true, yield_to_cpu=true),
        // which read-protects its own pages without a global switch.
        // Protecting ALL other pending readbacks caused many unrelated page
        // faults and a sharp performance regression on Adreno 830.
        // Only shader-independent Full geometry synchronization is retained:
        // both user-provided 720p and native 1080p logs used Full, without the
        // previously suspected performance regression. No more Fast/Balanced
        // profiles which can return character deformation.
        env.put("TLOU_AUTO_SMALL_CS_READBACK","2");
        env.put("GOW3_ASYNC_READBACK","1");
        // Coverage audit was completed in earlier device logs. Do not run
        // its atomic identity tracking on the hot compute-binding path during
        // normal gameplay; an explicit native diagnostic can still enable it.
        env.remove("TLOU_PHYSICS_COVERAGE");
        env.put("GOW3_ASYNC_SHADERS","1");
        if (isXclipse540()) env.put("GOW3_ASYNC_SHADERS","0");
        env.put("GOW3_FRAMES_AHEAD","0");
        env.put("TLOU_DESCRIPTOR_BULK","0");
        env.put("GOW3_PREP_POOL","1");
        env.put("GOW3_CPU_TRIMS","1");
        env.put("GOW3_PREFETCH_PIPELINES","1");
        env.put("GOW3_PARALLEL_PIPELINES","1");
        String workers=p.getString("draw_prep_workers","0");
        if (!"0".equals(workers) && !"1".equals(workers) && !"2".equals(workers)) workers="0";
        env.put("GOW3_PREP_WORKERS",workers);
        env.put("GOW3_DEFERRED_READBACK","0");
        env.put("GOW3_STALE_READBACK","0");

        // Only presentation controls remain user-adjustable.
        env.put("GOW3_VSYNC",p.getBoolean("vsync",true)?"1":"0");
        env.put("GOW3_FPS_LIMIT",fpsLimit(context));
    }

    static String experimentReport(Context context,Map<String,String> env) {
        StringBuilder result=new StringBuilder("TLOU launch settings: resolution=")
            .append(prefs(context).getString("resolution","original"));
        result.append("; renderer=Vulkan; CPU=FEX ARM64");
        result.append("; game-raster=").append(env.getOrDefault("TLOU_EXPECT_SCENE_SIZE","1920x1080"));
        result.append("; game-ui=")
              .append(env.containsKey("TLOU_UNIFIED_GUEST_UI")
                      ?env.getOrDefault("TLOU_EXPECT_SCENE_SIZE","1920x1080")
                      :"1920x1080");
        result.append("; video-decoding=original; movie-plane-layout=")
              .append(env.getOrDefault("GOW3_MOVIE_SIZE","native"));
        result.append("; host-scene=")
              .append(env.getOrDefault("TLOU_EXPECT_HOST_SCENE","native"));
        result.append("; scene-composite=")
              .append(env.containsKey("TLOU_UNIFIED_GUEST_UI")?"unified-direct-no-ui-blit":
                      env.containsKey("TLOU_SPATIAL_SCENE")?"proxy-spatial-compat":"native");
        result.append("; temporal-fsr=off");
        result.append("; geometry-sync=full");
        result.append("; async-global-protect=").append(env.containsKey("GOW3_ASYNC_READBACK")?"on":"off");
        result.append("; geometry-sync policy=2 full only");
        result.append("; draw-preparation-workers=").append(env.getOrDefault("GOW3_PREP_WORKERS","0"));
        result.append("; guest-job-worker-wake=")
              .append(conservativeJobWorkers()?"original":"patched-sleep");
        return result.toString();
    }

    static String fpsLimit(Context context) {
        return "30".equals(prefs(context).getString("fps_limit","60"))?"30":"60";
    }
    static String summary(Context context) {
        SharedPreferences p=prefs(context);
        String r=p.getString("resolution","original"), limit=fpsLimit(context);
        String res=r.equals("original")?"Original":r+"p";
        return res+"  ·  "+(limit+" FPS")+"  ·  "+
            (p.getBoolean("vsync",true)?"VSync ON":"VSync OFF");
    }
}

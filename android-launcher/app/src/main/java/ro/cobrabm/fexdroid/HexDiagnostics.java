package ro.cobrabm.fexdroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pre-game HEX diagnostics and stable, read-only reports. */
final class HexDiagnostics {
    private static final Pattern FRAME=Pattern.compile("TLOU frame summary:\\s*([0-9]+(?:\\.[0-9]+)?) flips/s worst=([0-9]+(?:\\.[0-9]+)?)ms");
    private HexDiagnostics() {}

    static File directory(Context c) throws IOException {
        File dir=new File(c.getFilesDir(),"tlou/hex");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("HEX report directory unavailable");
        return dir;
    }
    private static String readTail(File file,int maxBytes) {
        if(file==null||!file.isFile())return "";
        try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
            long start=Math.max(0,in.length()-maxBytes);
            in.seek(start);
            byte[] data=new byte[(int)(in.length()-start)];
            in.readFully(data);
            return new String(data,StandardCharsets.UTF_8);
        } catch(Exception e) { return "[Could not read "+file.getName()+": "+e+"]"; }
    }
    static File installProbe(Context c) throws Exception {
        File root=new File(c.getFilesDir(),"tlou/runtime");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Cannot create private runtime folder");
        File target=new File(root,"tlou-probe");
        File staged=new File(root,"tlou-probe.hex.tmp");
        try(InputStream in=c.getAssets().open("runtime/gow3-probe");
            FileOutputStream out=new FileOutputStream(staged)) {
            byte[] buffer=new byte[128*1024];int n;long total=0;
            while((n=in.read(buffer))!=-1){total+=n;if(total>64L*1024*1024)throw new IOException("Probe payload exceeds limit");out.write(buffer,0,n);}
            out.getFD().sync();
        }
        if(staged.length()<8192)throw new IOException("Invalid Vulkan diagnostic executable");
        if(!staged.setExecutable(true,true))throw new IOException("Cannot mark Vulkan diagnostic executable");
        if(target.exists()&&!target.delete())throw new IOException("Cannot replace diagnostic executable");
        if(!staged.renameTo(target))throw new IOException("Cannot publish diagnostic executable");
        return target;
    }

    static String runVulkanTest(Context context,boolean systemDriver) throws Exception {
        File reportDir=directory(context);
        File binary=installProbe(context);
        SharedPreferences prefs=PortSettings.prefs(context);
        String selected=prefs.getString("driver_path","");
        String driverName=prefs.getString("driver_name","System Vulkan driver");
        if(!systemDriver&&!selected.isEmpty()) {
            File driver=new File(selected);
            if(!driver.isFile())throw new IOException("Selected driver file is missing");
            DriverArchive.validateLibrary(driver);
        }
        File home=new File(context.getFilesDir(),"tlou/hex-home");
        File temp=new File(context.getFilesDir(),"tlou/hex-tmp");
        for(File dir:new File[]{home,temp})
            if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create private diagnostic directory: "+dir.getName());
        File output=new File(reportDir,systemDriver?"vulkan-system.txt":"vulkan-selected.txt");
        File pid=new File(reportDir,systemDriver?"vulkan-system.pid":"vulkan-selected.pid");
        Files.deleteIfExists(output.toPath());
        List<String> args=Arrays.asList(binary.getAbsolutePath(),"--vulkan-only");
        ProcessBuilder builder=new ProcessBuilder(RuntimeProcess.command(args,pid));
        Map<String,String> env=builder.environment();
        env.put("HOME",home.getAbsolutePath());
        env.put("TMPDIR",temp.getAbsolutePath());
        env.put("XDG_RUNTIME_DIR",temp.getAbsolutePath());
        String nativeDir=context.getApplicationInfo().nativeLibraryDir;
        if(!systemDriver&&!selected.isEmpty()) {
            File custom=new File(selected);
            env.put("GOW3_VULKAN_DRIVER",custom.getAbsolutePath());
            env.put("LD_LIBRARY_PATH",custom.getParent()+":"+nativeDir);
        } else {
            env.remove("GOW3_VULKAN_DRIVER");
            env.put("LD_LIBRARY_PATH",nativeDir);
            driverName="System Vulkan driver";
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(output);
        long started=System.currentTimeMillis();
        Process process=builder.start();
        boolean completed=process.waitFor(50,TimeUnit.SECONDS);
        if(!completed) {
            process.destroy();
            if(!process.waitFor(1,TimeUnit.SECONDS))process.destroyForcibly();
        }
        int exit=completed?process.exitValue():124;
        String body=readTail(output,1024*1024);
        return "===== "+(systemDriver?"SYSTEM DRIVER":"SELECTED DRIVER")+" =====\n"+
            "Driver: "+driverName+"\n"+
            "Exit: "+(completed?exit:"TIMEOUT (50 seconds)")+"\n"+
            "Elapsed: "+(System.currentTimeMillis()-started)+" ms\n"+
            body+"\n";
    }

    static String summarizeText(String data) {
        long bc4=0,bc5=0,bc6=0,bc7=0,pipelines=0,longJobs=0,luts=0,rain=0;
        for(String line:data.split("\\R")) {
            if(line.contains("image.cpp:694")) {
                if(line.contains("Bc4")||line.contains("BC4"))bc4++;
                if(line.contains("Bc5")||line.contains("BC5"))bc5++;
                if(line.contains("Bc6")||line.contains("BC6"))bc6++;
                if(line.contains("Bc7")||line.contains("BC7"))bc7++;
            }
            if(line.contains("Draws without a pipeline")||line.contains("No pipeline for"))pipelines++;
            if(line.contains("A really long job")||line.contains("deadlocked"))longJobs++;
            if(line.contains("default-color-gray.dds")||line.contains("LutTableManager"))luts++;
            if(line.contains("Trying to load rain material"))rain++;
        }
        double total=0,min=Double.POSITIVE_INFINITY,max=0,worst=0,last=Double.NaN;
        int frames=0;
        ArrayDeque<String> recent=new ArrayDeque<>();
        for(String line:data.split("\\R")) {
            Matcher m=FRAME.matcher(line);
            if(m.find()) {
                double fps=Double.parseDouble(m.group(1)),ms=Double.parseDouble(m.group(2));
                total+=fps;min=Math.min(min,fps);max=Math.max(max,fps);worst=Math.max(worst,ms);last=fps;frames++;
                if(recent.size()==12)recent.removeFirst();
                recent.addLast(String.format(Locale.ROOT,"%.1f FPS / %.1f ms",fps,ms));
            }
        }
        StringBuilder result=new StringBuilder();
        result.append("BC4 errors: ").append(bc4).append('\n');
        result.append("BC5 errors: ").append(bc5).append('\n');
        result.append("BC6H errors: ").append(bc6).append('\n');
        result.append("BC7 errors: ").append(bc7).append('\n');
        result.append("Missing pipeline events: ").append(pipelines).append('\n');
        result.append("Long-job/deadlock warnings: ").append(longJobs).append('\n');
        result.append("LUT-related events: ").append(luts).append('\n');
        result.append("Rain material events: ").append(rain).append('\n');
        result.append("Frame summaries: ").append(frames).append('\n');
        if(frames>0) {
            result.append(String.format(Locale.ROOT,
                "FPS mean/min/max/last: %.2f / %.2f / %.2f / %.2f\n",
                total/frames,min,max,last));
            result.append(String.format(Locale.ROOT,"Worst reported frame: %.1f ms\n",worst));
            result.append("Last frame samples:\n");
            for(String item:recent)result.append("  ").append(item).append('\n');
        } else result.append("No frame summary records found.\n");
        result.append("Note: Android process exit status 0 does not prove rendered frames are correct.\n");
        return result.toString();
    }

    static String analyzeGameLog(Context c) {
        File log=new File(c.getFilesDir(),"tlou/runtime.log");
        if(!log.isFile())return "No game runtime log has been created yet.\n";
        return "Runtime log: "+log.length()+" bytes (last 8 MiB analysed)\n"+
            summarizeText(readTail(log,8*1024*1024));
    }
    static String deviceSummary(Context c) {
        String renderer;
        try { renderer=BuiltinDrivers.renderer(); }
        catch(Throwable e){renderer="EGL query failed: "+e;}
        String soc=Build.VERSION.SDK_INT>=31&&Build.SOC_MODEL!=null?Build.SOC_MODEL:"unknown";
        return "Model: "+Build.MANUFACTURER+" "+Build.MODEL+"\n"+
            "SoC: "+soc+"\n"+
            "Android: "+Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")\n"+
            "ABIs: "+Arrays.toString(Build.SUPPORTED_ABIS)+"\n"+
            "EGL renderer: "+(renderer.isEmpty()?"unknown":renderer)+"\n";
    }

    static String buildReport(Context c,String driverTests) {
        SharedPreferences p=PortSettings.prefs(c);
        StringBuilder out=new StringBuilder();
        out.append("THE LAST OF US REMASTERED ANDROID / HEX REPORT\n");
        out.append("Generated: ").append(new Date()).append("\n\nDEVICE\n");
        out.append(deviceSummary(c));
        out.append("\nACTIVE SETTINGS\n");
        out.append("Driver: ").append(p.getString("driver_name","System Vulkan driver")).append('\n');
        out.append("Resolution: ").append(p.getString("resolution","720")).append('\n');
        out.append("FPS limit: ").append(PortSettings.fpsLimit(c)).append('\n');
        out.append("VSync: ").append(p.getBoolean("vsync",true)).append('\n');
        out.append("Draw preparation workers: ").append(p.getString("draw_prep_workers","0")).append('\n');
        out.append("\nVULKAN DRIVER TESTS\n").append(driverTests);
        out.append("\nLAST GAME LOG ANALYSIS\n").append(analyzeGameLog(c));
        return out.toString();
    }

    static File saveLatest(Context c,String report) throws IOException {
        File dir=directory(c);
        File staged=new File(dir,"latest-report.txt.tmp");
        try(FileOutputStream out=new FileOutputStream(staged)) {
            out.write(report.getBytes(StandardCharsets.UTF_8));out.getFD().sync();
        }
        File latest=new File(dir,"latest-report.txt");
        if(latest.exists()&&!latest.delete())throw new IOException("Cannot replace latest HEX report");
        if(!staged.renameTo(latest))throw new IOException("Cannot publish HEX report");
        return latest;
    }
}

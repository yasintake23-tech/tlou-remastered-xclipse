package ro.cobrabm.fexdroid;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Durable launch breadcrumbs exist before JNI, AHB or the FEX child is started. */
final class RunLog {
    static File launch(Context c) { return new File(c.getFilesDir(),"launch.log"); }
    static synchronized void begin(Context c) {
        File current=launch(c), previous=new File(c.getFilesDir(),"launch-previous.log");
        if(current.exists()) { previous.delete(); current.renameTo(previous); }
        write(c,"JOGAR • "+new Date()+" • Android "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT+" • "+Build.MANUFACTURER+" "+Build.MODEL);
    }
    static synchronized void write(Context c,String message) {
        try(FileOutputStream out=new FileOutputStream(launch(c),true)) {
            out.write((new Date()+" ["+Thread.currentThread().getName()+"] "+message+"\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch(IOException e) { android.util.Log.e("TLOU","Cannot write launch log",e); }
    }
    static void failure(Context c,String label,Throwable error) {
        StringWriter trace=new StringWriter(); error.printStackTrace(new PrintWriter(trace));
        write(c,label+"\n"+trace);
    }
    static File runtime(Context c) { return new File(c.getFilesDir(),"tlou/runtime.log"); }
    static String tail(File file,int bytes) {
        if(!file.isFile())return "Ainda não criado.";
        try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
            in.seek(Math.max(0,in.length()-bytes)); byte[] data=new byte[(int)(in.length()-in.getFilePointer())];in.readFully(data);return new String(data,StandardCharsets.UTF_8);
        }catch(IOException e){return e.toString();}
    }
    static String summary(Context c) {
        return "INICIALIZAÇÃO\n"+tail(launch(c),10000)+"\n\nRUNTIME\n"+tail(runtime(c),10000)+
            "\n\nINSTALAÇÃO / PREPARAÇÃO\n"+tail(new File(c.getFilesDir(),"installation.log"),5000);
    }
    static void export(Context c,OutputStream output) throws IOException {
        try(ZipOutputStream zip=new ZipOutputStream(output)) {
            for(String path:new String[]{"launch.log","launch-previous.log","tlou/runtime.log","tlou/runtime-previous.log","installation.log","tlou-prepared/device-ready.json","tlou/perf-cs-22e6e19e.bin","tlou/perf-cs-61d67259.bin"}) {
                File file=new File(c.getFilesDir(),path);if(!file.isFile())continue;
                zip.putNextEntry(new ZipEntry(path));
                try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
                    in.seek(Math.max(0,in.length()-4L*1024*1024));byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)zip.write(bytes,0,n);
                }
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("android-process-exits.txt"));
            try {
                ActivityManager manager=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
                if(manager!=null)for(ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(c.getPackageName(),0,5)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(new Date(exit.getTimestamp())).append(" process=").append(exit.getProcessName())
                      .append(" reason=").append(exit.getReason()).append(" status=").append(exit.getStatus())
                      .append(" description=").append(exit.getDescription());
                    if (Build.VERSION.SDK_INT >= 30) {
                        sb.append(" pss=").append(exit.getPss() >> 20).append("MB")
                          .append(" rss=").append(exit.getRss() >> 20).append("MB");
                    }
                    sb.append("\n");
                    zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch(RuntimeException e) { zip.write(e.toString().getBytes(StandardCharsets.UTF_8)); }
            zip.closeEntry();
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    ActivityManager manager=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
                    if(manager!=null)for(ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(c.getPackageName(),0,5)) {
                        InputStream trace = exit.getTraceInputStream();
                        if (trace != null) {
                            try {
                                zip.putNextEntry(new ZipEntry("tombstone-exit-" + exit.getTimestamp() + ".txt"));
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = trace.read(buf)) != -1) zip.write(buf, 0, len);
                                zip.closeEntry();
                            } finally { trace.close(); }
                        }
                    }
                } catch(Exception ignored) {}
            }
        }
    }
}

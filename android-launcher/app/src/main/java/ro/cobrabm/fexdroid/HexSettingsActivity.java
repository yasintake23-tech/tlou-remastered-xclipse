package ro.cobrabm.fexdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pre-game diagnostics, Vulkan driver tests and stable profiles. Never launches the game. */
public final class HexSettingsActivity extends Activity {
    private static final int BG=0xff101613,PANEL=0xff1b2520,TEXT=0xffe7e9df,MUTED=0xffa6b0a4,ACCENT=0xffc2cf96;
    private static final int PICK_DRIVER=781,EXPORT_REPORT=782;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<Button> buttons=new ArrayList<>();
    private SharedPreferences prefs;
    private LinearLayout root;
    private ProgressBar progress;
    private TextView status,device,driver;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=PortSettings.prefs(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        build();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String tr(String s){return TlouLanguage.t(this,s);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(tr(s));t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(7));return t;}
    private GradientDrawable background(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}
    private LinearLayout card(String heading) {
        LinearLayout c=column();c.setPadding(dp(16),dp(12),dp(16),dp(14));c.setBackground(background(PANEL));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);root.addView(c,lp);
        TextView h=text(heading,12,ACCENT);h.setTypeface(null,Typeface.BOLD);h.setLetterSpacing(.1f);c.addView(h);return c;
    }
    private void add(LinearLayout parent,String label,boolean primary,Runnable action) {
        Button b=new Button(this);b.setText(tr(label));b.setAllCaps(false);b.setTextSize(14);
        b.setTextColor(primary?BG:TEXT);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?ACCENT:0xff303c33));
        b.setMinHeight(dp(46));b.setOnClickListener(v->{if(!busy)action.run();});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.topMargin=dp(7);parent.addView(b,lp);buttons.add(b);
    }
    private void note(LinearLayout parent,String value){parent.addView(text(value,12,MUTED));}
    private void choice(LinearLayout parent,String key,String label,String[] labels,String[] values,String fallback) {
        parent.addView(text(label,13,ACCENT));
        Spinner spinner=new Spinner(this,Spinner.MODE_DROPDOWN);
        String[] names=new String[labels.length];for(int i=0;i<labels.length;i++)names[i]=tr(labels[i]);
        spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
        String current=prefs.getString(key,fallback);int selected=0;
        for(int i=0;i<values.length;i++)if(values[i].equals(current))selected=i;
        spinner.setSelection(selected);spinner.setTag(selected);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int index,long id){
                if((Integer)spinner.getTag()==index)return;
                spinner.setTag(index);prefs.edit().putString(key,values[index]).apply();
                refreshSummary();
            }
        });
        parent.addView(spinner,new LinearLayout.LayoutParams(-1,dp(46)));
    }
    private void toggle(LinearLayout parent,String key,String label,boolean fallback) {
        Switch sw=new Switch(this);sw.setText(tr(label));sw.setTextSize(14);sw.setTextColor(TEXT);
        sw.setChecked(prefs.getBoolean(key,fallback));
        sw.setOnCheckedChangeListener((v,checked)->prefs.edit().putBoolean(key,checked).apply());
        parent.addView(sw);
    }
    private void build() {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        root=column();root.setPadding(dp(18),dp(18),dp(18),dp(24));scroll.addView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("HEX AYARLARI",24,TEXT);title.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(text("v0.2",12,ACCENT));root.addView(header);
        note(root,"Testler oyunu başlatmadan ayrı bir süreçte çalışır. Oyun dosyaları ve kayıtlar değiştirilmez.");

        LinearLayout deviceCard=card("CİHAZ VE VULKAN SÜRÜCÜSÜ");
        device=text("",12,TEXT);deviceCard.addView(device);
        driver=text("",12,MUTED);deviceCard.addView(driver);
        add(deviceCard,"Donanım bilgilerini yenile",false,this::refreshSummary);
        add(deviceCard,"Sistem Vulkan sürücüsünü test et",false,()->runTest(true));
        add(deviceCard,"Seçili Vulkan sürücüsünü test et",false,()->runTest(false));
        add(deviceCard,"Sistem + seçili sürücüleri test et",true,this::runBoth);
        add(deviceCard,"Driver içe aktar (ZIP / SO)",false,this::pickDriver);
        add(deviceCard,"Sürücü seç",false,this::selectDriver);

        LinearLayout settings=card("OYUN AYARLARI · SONRAKİ AÇILIŞTA");
        note(settings,"Değişiklikler yalnızca oyun başlatıldığında uygulanır. Varsayılanlar kararlılığı önceleyerek seçilmiştir.");
        choice(settings,"resolution","Dahili çözünürlük",
            new String[]{"720p (başlangıç önerisi)","540p","360p","Orijinal 1080p"},
            new String[]{"720","540","360","original"},"720");
        choice(settings,"fps_limit","FPS sınırı",new String[]{"30 FPS","60 FPS"},new String[]{"30","60"},"60");
        choice(settings,"draw_prep_workers","Çizim hazırlama işçileri",
            new String[]{"Kapalı (kararlı)","1 işçi (deneysel A/B)"},
            new String[]{"0","1"},"0");
        toggle(settings,"vsync","VSync",true);
        for(String effect:PortSettings.EFFECTS) {
            String label;
            switch(effect){
            case "ssao":label="SSAO efektini devre dışı bırak";break;
            case "motion_blur":label="Hareket bulanıklığını devre dışı bırak";break;
            case "depth_of_field":label="Alan derinliğini devre dışı bırak";break;
            case "bloom":label="Bloom efektini devre dışı bırak";break;
            case "sunlight_shadows":label="Güneş gölgelerini devre dışı bırak";break;
            default:label="Subsurface efektini devre dışı bırak";break;
            }
            toggle(settings,effect,label,true);
        }
        add(settings,"Kararlı profili uygula",true,()->{
            prefs.edit().putString("draw_prep_workers","0").putBoolean("vsync",true)
                .putBoolean("gpu_turbo",false).putBoolean("subsurface",true).apply();
            refreshSummary();
            new AlertDialog.Builder(this).setMessage(tr("Kararlı profil kaydedildi. Çözünürlük ve diğer görsel tercihler korunmuştur."))
                .setPositiveButton("OK",null).show();
        });
        add(settings,"Orijinal görsel efektleri",false,()->new AlertDialog.Builder(this)
            .setTitle(tr("Orijinal görsel efektleri"))
            .setMessage(tr("Tüm efekt kapatma yamalarını devre dışı bırakır. Görüntü kalitesi artabilir ancak GPU yükü de artabilir."))
            .setNegativeButton(tr("İptal"),null).setPositiveButton(tr("Uygula"),(d,w)->{
                SharedPreferences.Editor e=prefs.edit();for(String key:PortSettings.EFFECTS)e.putBoolean(key,false);
                e.apply();refreshSummary();
            }).show());

        LinearLayout reports=card("KAPSAMLI TEST RAPORU");
        note(reports,"Vulkan API, GPU kimliği, BC doku biçimleri, ASTC/RGBA alternatifleri ve gerçek GPU aktarım testi rapora eklenir.");
        note(reports,"Son oyun logundaki BC hataları, pipeline uyarıları ve FPS ölçümleri de analiz edilir.");
        add(reports,"Son oyun loglarını analiz et",false,this::buildLogOnly);
        add(reports,"Son HEX raporunu görüntüle",false,this::showLatest);
        add(reports,"HEX raporunu dışa aktar",true,this::exportReport);

        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);progress.setVisibility(View.GONE);root.addView(progress);
        status=text("",12,MUTED);status.setVisibility(View.GONE);root.addView(status);
        setContentView(scroll);
        refreshSummary();
    }
    private void refreshSummary() {
        if(device==null)return;
        device.setText(HexDiagnostics.deviceSummary(this));
        driver.setText("Driver: "+prefs.getString("driver_name","System Vulkan driver")+
            "\nPath: "+(prefs.getString("driver_path","").isEmpty()?"System loader":"Imported private library"));
    }
    private void setBusy(boolean value,String message) {
        busy=value;
        for(Button b:buttons)b.setEnabled(!value);
        progress.setVisibility(value?View.VISIBLE:View.GONE);
        status.setText(tr(message));status.setVisibility(View.VISIBLE);
    }
    private interface Job {String run() throws Exception;}
    private void runJob(String message,Job job) {
        if(busy)return;setBusy(true,message);
        worker.execute(()->{
            try {
                String detail=job.run();
                String report=HexDiagnostics.buildReport(this,detail);
                HexDiagnostics.saveLatest(this,report);
                runOnUiThread(()->{if(isDestroyed())return;setBusy(false,"Test tamamlandı. HEX raporu hazır.");refreshSummary();showReport(report);});
            } catch(Exception e) {
                RunLog.failure(this,"HEX diagnostics failed",e);
                runOnUiThread(()->{if(isDestroyed())return;setBusy(false,"Test tamamlanamadı: "+e.getMessage());});
            }
        });
    }
    private void runTest(boolean system) {
        runJob(system?"Sistem Vulkan sürücüsü sınanıyor...":"Seçili Vulkan sürücüsü sınanıyor...",
            ()->HexDiagnostics.runVulkanTest(this,system));
    }
    private void runBoth() {
        runJob("Sistem ve seçili sürücüler ayrı süreçlerde sınanıyor...",()->{
            StringBuilder b=new StringBuilder(HexDiagnostics.runVulkanTest(this,true));
            String selected=prefs.getString("driver_path","");
            if(!selected.isEmpty())b.append(HexDiagnostics.runVulkanTest(this,false));
            else b.append("\nSelected driver is the system loader; duplicate test skipped.\n");
            return b.toString();
        });
    }
    private void buildLogOnly() {
        runJob("Son oyun logu analiz ediliyor...",()->"No Vulkan driver test was requested in this report.\n");
    }
    private void showReport(String value) {
        ScrollView scroll=new ScrollView(this);TextView t=new TextView(this);
        t.setText(value);t.setTextIsSelectable(true);t.setTextSize(12);t.setPadding(dp(16),dp(12),dp(16),dp(12));scroll.addView(t);
        new AlertDialog.Builder(this).setTitle("HEX REPORT").setView(scroll)
            .setPositiveButton(tr("Kapat"),null).show();
    }
    private void showLatest() {
        worker.execute(()->{
            File latest=new File(getFilesDir(),"tlou/hex/latest-report.txt");
            String value=latest.isFile()?readFile(latest):HexDiagnostics.buildReport(this,"No Vulkan test has run yet.\n");
            runOnUiThread(()->showReport(value));
        });
    }
    private static String readFile(File file) {
        try{return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}
        catch(Exception e){return e.toString();}
    }
    private void exportReport() {
        File latest=new File(getFilesDir(),"tlou/hex/latest-report.txt");
        if(!latest.isFile()){buildLogOnly();return;}
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("text/plain");i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_TITLE,"TLOU-HEX-Report.txt");startActivityForResult(i,EXPORT_REPORT);
    }
    private String displayName(Uri uri) {
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return "Imported Vulkan driver";
    }
    private void pickDriver() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,PICK_DRIVER);
    }
    private void importDriver(Uri uri) throws Exception {
        String name=displayName(uri);File root=new File(getFilesDir(),"drivers");
        try(InputStream raw=getContentResolver().openInputStream(uri)) {
            if(raw==null)throw new IOException("Could not open driver");
            BufferedInputStream in=new BufferedInputStream(raw);in.mark(4);
            int a=in.read(),b=in.read();in.reset();
            if(a=='P'&&b=='K') {
                DriverArchive.Installed installed=DriverArchive.install(root,in,Build.VERSION.SDK_INT,file->{
                    JSONObject j=new JSONObject(readFile(file));
                    return new DriverArchive.Metadata(j.getString("name"),j.getString("libraryName"),j.getInt("minApi"));
                });
                prefs.edit().putString("driver_path",installed.library.getAbsolutePath())
                    .putString("driver_name",installed.name).apply();
            } else {
                File rawDir=new File(root,"raw");
                if(!rawDir.isDirectory()&&!rawDir.mkdirs())throw new IOException("Cannot create raw driver directory");
                File staged=File.createTempFile("hex-driver-",".so",rawDir);
                try {
                    try(OutputStream out=new FileOutputStream(staged)) {
                        byte[] buffer=new byte[65536];int n;long total=0;
                        while((n=in.read(buffer))!=-1) {
                            total+=n;if(total>256L*1024*1024)throw new IOException("Driver file too large");
                            out.write(buffer,0,n);
                        }
                    }
                    DriverArchive.validateLibrary(staged);
                    Files.write(new File(staged.getPath()+".name").toPath(),name.getBytes(StandardCharsets.UTF_8));
                    prefs.edit().putString("driver_path",staged.getAbsolutePath()).putString("driver_name",name).apply();
                } catch(Exception e) { Files.deleteIfExists(staged.toPath());throw e; }
            }
        }
    }
    private void selectDriver() {
        List<String> names=new ArrayList<>(),paths=new ArrayList<>();
        names.add("System Vulkan driver");paths.add("");
        File base=new File(getFilesDir(),"drivers");File[] children=base.listFiles();
        if(children!=null)for(File child:children)try {
            if(child.getName().equals("raw")) {
                File[] raw=child.listFiles((dir,n)->n.endsWith(".so"));
                if(raw!=null)for(File f:raw) {
                    File side=new File(f.getPath()+".name");
                    names.add(side.isFile()?readFile(side):f.getName());paths.add(f.getAbsolutePath());
                }
            } else if(!child.getName().startsWith(".")) {
                DriverArchive.Installed d=DriverArchive.read(base,child.getName(),Build.VERSION.SDK_INT);
                names.add(d.name);paths.add(d.library.getAbsolutePath());
            }
        }catch(Exception ignored){}
        int selected=Math.max(0,paths.indexOf(prefs.getString("driver_path","")));
        new AlertDialog.Builder(this).setTitle(tr("Vulkan driver"))
            .setSingleChoiceItems(names.toArray(new String[0]),selected,(dialog,index)->{
                prefs.edit().putString("driver_path",paths.get(index)).putString("driver_name",names.get(index)).apply();
                dialog.dismiss();refreshSummary();
            }).setNegativeButton(tr("İptal"),null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try {getContentResolver().takePersistableUriPermission(uri,data.getFlags()&
            (Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));}catch(Exception ignored){}
        if(request==PICK_DRIVER) {
            if(busy)return;setBusy(true,"Vulkan sürücüsü içe aktarılıyor...");
            worker.execute(()->{
                try {
                    importDriver(uri);
                    runOnUiThread(()->{if(isDestroyed())return;setBusy(false,"Sürücü içe aktarıldı ve seçildi.");refreshSummary();});
                } catch(Exception e) {
                    RunLog.failure(this,"HEX driver import failed",e);
                    runOnUiThread(()->{if(!isDestroyed())setBusy(false,"İçe aktarma başarısız: "+e.getMessage());});
                }
            });
        } else if(request==EXPORT_REPORT) {
            worker.execute(()->{
                try(OutputStream out=getContentResolver().openOutputStream(uri,"w")) {
                    if(out==null)throw new IOException("Cannot open report destination");
                    File latest=new File(getFilesDir(),"tlou/hex/latest-report.txt");
                    String value=latest.isFile()?readFile(latest):"No HEX report available.";
                    out.write(value.getBytes(StandardCharsets.UTF_8));
                    runOnUiThread(()->{status.setText(tr("HEX raporu dışa aktarıldı."));status.setVisibility(View.VISIBLE);});
                } catch(Exception e){runOnUiThread(()->new AlertDialog.Builder(this).setMessage(e.toString()).setPositiveButton("OK",null).show());}
            });
        }
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}

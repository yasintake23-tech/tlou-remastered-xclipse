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
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors;

/** TLOU launcher: PKG or extracted game -> automatic on-device preparation. */
public final class MainActivity extends Activity {
    private static final int PICK_GAME=501, PICK_DRIVER=502, PICK_PKG=503, PICK_DEST=504, SAVE_LOGS=505, IMPORT_SAVES=506, EXPORT_SAVES=507;
    private static final int BACKGROUND=0xff101613, PANEL=0xff1b2520, TEXT=0xffe7e9df, MUTED=0xffa6b0a4, ACCENT=0xffc2cf96;
    private SharedPreferences prefs;
    private TextView gameInfo, driverInfo, status, permission;
    private ProgressBar progress;
    private final List<Button> buttons=new ArrayList<>();
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    private boolean busy;
    private String currentTab="home";
    private Runnable afterPermission;
    private Uri pendingPkg;
    private InstallTools tools;
    private boolean directLaunchConsumed;
    private boolean firstRunDialogShown;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs=getSharedPreferences("tlou",MODE_PRIVATE);
        PortSettings.retireTestPreferences(this);
        // Log 32 actually ran at 720p with six effect-disabling patches.
        // Only set these on a fresh install; never overwrite earlier choices.
        SharedPreferences.Editor initial=prefs.edit();
        if(!prefs.contains("resolution"))initial.putString("resolution","720");
        for(String effect:PortSettings.EFFECTS)
            if(!prefs.contains(effect))initial.putBoolean(effect,true);
        initial.apply();
        // Keep paths chosen in the first experimental APK.
        if (!prefs.contains("game_path")) prefs.edit().putString("game_path",getPreferences(0).getString("prepared_path","")).apply();
        if (saved!=null && saved.getString("pending_pkg")!=null) pendingPkg=Uri.parse(saved.getString("pending_pkg"));
        tools=new InstallTools(this);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        buildHome();
        // Storage authorization is the first onboarding interaction, before
        // game selection and before any optional driver setup.
        if(!prefs.getBoolean("onboarding_seen",false)) {
            if(!access())withAccess(this::showFirstRunSetup);
            else showFirstRunSetup();
        } else scheduleDirectLaunch();
        // Optional packaged driver installation runs off the main/UI thread.
        worker.execute(()->{
            try { BuiltinDrivers.ensureDefault(this); }
            catch(Exception e) { android.util.Log.w("TLOU", "Bundled Vulkan driver auto-select unavailable", e); }
            runOnUiThread(()->{if(!isDestroyed())refresh();});
        });
    }
    private void showFirstRunSetup() {
        if(firstRunDialogShown || isFinishing())return;
        firstRunDialogShown=true;
        GameSetupDialog.show(this,selection->{
            prefs.edit().putBoolean("onboarding_seen",true).apply();
            if(selection==0)pickFile(PICK_PKG);
            else if(selection==1)pickFolder(PICK_GAME);
            else scheduleDirectLaunch();
        });
    }

    private void scheduleDirectLaunch() {
        if(directLaunchConsumed || !prefs.getBoolean("launch_direct",false) ||
            prefs.getString("game_path","").isEmpty())return;
        directLaunchConsumed=true;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(
            ()->withAccess(()->prepareSelected(true)));
    }
    private void selectTab(String tab) {
        if(busy)return;
        currentTab=tab;buildHome();
    }
    private void addTab(LinearLayout tabs,String title,String tab) {
        TextView label=text(title,12,currentTab.equals(tab)?0xffe9efd9:MUTED);
        label.setGravity(Gravity.CENTER);label.setTypeface(null,
            currentTab.equals(tab)?Typeface.BOLD:Typeface.NORMAL);
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(currentTab.equals(tab)?0xff3e4e36:0xff1b2520);
        shape.setCornerRadius(dp(8));label.setBackground(shape);
        label.setOnClickListener(v->selectTab(tab));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(45),1);
        lp.setMargins(dp(2),0,dp(2),0);tabs.addView(label,lp);
    }
    private void choice(LinearLayout parent,String key,String heading,
                        String[] names,String[] values,String fallback) {
        parent.addView(text(heading,13,ACCENT));
        Spinner spinner=new Spinner(this,Spinner.MODE_DROPDOWN);
        String[] translated=new String[names.length];
        for(int i=0;i<names.length;i++)translated[i]=TlouLanguage.t(this,names[i]);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,translated);
        spinner.setAdapter(adapter);
        String value=prefs.getString(key,fallback);int initial=0;
        for(int i=0;i<values.length;i++)if(values[i].equals(value))initial=i;
        spinner.setSelection(initial);spinner.setTag(initial);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int index,long id){
                if((Integer)spinner.getTag()==index)return;
                spinner.setTag(index);prefs.edit().putString(key,values[index]).apply();refresh();
            }
        });parent.addView(spinner,new LinearLayout.LayoutParams(-1,dp(46)));
    }
    private void toggleSetting(LinearLayout parent,String key,String label,boolean fallback) {
        Switch sw=new Switch(this);sw.setText(TlouLanguage.t(this,label));sw.setTextSize(14);
        sw.setTextColor(TEXT);sw.setPadding(0,dp(8),0,dp(8));
        sw.setChecked(prefs.getBoolean(key,fallback));
        sw.setOnCheckedChangeListener((v,checked)->prefs.edit().putBoolean(key,checked).apply());
        parent.addView(sw);
    }
    private void languageChoice(LinearLayout parent) {
        parent.addView(text("Idioma do aplicativo",13,ACCENT));
        Spinner spinner=new Spinner(this,Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,TlouLanguage.options(this));
        spinner.setAdapter(adapter);
        int initial=TlouLanguage.selectedOption(this);spinner.setSelection(initial);spinner.setTag(initial);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int index,long id){
                if((Integer)spinner.getTag()==index)return;
                spinner.setTag(index);prefs.edit().putString("ui_language",TlouLanguage.selectedCode(index)).apply();
                buildHome(); // app language is immediate and never changes guest/game language
            }
        });parent.addView(spinner,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    private void screenChoice(LinearLayout parent) {
        parent.addView(text("Tela",13,ACCENT));
        Spinner spinner=new Spinner(this,Spinner.MODE_DROPDOWN);
        String[] labels={
            TlouLanguage.t(this,"Ajustar à tela (proporção original)"),
            TlouLanguage.t(this,"Preencher tela (sem barras)")};
        spinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,labels));
        int initial=prefs.getBoolean("fullscreen",false)?1:0;
        spinner.setSelection(initial);spinner.setTag(initial);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int index,long id){
                if((Integer)spinner.getTag()==index)return;
                spinner.setTag(index);prefs.edit().putBoolean("fullscreen",index==1).apply();
            }
        });parent.addView(spinner,new LinearLayout.LayoutParams(-1,dp(46)));
    }
    private void buildHome() {
        gameInfo=null;driverInfo=null;permission=null;
        buttons.clear();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout root=column();root.setPadding(dp(18),dp(18),dp(18),dp(22));
        scroll.addView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text("DAEDALUS  /  ANDROID",12,ACCENT);brand.setLetterSpacing(.14f);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView version=text("v0.2 HEX",13,ACCENT);
        version.setGravity(Gravity.RIGHT);header.addView(version,new LinearLayout.LayoutParams(dp(60),-2));
        root.addView(header);
        if(currentTab.equals("home")) {
            // The hero is bundled with the APK: shown immediately and offline,
            // like the other Daedalus game launchers.
            ImageView hero=new ImageView(this);
            hero.setImageResource(R.drawable.tlou_hero);
            hero.setContentDescription("The Last of Us Remastered");
            hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
            hero.setBackground(background(PANEL));
            hero.setClipToOutline(true);
            LinearLayout.LayoutParams art=new LinearLayout.LayoutParams(-1,dp(210));
            art.topMargin=dp(8);art.bottomMargin=dp(13);
            root.addView(hero,art);
        } else {
            TextView title=text("THE LAST OF US",34,TEXT);
            title.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));root.addView(title);
            TextView subtitle=text("R E M A S T E R E D",12,ACCENT);
            subtitle.setPadding(0,0,0,dp(14));root.addView(subtitle);
        }

        LinearLayout tabs=new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        addTab(tabs,"Início","home");addTab(tabs,"Patches","patches");
        addTab(tabs,"Ajustes","settings");addTab(tabs,"Sobre","about");
        LinearLayout.LayoutParams tabLP=new LinearLayout.LayoutParams(-1,dp(45));
        tabLP.bottomMargin=dp(18);root.addView(tabs,tabLP);

        if(currentTab.equals("home")) {
            LinearLayout game=card(root,"SEU JOGO");
            gameInfo=text("",13,TEXT);gameInfo.setMaxLines(3);game.addView(gameInfo);
            add(game,"JOGAR",true,()->withAccess(()->prepareSelected(true)));
            add(game,"HEX Ayarları",false,()->startActivity(new Intent(this,HexSettingsActivity.class)));
            game.addView(text("PKG ou pasta extraída. Preparação automática no celular.",12,MUTED));
            add(game,"Escolher pasta do jogo",false,()->withAccess(()->pickFolder(PICK_GAME)));
            add(game,"Instalar por PKG",false,()->withAccess(()->pickFile(PICK_PKG)));
            TextView manual=text("Informar caminho da pasta",12,ACCENT);
            manual.setOnClickListener(v->{if(!busy)manualPath();});game.addView(manual);
            toggleSetting(game,"launch_direct","Entrar direto no jogo",false);
            add(game,"Sair do aplicativo",false,this::finishAffinity);
        } else if(currentTab.equals("patches")) {
            LinearLayout box=card(root,"PATCHES");
            box.addView(text("Patches validados para o eboot reconhecido. Reinicie para aplicar.",12,MUTED));
            choice(box,"resolution","Resolução interna",
                new String[]{"Original (1080p)","1280 × 720","960 × 540","640 × 360"},
                new String[]{"original","720","540","360"},"original");
            toggleSetting(box,"ssao","Desativar SSAO",true);
            toggleSetting(box,"motion_blur","Desativar desfoque de movimento",true);
            toggleSetting(box,"depth_of_field","Desativar profundidade de campo",true);
            toggleSetting(box,"bloom","Desativar bloom",true);
            toggleSetting(box,"sunlight_shadows","Desativar sombras solares",true);
            toggleSetting(box,"subsurface","Desativar subsurface",true);
            add(box,"Restaurar patches e resolução",false,()->{
                SharedPreferences.Editor edit=prefs.edit().putString("resolution","original");
                for(String key:PortSettings.EFFECTS)edit.putBoolean(key,false);
                edit.apply();buildHome();
            });
        } else if(currentTab.equals("settings")) {
            LinearLayout video=card(root,"VÍDEO E TELA");
            screenChoice(video);
            choice(video,"fps_limit","Limite de FPS",
                new String[]{"30 FPS","60 FPS"},
                new String[]{"30","60"},"60");
            toggleSetting(video,"vsync","VSync",true);
            choice(video,"render_path","Método de resolução (requer reiniciar o jogo)",
                new String[]{"Unificado: jogo, interface e mira na resolução escolhida",
                             "Compatibilidade: interface 1080p (proxies Vulkan)"},
                new String[]{"guest","proxy"},"guest");
            video.addView(text("O modo Unificado mantém cena e interface na mesma resolução; a decodificação dos vídeos não muda. "
                +"Compatibilidade mantém a composição da interface em 1080p.",12,MUTED));
            video.addView(text("Sincronização da geometria: Completo (único modo). "
                +"Correção de deformações mantida sem opções que não melhoraram o FPS.",12,MUTED));
            LinearLayout drivers=card(root,"DRIVER VULKAN");
            driverInfo=text("",12,MUTED);drivers.addView(driverInfo);
            add(drivers,"Selecionar driver Vulkan",false,this::selectDriver);
            add(drivers,"Importar driver ZIP ou SO",false,()->pickFile(PICK_DRIVER));
            toggleSetting(drivers,"gpu_turbo","Adreno Turbo (sem root)",false);
            drivers.addView(text("Política KGSL de máximo desempenho; a proteção térmica continua ativa. Pode não funcionar em todos os dispositivos.",12,MUTED));
            LinearLayout app=card(root,"IDIOMA E INTERFACE");
            languageChoice(app);
            app.addView(text("O idioma do jogo é escolhido no próprio The Last of Us.",12,MUTED));
            LinearLayout controls=card(root,"CONTROLES E HUD");
            toggleSetting(controls,"hud_visible","Mostrar FPS",true);
            choice(controls,"hud_mode","Estilo do HUD",
                new String[]{"Completa (FPS / GPU / CPU)","FPS + tempo de frame","Apenas FPS"},
                new String[]{"full","compact","minimal"},"full");
            choice(controls,"hud_corner","Posição do HUD",
                new String[]{"Superior esquerdo","Superior direito","Inferior esquerdo",
                    "Inferior direito","Superior central","Centro da tela",
                    "Inferior central","Central esquerdo","Central direito",
                    "Personalizada (arrastar)"},
                new String[]{"0","1","2","3","4","5","6","7","8","-1"},"0");
            toggleSetting(controls,"hud_gpu","Uso da GPU",true);
            toggleSetting(controls,"hud_cpu","Uso da CPU (jogo)",true);
            toggleSetting(controls,"touch_visible","Controles na tela",true);
            choice(controls,"control_color","Cor dos controles",
                new String[]{"Verde oliva","Âmbar","Branco","Vermelho","Azul"},
                new String[]{"olive","amber","white","red","blue"},"olive");
            controls.addView(text("Editar posição dos botões: deslize da borda esquerda para abrir o menu.",12,MUTED));
            toggleSetting(app,"launch_direct","Entrar direto no jogo",false);
            LinearLayout data=card(root,"SAVES");
            add(data,"Exportar saves ZIP",false,this::exportSaves);
            add(data,"Importar saves ZIP",false,()->pickFile(IMPORT_SAVES));
        } else {
            root.addView(AboutPanel.create(this,this::showLog,this::exportLogs));
        }

        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);progress.setVisibility(View.GONE);root.addView(progress);
        status=text("",12,MUTED);status.setVisibility(View.GONE);root.addView(status);
        permission=text("Permitir acesso aos arquivos",12,ACCENT);
        permission.setPadding(0,dp(10),0,dp(10));
        permission.setOnClickListener(v->withAccess(this::refresh));root.addView(permission);
        setContentView(scroll);refresh();
    }
    private void exportLogs() {
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);save.setType("application/zip");save.addCategory(Intent.CATEGORY_OPENABLE);
        save.putExtra(Intent.EXTRA_TITLE,"TLOU-Android-Logs.zip");startActivityForResult(save,SAVE_LOGS);
    }
    private File saveUser(){return new File(getFilesDir(),"tlou/user");}
    private void exportSaves() {
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);save.setType("application/zip");save.addCategory(Intent.CATEGORY_OPENABLE);
        save.putExtra(Intent.EXTRA_TITLE,"TLOU-Saves-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(new Date())+".zip");
        startActivityForResult(save,EXPORT_SAVES);
    }
    private void openReference(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView text(String message,int size,int color) {
        TextView t=new TextView(this); t.setText(TlouLanguage.t(this,message)); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(5),0,dp(7)); return t;
    }
    private GradientDrawable background(int color) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(12)); return d; }
    private LinearLayout card(LinearLayout root,String title) {
        LinearLayout c=column(); c.setPadding(dp(16),dp(12),dp(16),dp(12)); c.setBackground(background(PANEL));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(14); root.addView(c,lp);
        TextView heading=text(title,11,ACCENT); heading.setLetterSpacing(0.12f); c.addView(heading); return c;
    }
    private void add(LinearLayout parent,String label,boolean primary,Runnable action) {
        Button b=new Button(this); b.setText(TlouLanguage.t(this,label)); b.setTextSize(14); b.setAllCaps(false); b.setMinHeight(dp(48));
        b.setTextColor(primary?BACKGROUND:TEXT); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?ACCENT:0xff303c33));
        b.setOnClickListener(v->{if(!busy)action.run();}); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50)); p.topMargin=dp(8); parent.addView(b,p); buttons.add(b);
    }
    private void alert(String title,String message) { if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle(TlouLanguage.t(this,title)).setMessage(TlouLanguage.t(this,message)).setPositiveButton("OK",null).show(); }
    private void update(String message) {
        runOnUiThread(()->{if(!isDestroyed() && status!=null)
            {status.setText(TlouLanguage.t(this,message));status.setVisibility(View.VISIBLE);}});
    }
    private boolean access() { return Build.VERSION.SDK_INT<30 || Environment.isExternalStorageManager(); }
    private void withAccess(Runnable action) {
        if(access()){action.run();return;}
        afterPermission=action;
        try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName()))); }
        catch(Exception e){startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}
    }
    private void pickFile(int code) { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i,code); }
    private void pickFolder(int code) { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i,code); }
    private void refresh() {
        if(gameInfo==null && driverInfo==null && permission==null)return;
        String folder=prefs.getString("game_path","");
        if(gameInfo!=null)gameInfo.setText(folder.isEmpty()?TlouLanguage.t(this,"Nenhum jogo selecionado"):folder);
        if(driverInfo!=null)driverInfo.setText(PortSettings.summary(this)+"\n"+prefs.getString("driver_name",TlouLanguage.t(this,"Driver do sistema")));
        if(permission!=null)permission.setVisibility(access()?View.GONE:View.VISIBLE);
    }
    private void task(String message, Work work) {
        if(busy)return; busy=true; for(Button b:buttons)b.setEnabled(false); progress.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); update(message);
        worker.execute(()->{try{work.run();}catch(Exception e){RunLog.failure(this,"Operação do launcher",e);update("A operação não foi concluída."); runOnUiThread(()->alert("Não foi possível concluir",e.getMessage()==null?e.toString():e.getMessage()));}
            finally{runOnUiThread(()->{busy=false;if(isDestroyed())return;for(Button b:buttons)b.setEnabled(true);progress.setVisibility(View.GONE);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);refresh();});}});
    }
    private interface Work { void run() throws Exception; }
    private File selected() { return new File(prefs.getString("game_path","")); }
    private void chooseGame(File folder) {
        if(!InstallTools.prepared(folder)&&!new File(folder,"eboot.bin").isFile()) {alert("Pasta do jogo","Escolha a pasta que contém eboot.bin e sce_sys, ou uma pasta já preparada.");return;}
        prefs.edit().putString("game_path",folder.getAbsolutePath()).apply(); refresh(); prepareSelected(false);
    }
    private void prepareSelected(boolean launch) {
        if(busy)return;
        if(launch){RunLog.begin(this);RunLog.write(this,"Jogo selecionado: "+prefs.getString("game_path","")+" • driver="+prefs.getString("driver_name","Sistema"));}
        if(prefs.getString("game_path","").isEmpty()){alert("Selecione o jogo","Instale um PKG ou escolha a pasta do jogo.");return;}
        task("Verificando e preparando jogo…",()->{
            // Finish first-run Adreno 6xx/7xx driver setup before the game
            // captures its launch environment, without overriding user choice.
            try { BuiltinDrivers.ensureDefault(this); }
            catch(Exception e) { android.util.Log.w("TLOU", "Default Vulkan driver unavailable", e); }
            File folder=selected(); File prepared;
            if(InstallTools.prepared(folder)){prepared=folder;tools.applyPatches(prepared,this::update);}
            else prepared=tools.prepare(folder,this::update);
            prefs.edit().putString("prepared_path",prepared.getAbsolutePath()).apply();
            update("Pronto para jogar.");
            if(launch)runOnUiThread(()->{if(!isDestroyed()){try { RunLog.write(this,"Preparação verificada. Abrindo GameActivity: "+prepared); Intent i=new Intent(this,GameActivity.class);i.putExtra("prepared_path",prepared.getAbsolutePath());startActivity(i); } catch(Throwable error) { RunLog.failure(this,"Falha ao abrir GameActivity",error);alert("Inicialização",error.toString()); }}});
        });
    }
    private void manualPath() {
        EditText input=new EditText(this); input.setSingleLine(true); input.setText(prefs.getString("game_path",""));input.setHint("/storage/emulated/0/Jogos/CUSA00552");
        new AlertDialog.Builder(this).setTitle(TlouLanguage.t(this,"Pasta do jogo")).setView(input).setNegativeButton(TlouLanguage.t(this,"Cancelar"),null).setPositiveButton(TlouLanguage.t(this,"Selecionar"),(d,w)->withAccess(()->chooseGame(new File(input.getText().toString().trim())))).show();
    }
    private void installPkg(File pkg,File parent) {
        task("Instalando PKG…",()->{
            File extracted=tools.extract(pkg,parent,this::update);
            // Save extracted folder even if preparation fails; never force another extraction.
            prefs.edit().putString("game_path",extracted.getAbsolutePath()).apply();
            File prepared=tools.prepare(extracted,this::update);
            prefs.edit().putString("prepared_path",prepared.getAbsolutePath()).apply(); update("Jogo instalado e preparado. Toque em JOGAR.");
        });
    }
    private String displayName(Uri uri) {
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}
        return "Driver importado";
    }
    private void importDriver(Uri uri) {
        String name=displayName(uri);
        task("Importando driver…",()->{
            File drivers=new File(getFilesDir(),"drivers");
            try(BufferedInputStream in=new BufferedInputStream(Objects.requireNonNull(getContentResolver().openInputStream(uri)))) {
                in.mark(4); int a=in.read(),b=in.read();in.reset();
                if(a=='P'&&b=='K') {
                    DriverArchive.Installed driver=DriverArchive.install(drivers,in,Build.VERSION.SDK_INT,file->{
                        JSONObject j=new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
                        return new DriverArchive.Metadata(j.getString("name"),j.getString("libraryName"),j.getInt("minApi"));
                    });
                    prefs.edit().putString("driver_path",driver.library.getAbsolutePath()).putString("driver_name",driver.name).apply();
                } else {
                    File raw=new File(drivers,"raw");if(!raw.isDirectory()&&!raw.mkdirs())throw new IOException("Falha ao criar pasta de drivers");
                    File staged=File.createTempFile("driver-",".so",raw);
                    try {
                        try(OutputStream out=new FileOutputStream(staged)){byte[] bytes=new byte[65536];long total=0;int n;while((n=in.read(bytes))!=-1){total+=n;if(total>256L*1024*1024)throw new IOException("Driver grande demais");out.write(bytes,0,n);}}
                        DriverArchive.validateLibrary(staged);
                        Files.write(new File(staged.getPath()+".name").toPath(),name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        prefs.edit().putString("driver_path",staged.getAbsolutePath()).putString("driver_name",name).apply();
                    }catch(Exception e){Files.deleteIfExists(staged.toPath());throw e;}
                }
            }catch(DriverArchive.Rejected e){throw new IOException(e.reason==DriverArchive.Reason.NEWER_ANDROID?"Este driver exige uma versão mais recente do Android":"Pacote inválido. Use um driver Vulkan Android ARM64 com meta.json e biblioteca .so.",e);}
            update("Driver importado e selecionado.");
        });
    }
    private void selectDriver() {
        List<String> names=new ArrayList<>(),paths=new ArrayList<>();names.add(TlouLanguage.t(this,"Driver do sistema"));paths.add("");
        File root=new File(getFilesDir(),"drivers");File[] dirs=root.listFiles();
        if(dirs!=null)for(File d:dirs)try{
            if(d.getName().equals("raw")){
                File[] raw=d.listFiles((dir,name)->name.endsWith(".so"));if(raw!=null)for(File f:raw){File n=new File(f.getPath()+".name");names.add(n.isFile()?new String(Files.readAllBytes(n.toPath()),java.nio.charset.StandardCharsets.UTF_8):f.getName());paths.add(f.getAbsolutePath());}
            }else if(!d.getName().startsWith(".")){DriverArchive.Installed imported=DriverArchive.read(root,d.getName(),Build.VERSION.SDK_INT);names.add(imported.name);paths.add(imported.library.getAbsolutePath());}
        }catch(Exception ignored){}
        String current=prefs.getString("driver_path","");int checked=Math.max(0,paths.indexOf(current));
        new AlertDialog.Builder(this).setTitle("Driver Vulkan").setSingleChoiceItems(names.toArray(new String[0]),checked,(dialog,index)->{
            prefs.edit().putString("driver_path",paths.get(index)).putString("driver_name",names.get(index)).apply();refresh();dialog.dismiss();
        }).setNegativeButton(TlouLanguage.t(this,"Cancelar"),null).show();
    }
    private File local(Uri uri,boolean tree) throws Exception {
        String raw=null;
        if("com.android.externalstorage.documents".equals(uri.getAuthority())){
            String id=tree?DocumentsContract.getTreeDocumentId(uri):DocumentsContract.getDocumentId(uri);int colon=id.indexOf(':');if(colon<1)throw new IOException("Volume inválido");
            String volume=id.substring(0,colon);if(!volume.matches("[A-Za-z0-9_-]+"))throw new IOException("Volume inválido");
            File root=volume.equalsIgnoreCase("primary")?Environment.getExternalStorageDirectory():new File("/storage",volume);
            File result=new File(root,id.substring(colon+1)).getCanonicalFile();if(!result.toPath().startsWith(root.getCanonicalFile().toPath()))throw new IOException("Pasta inválida");return result;
        }
        if(!tree){
            try{String id=DocumentsContract.getDocumentId(uri);if(id.startsWith("raw:"))raw=id.substring(4);}catch(Exception ignored){}
            if(raw==null)try(Cursor c=getContentResolver().query(uri,new String[]{"_data"},null,null,null)){if(c!=null&&c.moveToFirst())raw=c.getString(0);}catch(Exception ignored){}
        }
        if(raw!=null){File f=new File(raw).getCanonicalFile();if(f.toPath().startsWith(new File("/storage").toPath()))return f;}
        throw new IOException("Escolha um arquivo ou pasta local pelo armazenamento interno/USB. Para pastas, você também pode informar o caminho.");
    }
    private void showLog() {
        ScrollView scroll=new ScrollView(this);TextView text=new TextView(this);text.setText(RunLog.summary(this));text.setTextSize(12);text.setTextIsSelectable(true);text.setPadding(dp(16),dp(12),dp(16),dp(12));scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("TLOU — logs").setView(scroll).setPositiveButton(TlouLanguage.t(this,"Fechar"),null).show();
    }
    @Override protected void onResume(){super.onResume();refresh();if(afterPermission!=null){Runnable action=afterPermission;afterPermission=null;if(access())action.run();}
        else if(access()&&!prefs.getBoolean("onboarding_seen",false)&&!firstRunDialogShown)showFirstRunSetup();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(pendingPkg!=null)state.putString("pending_pkg",pendingPkg.toString());}
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);if(result!=RESULT_OK||data==null||data.getData()==null){if(code==PICK_DEST)pendingPkg=null;return;}Uri uri=data.getData();
        try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));}catch(Exception ignored){}
        try{
            if(code==SAVE_LOGS){task("Exportando logs…",()->{java.io.OutputStream out=getContentResolver().openOutputStream(uri,"w");if(out==null)throw new IOException("Não foi possível abrir o destino");RunLog.export(this,out);update("Logs ZIP salvos.");});}
            else if(code==EXPORT_SAVES){task("Exportando saves…",()->{
                try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){
                    if(out==null)throw new IOException("Não foi possível abrir o destino");
                    SaveArchive.exportZip(saveUser(),out);
                }update("Saves exportados com sucesso.");
            });}
            else if(code==IMPORT_SAVES){task("Importando saves…",()->{
                try(InputStream in=getContentResolver().openInputStream(uri)){
                    if(in==null)throw new IOException("Não foi possível abrir o ZIP");
                    File backup=SaveArchive.importZip(saveUser(),in);
                    RunLog.write(this,"Saves importados; backup anterior: "+(backup==null?"sem saves anteriores":backup.getAbsolutePath()));
                    update(backup==null?"Saves importados com sucesso.":"Saves importados. Os anteriores foram preservados em backup.");
                }
            });}
            else if(code==PICK_GAME)chooseGame(local(uri,true));
            else if(code==PICK_DRIVER)importDriver(uri);
            else if(code==PICK_PKG){pendingPkg=uri;new AlertDialog.Builder(this).setTitle(TlouLanguage.t(this,"Destino do jogo")).setMessage(TlouLanguage.t(this,"Escolha onde criar a pasta CUSA00552. O PKG será extraído diretamente para ela; depois o app prepara o jogo automaticamente.")).setPositiveButton(TlouLanguage.t(this,"Escolher destino"),(d,w)->pickFolder(PICK_DEST)).setNegativeButton(TlouLanguage.t(this,"Cancelar"),(d,w)->pendingPkg=null).show();}
            else if(code==PICK_DEST&&pendingPkg!=null){Uri pkg=pendingPkg;pendingPkg=null;installPkg(local(pkg,false),local(uri,true));}
        }catch(Exception e){alert("Seleção",e.getMessage());}
    }
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}

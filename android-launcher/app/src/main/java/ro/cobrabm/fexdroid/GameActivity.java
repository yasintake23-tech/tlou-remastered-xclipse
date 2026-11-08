package ro.cobrabm.fexdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Locale;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loads only the real TLOU ARM64/FEX executable, never a renamed GOW3 game. */
public final class GameActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AtomicBoolean closing=new AtomicBoolean();
    private SurfaceView surface;
    private TouchControllerOverlay touch;
    private PadBridge pad;
    private AudioBridge audio;
    private volatile Process game;
    private File logFile;
    private TextView status;
    private FrameLayout gameLayout;
    private PortMenu playerMenu;
    private boolean editingControls;
    private EdgeMenuGesture edgeMenu;
    private File runtimePidFile;
    private volatile GameChoiceBridge gameChoices;
    private String prepared;
    private volatile boolean started;
    private boolean resumed;
    private final FpsMeter fpsMeter=new FpsMeter();
    private long lastPresentDiagnosticsMs;
    private HudTelemetry hudTelemetry;
    private final GpuTurboSession gpuTurbo=new GpuTurboSession();
    private Boolean lastTurboChoice;

    private static FrameLayout.LayoutParams pos(int w,int h,int gravity) {
        return new FrameLayout.LayoutParams(w,h,gravity);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        RunLog.write(this,"GameActivity.onCreate • início");
        try { initialize(); } catch(Throwable error) {
            RunLog.failure(this,"Falha antes do runtime",error);
            error("Falha na inicialização: "+error+"\nOs detalhes estão em Ver logs, no menu principal.");
        }
    }

    private void initialize() throws Exception {
        File logDir=new File(getFilesDir(),"tlou");
        if(!logDir.isDirectory()&&!logDir.mkdirs())throw new java.io.IOException("Falha ao criar pasta dos logs");
        logFile=RunLog.runtime(this);
        if(logFile.exists()){ File previous=new File(logDir,"runtime-previous.log"); previous.delete(); logFile.renameTo(previous); }
        try(FileOutputStream out=new FileOutputStream(logFile)){out.write("TLOU runtime: aguardando inicialização do processo\n".getBytes(StandardCharsets.UTF_8));}
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prepared=getIntent().getStringExtra("prepared_path");
        if (prepared==null) throw new IllegalStateException("Caminho preparado não informado");
        RunLog.write(this,"Pasta preparada: "+prepared);
        File gamePath=new File(prepared);
        if (!new File(gamePath,"boot-linked.bin").isFile() ||
            !new File(gamePath,"content.bin").isFile()) throw new IllegalStateException("Imagem preparada ausente: "+gamePath);
        File stateDir=new File(getFilesDir(),"tlou");
        if (!stateDir.exists()) stateDir.mkdirs();
        File input=new File(stateDir,"controller.state");
        RunLog.write(this,"Inicializando controle e áudio");
        pad=new PadBridge(input);
        runtimePidFile=new File(stateDir,"runtime.pid");
        hudTelemetry=new HudTelemetry(this,runtimePidFile);
        edgeMenu=new EdgeMenuGesture(getResources().getDisplayMetrics().density);
        audio=new AudioBridge(new File(stateDir,"audio.fifo"));
        logFile=new File(stateDir,"runtime.log");

        FrameLayout layout=new FrameLayout(this);
        layout.setBackgroundColor(0xff000000);
        surface=new SurfaceView(this);
        layout.addView(surface,pos(-1,-1,Gravity.CENTER));
        touch=new TouchControllerOverlay(this,pad,false);
        layout.addView(touch,pos(-1,-1,Gravity.CENTER));
        gameLayout=layout;
        layout.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(r-l!=or-ol || b-t!=ob-ot)placeHud();
        });
        status=new TextView(this);status.setGravity(Gravity.LEFT);
        HudPresentation.style(status,PortSettings.prefs(this));
        status.setText(HudPresentation.text("full",0,Double.NaN,Double.NaN,true,true));
        layout.addView(status,pos(-2,-2,Gravity.TOP|Gravity.LEFT));
        status.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol || b-t!=ob-ot)placeHud();});
        final float[] drag=new float[2];
        status.setOnTouchListener((v,e)->{
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)status.getLayoutParams();
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                // Convert anchor-based placement to absolute TOP|LEFT before dragging.
                lp.gravity=Gravity.TOP|Gravity.LEFT;
                lp.leftMargin=status.getLeft();lp.topMargin=status.getTop();
                lp.rightMargin=0;lp.bottomMargin=0;status.setLayoutParams(lp);
                drag[0]=e.getRawX()-lp.leftMargin;drag[1]=e.getRawY()-lp.topMargin;
                return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                lp.leftMargin=Math.max(dp(4),Math.min(layout.getWidth()-status.getWidth()-dp(4),(int)(e.getRawX()-drag[0])));
                lp.topMargin=Math.max(dp(4),Math.min(layout.getHeight()-status.getHeight()-dp(4),(int)(e.getRawY()-drag[1])));
                status.setLayoutParams(lp);return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP){
                PortSettings.prefs(this).edit().putString("hud_corner","-1")
                    .putFloat("hud_x",lp.leftMargin/(float)Math.max(1,layout.getWidth()-status.getWidth()))
                    .putFloat("hud_y",lp.topMargin/(float)Math.max(1,layout.getHeight()-status.getHeight())).apply();
                v.performClick();return true;
            }
            return true;
        });
        setContentView(layout);
        // InsetsController is available only after a decor exists and is attached.
        layout.post(()->{immersive();applyLiveSettings();});

        try {
            RunLog.write(this,"Carregando JNI e iniciando apresentação AHardwareBuffer");
            String bridge=Gow3Present.start(surface.getHolder(),
                    new File(stateDir,"present.sock").getAbsolutePath(),PortSettings.prefs(this).getBoolean("fullscreen",false));
            if (!bridge.startsWith("bridge running")) throw new IllegalStateException(bridge);
            RunLog.write(this,"Apresentação: "+bridge);
            audio.start();
            started=true;
        } catch (Throwable err) {
            error("Não foi possível iniciar AHardwareBuffer: "+err);
            return;
        }
        Thread starter=new Thread(this::launch,"tlou-fex-launcher");
        starter.start();
        handler.postDelayed(this::refreshFps,500);
    }

    private void immersive() {
        if(isFinishing() || isDestroyed()) return;
        final View decor=getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT>=30) {
            final WindowInsetsController controller=decor.getWindowInsetsController();
            if(controller!=null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void update(String message) {
        handler.post(()->{
            if (status!=null && !closing.get()) status.setContentDescription(message);
        });
    }

    private void error(String message) {
        RunLog.write(this,"ERRO: "+message);
        handler.post(()->{if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle(TlouLanguage.t(this,"TLOU — erro de inicialização"))
            .setMessage(message).setNeutralButton(TlouLanguage.t(this,"Ver logs"),(d,w)->showLog())
            .setPositiveButton(TlouLanguage.t(this,"Voltar ao menu"),(d,w)->stopAndExit()).show();});
    }

    private void refreshFps() {
        if(!started || closing.get())return;
        try {
            if(resumed && game!=null && game.isAlive()) {
                long now=SystemClock.elapsedRealtimeNanos();
                double fps=fpsMeter.sample(Gow3Present.framesIfLoaded(),now);
                if(hudTelemetry!=null && PortSettings.prefs(this).getBoolean("hud_visible",true))hudTelemetry.requestSample(game,now);
                SharedPreferences p=PortSettings.prefs(this);
                String mode=p.getString("hud_mode","full");
                double cpu=hudTelemetry==null?Double.NaN:hudTelemetry.cpuPercent();
                double gpu=hudTelemetry==null?Double.NaN:hudTelemetry.gpuPercent();
                status.setText(HudPresentation.text(mode,fps,gpu,cpu,
                    p.getBoolean("hud_gpu",true),p.getBoolean("hud_cpu",true)));
                long diagnosticsNow=SystemClock.elapsedRealtime();
                if(diagnosticsNow-lastPresentDiagnosticsMs>=5000L) {
                    lastPresentDiagnosticsMs=diagnosticsNow;
                    RunLog.write(this,"Android presentation: "+Gow3Present.diagnostics());
                }
            } else {
                fpsMeter.reset();
                if(hudTelemetry!=null)hudTelemetry.reset();
            }
        } catch(Throwable e) {android.util.Log.w("TLOU", "HUD sampling unavailable",e);}
        handler.postDelayed(this::refreshFps,500);
    }

    private File installBinary() throws Exception {
        File root=new File(getFilesDir(),"tlou/runtime");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("Diretório de runtime indisponível");
        File dst=new File(root,"tlou-probe");
        File tmp=new File(root,"tlou-probe.tmp");
        try(InputStream in=getAssets().open("runtime/gow3-probe");
            FileOutputStream out=new FileOutputStream(tmp)) {
            byte[] buf=new byte[128*1024];
            int n;
            while((n=in.read(buf))!=-1) out.write(buf,0,n);
        }
        if(tmp.length()<8192) throw new IllegalStateException("Runtime TLOU inválido no APK");
        if (!tmp.setExecutable(true,true)) throw new IllegalStateException("Falhou chmod do loader Bionic");
        if (dst.exists()&&!dst.delete()) throw new IllegalStateException("Falhou substituir runtime");
        if(!tmp.renameTo(dst)) throw new IllegalStateException("Falhou instalar loader");
        return dst;
    }

    private void launch() {
        SaveArchive.Guard saveGuard=null;
        try {
            RunLog.write(this,"Android görüntü sunum katmanı bekleniyor...");
            if (!Gow3Present.awaitSurface(12000L)) {
                String diagnostic = Gow3Present.diagnostics();
                RunLog.write(this,"Sunum yüzeyi hazır değil; siyah ekranlı başlatma engellendi: "+diagnostic);
                throw new IllegalStateException("Android görüntü yüzeyi 12 saniye içinde hazırlanamadı. "+diagnostic);
            }
            RunLog.write(this,"Android SurfaceControl hazır: "+Gow3Present.diagnostics());
            RunLog.write(this,"Instalando executável Bionic do APK");
            File bin=installBinary();
            RunLog.write(this,"Loader: "+bin+" • "+bin.length()+" bytes • executável="+bin.canExecute());
            File base=new File(prepared);
            File app0=new File(base,"tlou-view");
            File state=new File(getFilesDir(),"tlou");
            File user=new File(state,"user");
            saveGuard=SaveArchive.lock(user);
            if(closing.get())return;
            gameChoices=new GameChoiceBridge(this,state,()->{if(playerMenu!=null)playerMenu.dismiss();if(editingControls)touch.finishEdit();touch.release();pad.reset();},pad::name);
            gameChoices.start();
            if(closing.get())return;
            if (!user.exists() && !user.mkdirs()) throw new IllegalStateException("Falha no usuário PS4");
            if (!app0.isDirectory()) throw new IllegalStateException("tlou-view ausente, rode o preparador TLOU");
            List<String> args=new ArrayList<>();
            args.add(bin.getAbsolutePath());
            args.add(new File(base,"boot-linked.bin").getAbsolutePath());
            args.add("--low-image");
            args.add("--no-game-hooks");
            // probe.c defaults to a 10-second research watchdog, unsuitable for gameplay.
            args.add("--timeout");
            args.add("0");
            args.add("--content-profile");
            args.add(new File(base,"content.bin").getAbsolutePath());
            args.add("--app0");
            args.add(app0.getAbsolutePath());
            args.add("--user");
            args.add(user.getAbsolutePath());
            File patches=new File(base,"patches.bin");
            if (patches.isFile() && patches.length()>0) {
                args.add("--patches");
                args.add(patches.getAbsolutePath());
            }
            ProcessBuilder builder=new ProcessBuilder(RuntimeProcess.command(args,runtimePidFile));
            Map<String,String> env=builder.environment();
            File home=new File(state,"home"), temp=new File(state,"tmp"), config=new File(home,".fex-emu"), cache=new File(home,".cache/fex-emu");
            for(File directory:new File[]{home,temp,config,cache})
                if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("Falha ao criar "+directory);
            env.put("HOME",home.getAbsolutePath()); env.put("TMPDIR",temp.getAbsolutePath()); env.put("XDG_RUNTIME_DIR",temp.getAbsolutePath());
            env.put("FEX_APP_CONFIG_LOCATION",config.getAbsolutePath()+"/");
            env.put("FEX_APP_DATA_LOCATION",config.getAbsolutePath()+"/");
            env.put("FEX_APP_CACHE_LOCATION",cache.getAbsolutePath()+"/");
            if(!audio.awaitReady(5000)) {
                env.put("GOW3_AUDIO","none");
                RunLog.write(this,"Áudio indisponível após 5 s: usando timer sem som nesta sessão");
            } else RunLog.write(this,"Áudio PCM pronto antes da execução");
            env.put("LD_LIBRARY_PATH",getApplicationInfo().nativeLibraryDir);
            env.put("GOW3_ANDROID_PORT","1");
            env.put("GOW3_ANDROID_INPUT","1");
            env.put("GOW3_PAD_FILE",new File(state,"controller.state").getAbsolutePath());
            env.put("GOW3_AUDIO_FIFO",new File(state,"audio.fifo").getAbsolutePath());
            env.put("GOW3_PRESENT_SOCKET",new File(state,"present.sock").getAbsolutePath());
            env.put("GOW3_SCREEN_W",String.valueOf(getResources().getDisplayMetrics().widthPixels));
            env.put("GOW3_SCREEN_H",String.valueOf(getResources().getDisplayMetrics().heightPixels));
            env.put("GOW3_DISPLAY_HZ",String.valueOf(Math.round(getWindowManager().getDefaultDisplay().getRefreshRate())));
            env.put("GOW3_GPU_USER_DIR",state.getAbsolutePath());
            env.put("TLOU_FRAME_SUMMARY","1");
            env.put("GOW3_VSYNC","1");
            // Desktop temporal injections target updated game shaders. Keep the
            // original render path until those hashes are validated on Android.
            env.put("GOW3_UPSCALER","off");
            env.put("GOW3_JITTER","0");
            env.put("GOW3_OBJECT_MOTION","0");
            env.put("GOW3_FSR1","0");
            env.put("GOW3_RCAS","0");
            PortSettings.environment(this,env);
            String selectedDriverName=getSharedPreferences("tlou",MODE_PRIVATE)
                .getString("driver_name","");
            boolean xclipse540=PortSettings.isXclipse540();
            if(XclipseRadvProfile.apply(env,xclipse540,selectedDriverName)) {
                RunLog.write(this,XclipseRadvProfile.describe());
            } else if(xclipse540) {
                RunLog.write(this,"Xclipse 540 detected; custom RADV Xclipse driver not selected. " +
                    "Keeping the selected/system Vulkan driver without RADV-specific flags.");
            }
            RunLog.write(this,PortSettings.experimentReport(this,env));
            String driver=getSharedPreferences("tlou",MODE_PRIVATE).getString("driver_path", "");
            if(!driver.isEmpty()) {
                File custom=new File(driver);
                if(!custom.isFile()) throw new IllegalStateException("Driver selecionado ausente; selecione outro driver no menu");
                DriverArchive.validateLibrary(custom);
                env.put("GOW3_VULKAN_DRIVER",custom.getAbsolutePath());
                env.put("LD_LIBRARY_PATH",custom.getParent()+":"+getApplicationInfo().nativeLibraryDir);
            }
            builder.directory(app0);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            RunLog.write(this,"Comando: "+args+"\nLD_LIBRARY_PATH="+env.get("LD_LIBRARY_PATH")+"\nDriver="+env.get("GOW3_VULKAN_DRIVER"));
            update("Iniciando TLOU x86-64 pelo FEX...");
            if(closing.get())return;
            RunLog.write(this,"ProcessBuilder.start • iniciando FEX • watchdog de teste desativado");
            gpuTurbo.start(this); // root is optional; if unavailable gameplay still runs
            game=builder.start();
            if(closing.get()){game.destroyForcibly();game.waitFor();return;}
            RunLog.write(this,"Processo FEX criado");
            final int result=game.waitFor();
            RunLog.write(this,"Processo FEX finalizado: código "+result);
            if (!closing.get()) {
                String message="Runtime finalizado (código "+result+"). Veja o log.";
                update(message);
                error(message+"\n"+tailLog());
            }
        } catch (Throwable err) {
            RunLog.failure(this,"Falha ao executar FEX",err);
            if(!closing.get()) error("Falha ao iniciar runtime TLOU: "+err+"\n"+tailLog());
        } finally {
            // Never release the transfer lock while a surviving child could still write saves.
            Process child=game;
            if(child!=null&&child.isAlive()) {child.destroyForcibly();boolean interrupted=false;
                while(child.isAlive())try{child.waitFor();}catch(InterruptedException e){interrupted=true;}
                if(interrupted)Thread.currentThread().interrupt();
            }
            if(gameChoices!=null)gameChoices.close();
            gpuTurbo.stop(this);
            if(saveGuard!=null)try{saveGuard.close();}catch(IOException e){RunLog.failure(this,"Saves lock",e);}
        }
    }

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void applyLiveSettings() {
        if(closing.get() || status==null || touch==null)return;
        SharedPreferences p=PortSettings.prefs(this);
        status.setVisibility(p.getBoolean("hud_visible",true)?View.VISIBLE:View.GONE);
        HudPresentation.style(status,p);
        boolean show=p.getBoolean("touch_visible",true);if(!show)touch.release();touch.setVisibility(show?View.VISIBLE:View.GONE);touch.setAlpha(p.getInt("touch_opacity",55)/100f);touch.invalidate();
        if(started)Gow3Present.fullscreen(p.getBoolean("fullscreen",false));
        if(started) {
            boolean turbo=p.getBoolean("gpu_turbo",false);
            if(lastTurboChoice==null || lastTurboChoice.booleanValue()!=turbo) {
                lastTurboChoice=turbo;
                new Thread(()->{
                    if(turbo)gpuTurbo.start(this);
                    else gpuTurbo.stop(this);
                },"tlou-adreno-turbo-toggle").start();
            }
        }
        placeHud();
    }
    private void placeHud() {
        status.post(()->{
            if(gameLayout==null || closing.get() || gameLayout.getWidth()<1 ||
                gameLayout.getHeight()<1)return;
            SharedPreferences p=PortSettings.prefs(this);
            android.graphics.Insets safe=android.graphics.Insets.NONE;
            WindowInsets insets=gameLayout.getRootWindowInsets();
            if(insets!=null)safe=insets.getInsets(WindowInsets.Type.displayCutout()|WindowInsets.Type.systemBars());
            int margin=dp(8);
            int availableWidth=Math.max(1,gameLayout.getWidth()-safe.left-safe.right);
            int availableHeight=Math.max(1,gameLayout.getHeight()-safe.top-safe.bottom);
            int allowedWidth=Math.max(1,availableWidth-2*margin);
            if(status.getMaxWidth()!=allowedWidth)status.setMaxWidth(allowedWidth);
            int[] anchor=HudPlacement.position(availableWidth,availableHeight,status.getWidth(),status.getHeight(),
                margin,p.getString("hud_corner","0"),p.getFloat("hud_x",0),p.getFloat("hud_y",0));
            int x=safe.left+anchor[0],y=safe.top+anchor[1];
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)status.getLayoutParams();
            if(lp.gravity== (Gravity.TOP|Gravity.LEFT) && lp.leftMargin==x &&
                lp.topMargin==y && lp.rightMargin==0 && lp.bottomMargin==0)return;
            lp.gravity=Gravity.TOP|Gravity.LEFT;
            lp.leftMargin=x;lp.topMargin=y;lp.rightMargin=0;lp.bottomMargin=0;
            status.setLayoutParams(lp);
        });
    }
    private void showMenu() {
        if(closing.get())return;
        if(!started || touch==null || pad==null){stopAndExit();return;}
        if(playerMenu!=null && playerMenu.isShowing())return;
        touch.release();pad.reset(); // release held inputs; keep the controller/provider connected
        playerMenu=new PortMenu(this,true,new PortMenu.Actions(){
            public void logs(){showLog();}public void exportLogs(){}public void chooseGame(){}public void installPkg(){}
            public void importDriver(){}public void selectDriver(){}
            public void editControls(){editingControls=true;touch.setVisibility(View.VISIBLE);touch.setAlpha(.9f);touch.edit(()->{editingControls=false;applyLiveSettings();});}
            public void resetControls(){touch.resetLayout();applyLiveSettings();}
            public void exitGame(){stopAndExit();}public void quitApp(){stopAndExit(true);}public void changed(){applyLiveSettings();}
        });
        playerMenu.open("home");
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if(edgeMenu==null || !started || closing.get() || editingControls)
            return super.dispatchTouchEvent(event);
        int result=edgeMenu.onEvent(event);
        if(result==EdgeMenuGesture.OPEN) {
            MotionEvent cancel=MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);cancel.recycle();
            showMenu();return true;
        }
        if(result==EdgeMenuGesture.CONSUME)return true;
        return super.dispatchTouchEvent(event);
    }
    @Override public void onBackPressed(){
        if(editingControls && touch!=null)touch.finishEdit();else showMenu();
    }

    private String tailLog() { return RunLog.tail(RunLog.runtime(this),12000); }

    private void showLog() {
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);
        TextView text=new TextView(this);text.setText(RunLog.summary(this));text.setTextIsSelectable(true);text.setTextSize(12);text.setPadding(24,16,24,16);scroll.addView(text);
        new AlertDialog.Builder(this).setTitle(TlouLanguage.t(this,"TLOU — logs")).setView(scroll).setPositiveButton(TlouLanguage.t(this,"Fechar"),null).show();
    }

    private void stopAndExit() { stopAndExit(false); }
    private void stopAndExit(boolean quitApp) {
        if (!closing.compareAndSet(false,true)) return;
        handler.removeCallbacksAndMessages(null);
        Process child=game;
        if (child!=null) {
            child.destroy();
            new Thread(()->{try{if(!child.waitFor(1,java.util.concurrent.TimeUnit.SECONDS))child.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();child.destroyForcibly();}},"tlou-process-stop").start();
        }
        try { if(touch!=null) touch.release(); } catch(Exception ignored) {}
        try { if(audio!=null) audio.stop(); } catch(Exception ignored) {}
        try { gpuTurbo.stop(this); } catch(Exception ignored) {}
        try { if(started) Gow3Present.stop(); } catch(Exception ignored) {}
        if(quitApp)finishAffinity(); else finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if(pad!=null && pad.key(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if(pad!=null && pad.motion(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed=false;
        fpsMeter.reset();
        if(audio!=null) audio.pause();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed=true;
        fpsMeter.reset();
        getWindow().getDecorView().post(this::immersive);
        if(audio!=null) audio.resume();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus) immersive();
    }

    @Override protected void onDestroy() {
        stopAndExit();
        if(playerMenu!=null)playerMenu.dismiss();
        if(gameChoices!=null)gameChoices.close();
        if(hudTelemetry!=null)hudTelemetry.close();
        super.onDestroy();
    }
}

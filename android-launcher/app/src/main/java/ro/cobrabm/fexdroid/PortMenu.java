package ro.cobrabm.fexdroid;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;

/** In-game-only TLOU-themed settings drawer; launcher uses its four tabs. */
final class PortMenu {
    interface Actions {
        void logs(); void exportLogs(); void chooseGame(); void installPkg();
        void importDriver(); void selectDriver(); void editControls(); void resetControls(); void exitGame();
        default void importSaves() {}
        default void exportSaves() {}
        default void quitApp(){exitGame();}
        void changed();
    }
    private static final int BG=0xff151c16, SURFACE=0xff253027, INK=0xffecefe4;
    private static final int OLIVE=0xffc0cb9e, MUTED=0xffa6b1a3;
    private final Activity activity;
    private final Actions actions;
    private final boolean inGame;
    private final SharedPreferences prefs;
    private Dialog dialog;
    private LinearLayout content;
    private String section;

    PortMenu(Activity activity,boolean inGame,Actions actions) {
        this.activity=activity;this.inGame=inGame;this.actions=actions;
        if(!inGame)throw new IllegalArgumentException("O menu do jogo é exclusivo da GameActivity");
        prefs=PortSettings.prefs(activity);
    }
    private int dp(int value) {
        return Math.round(value*activity.getResources().getDisplayMetrics().density);
    }
    private String tr(String s) {return TlouLanguage.t(activity,s);}
    private TextView text(String value,int size,int color) {
        TextView v=new TextView(activity);v.setText(tr(value));v.setTextSize(size);
        v.setTextColor(color);v.setPadding(0,dp(8),0,dp(9));return v;
    }
    void open(String initial) {
        section=initial;dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(16),dp(20),dp(18));root.setBackgroundColor(BG);
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("THE LAST OF US / DAEDALUS",15,INK);
        title.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=new Button(activity);close.setText("×");close.setTextSize(22);close.setAllCaps(false);
        close.setTextColor(INK);close.setBackgroundTintList(ColorStateList.valueOf(SURFACE));
        close.setOnClickListener(v->dialog.dismiss());
        header.addView(close,new LinearLayout.LayoutParams(dp(46),dp(46)));root.addView(header);
        View line=new View(activity);line.setBackgroundColor(0xff647055);
        LinearLayout.LayoutParams rule=new LinearLayout.LayoutParams(-1,dp(1));
        rule.bottomMargin=dp(12);root.addView(line,rule);
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);
        content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(root);
        Window w=dialog.getWindow();
        if(w!=null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp=w.getAttributes();lp.dimAmount=.6f;
            lp.gravity=Gravity.LEFT|Gravity.TOP;lp.width=Math.min(dp(390),
                (int)(activity.getResources().getDisplayMetrics().widthPixels*.94));lp.height=-1;
            w.setAttributes(lp);
        }
        dialog.setOnDismissListener(d->actions.changed());
        render();dialog.show();
        if(w!=null)w.setLayout(Math.min(dp(390),
            (int)(activity.getResources().getDisplayMetrics().widthPixels*.94)),-1);
    }
    boolean isShowing(){return dialog!=null && dialog.isShowing();}
    void dismiss(){if(dialog!=null)dialog.dismiss();}
    int drawerGravity(){return dialog.getWindow().getAttributes().gravity;}
    private void heading(String label) {
        TextView v=text(label,13,OLIVE);v.setTypeface(null,Typeface.BOLD);content.addView(v);
    }
    private void note(String label) {content.addView(text(label,12,MUTED));}
    private void row(String label,Runnable callback) {
        Button b=new Button(activity);b.setText(tr(label));b.setAllCaps(false);
        b.setTextSize(14);b.setTextColor(INK);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
        b.setBackgroundTintList(ColorStateList.valueOf(SURFACE));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));
        lp.bottomMargin=dp(7);content.addView(b,lp);b.setOnClickListener(v->callback.run());
    }
    private void action(String label,Runnable callback) {
        row(label,()->{dialog.dismiss();callback.run();});
    }
    private void toggle(String key,String label,boolean fallback) {
        Switch s=new Switch(activity);s.setText(tr(label));s.setTextSize(14);
        s.setTextColor(INK);s.setPadding(0,dp(10),0,dp(10));
        s.setChecked(prefs.getBoolean(key,fallback));
        s.setOnCheckedChangeListener((v,checked)->{
            prefs.edit().putBoolean(key,checked).apply();actions.changed();
        });content.addView(s);
    }
    private void choice(String key,String label,String[] names,String[] values,String fallback) {
        heading(label);
        Spinner spinner=new Spinner(activity,Spinner.MODE_DROPDOWN);
        String[] translated=new String[names.length];
        for(int i=0;i<names.length;++i)translated[i]=tr(names[i]);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,
            android.R.layout.simple_spinner_dropdown_item,translated);
        spinner.setAdapter(adapter);
        String current=prefs.getString(key,fallback);int selected=0;
        for(int i=0;i<values.length;++i)if(values[i].equals(current))selected=i;
        spinner.setSelection(selected);spinner.setTag(selected);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View v,int index,long id){
                if((Integer)spinner.getTag()==index)return;
                spinner.setTag(index);
                prefs.edit().putString(key,values[index]).apply();actions.changed();
            }
        });
        content.addView(spinner,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    private void language() {
        heading("Idioma do aplicativo");
        Spinner spinner=new Spinner(activity,Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,
            android.R.layout.simple_spinner_dropdown_item,TlouLanguage.options(activity));
        spinner.setAdapter(adapter);
        int index=TlouLanguage.selectedOption(activity);spinner.setSelection(index);spinner.setTag(index);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int selected,long id) {
                if((Integer)spinner.getTag()==selected)return;
                spinner.setTag(selected);
                prefs.edit().putString("ui_language",TlouLanguage.selectedCode(selected)).apply();
                actions.changed();render(); // apply to menu immediately, even in-game
            }
        });
        content.addView(spinner,new LinearLayout.LayoutParams(-1,dp(50)));
        note("O idioma do jogo é escolhido no próprio The Last of Us.");
    }
    private void setScreenMode(boolean fill) {
        prefs.edit().putBoolean("fullscreen",fill).apply();
        actions.changed(); // GameActivity updates SurfaceFlinger immediately
        render(); // visible selection responds without dismiss/restarting game
    }
    private void screenMode() {
        heading("Modo de exibição");
        boolean full=prefs.getBoolean("fullscreen",false);
        row((full?"○ ":"● ")+tr("Tela normal (proporção original)"),
            ()->setScreenMode(false));
        row((full?"● ":"○ ")+tr("Tela cheia (preencher a tela)"),
            ()->setScreenMode(true));
        note("O modo muda durante o jogo, sem reiniciar.");
    }
    private void slider(String key,String label,int min,int max,int fallback) {
        TextView title=text(tr(label)+": "+prefs.getInt(key,fallback),13,INK);
        content.addView(title);
        SeekBar bar=new SeekBar(activity);bar.setMax(max-min);
        bar.setProgress(prefs.getInt(key,fallback)-min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar v){}
            public void onStopTrackingTouch(SeekBar v){actions.changed();}
            public void onProgressChanged(SeekBar v,int n,boolean user){
                if(!user)return;
                prefs.edit().putInt(key,n+min).apply();
                title.setText(tr(label)+": "+(n+min));actions.changed();
            }
        });
        content.addView(bar,new LinearLayout.LayoutParams(-1,dp(38)));
    }
    private void go(String target) {section=target;render();}
    private void render() {
        content.removeAllViews();
        if(!"home".equals(section))row("‹ Voltar",()->go("home"));
        switch(section) {
            case "home":
                heading(inGame?"DURANTE O JOGO":"MENU");
                row("Vídeo e tela",()->go("video"));
                screenMode(); // separate one-tap fit and fill actions in the main in-game menu
                row("Patches",()->go("patches"));
                row("Idioma e interface",()->go("settings"));
                row("Controles e HUD",()->go("hud"));
                if(!inGame) {
                    row("Jogo e drivers",()->go("library"));
                    row("Saves",()->go("saves"));
                }
                row("Sobre",()->go("about"));
                action("Voltar ao launcher",actions::exitGame);
                action("Sair do aplicativo",actions::quitApp);
                break;
            case "video":
                heading("VÍDEO E TELA");
                choice("resolution","Resolução interna",
                    new String[]{"Original (1080p)","1280 × 720","960 × 540","640 × 360"},
                    new String[]{"original","720","540","360"},"original");
                screenMode();
                choice("fps_limit","Limite de FPS",
                    new String[]{"30 FPS","60 FPS"},
                    new String[]{"30","60"},"60");
                choice("draw_prep_workers","Draw preparation workers",
                    new String[]{"Off (0)","1 background worker","2 background workers"},
                    new String[]{"0","1","2"},PortSettings.isXclipse540()?"1":"0");
                note("Restart the game to apply. Compare Off / 1 / 2 on the same scene; more workers are not always faster.");
                toggle("vsync","VSync",true);
                choice("render_path","Método de resolução",
                    new String[]{"Unificado: cena e mira na mesma resolução",
                                 "Compatibilidade: interface 1080p (proxies)"},
                    new String[]{"guest","proxy"},"guest");
                note("Reinicie o jogo para aplicar o método de resolução.");
                note("Sincronização da geometria: Completo (único modo). "
                     +"Preserva a correção das deformações.");
                note("Reinicie o jogo para aplicar o método de resolução.");
                break;
            case "patches":
                heading("PATCHES");
                note("Patches validados para o eboot reconhecido. Reinicie para aplicar.");
                if (PortSettings.isXclipse540()) {
                    note("Xclipse 540 profile: skin subsurface lighting is enabled; other effect choices are preserved.");
                }
                toggle("ssao","Desativar SSAO",false);
                toggle("motion_blur","Desativar desfoque de movimento",false);
                toggle("depth_of_field","Desativar profundidade de campo",false);
                toggle("bloom","Desativar bloom",false);
                toggle("sunlight_shadows","Desativar sombras solares",false);
                toggle("subsurface","Desativar subsurface",false);
                row("Restaurar patches e resolução",()->{
                    SharedPreferences.Editor e=prefs.edit().putString("resolution","original");
                    for(String key:PortSettings.EFFECTS)e.putBoolean(key,false);
                    e.apply();actions.changed();render();
                });
                break;
            case "settings":
                heading("AJUSTES");
                language();
                screenMode();
                toggle("gpu_turbo","Adreno Turbo (sem root)",false);
                note("Usa KGSL sem root; respeita limites térmicos. Pode ser ativado ou desativado durante o jogo.");
                toggle("launch_direct","Entrar direto no jogo",false);
                break;
            case "hud":
                heading("CONTROLES E HUD");
                toggle("hud_visible","Mostrar FPS",true);
                choice("hud_mode","Estilo do HUD",
                    new String[]{"Completa (FPS / GPU / CPU)","FPS + tempo de frame","Apenas FPS"},
                    new String[]{"full","compact","minimal"},"full");
                choice("hud_corner","Posição do HUD",
                    new String[]{"Superior esquerdo","Superior direito",
                        "Inferior esquerdo","Inferior direito","Superior central",
                        "Centro da tela","Inferior central","Central esquerdo",
                        "Central direito","Personalizada (arrastar)"},
                    new String[]{"0","1","2","3","4","5","6","7","8","-1"},"0");
                toggle("hud_gpu","Uso da GPU",true);
                toggle("hud_cpu","Uso da CPU (jogo)",true);
                slider("hud_size","Tamanho do HUD",10,26,12);
                slider("hud_opacity","Opacidade do fundo (%)",0,100,65);
                toggle("touch_visible","Controles na tela",true);
                choice("control_color","Cor dos controles",
                    new String[]{"Verde oliva","Âmbar","Branco","Vermelho","Azul"},
                    new String[]{"olive","amber","white","red","blue"},"olive");
                slider("touch_opacity","Opacidade dos controles (%)",15,100,55);
                if(inGame) {
                    action("Editar posição dos botões",actions::editControls);
                    action("Restaurar posição dos botões",actions::resetControls);
                }
                break;
            case "library":
                heading("JOGO E DRIVERS");
                action("Escolher pasta do jogo",actions::chooseGame);
                action("Instalar por PKG",actions::installPkg);
                action("Selecionar driver Vulkan",actions::selectDriver);
                action("Importar driver ZIP ou SO",actions::importDriver);
                break;
            case "saves":
                heading("SAVES");
                note("Feche o jogo antes de importar ou exportar saves.");
                action("Exportar saves ZIP",actions::exportSaves);
                action("Importar saves ZIP",actions::importSaves);
                break;
            case "about":
                content.addView(AboutPanel.create(activity,actions::logs,null));
                break;
            default:
                section="home";render();
        }
    }
}

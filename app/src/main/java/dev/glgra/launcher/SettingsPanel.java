package dev.glgra.launcher;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Shared by the installed Settings icon and the settings below the editing workspace. */
final class SettingsPanel extends LinearLayout {
    private final LayoutStore store;
    private final UpdateRepository updates;
    private final Runnable changed;
    private final java.util.concurrent.ExecutorService loader=java.util.concurrent.Executors.newSingleThreadExecutor();
    private AppCatalog catalog;
    private TextView updateText;
    private Button updateButton;
    private boolean closed;
    SettingsPanel(Context context){this(context,()->{});}
    SettingsPanel(Context context,Runnable changed){
        super(context);this.changed=changed;store=new LayoutStore(context);updates=new UpdateRepository(context);setOrientation(VERTICAL);int pad=Glass.dp(context,24);setPadding(pad,pad,pad,pad);setBackgroundColor(Design.surface(context));
        Design.type(heading("Launcher Settings",28),28,36,500);
        button("Open Home",()->context.startActivity(new Intent(context,HomeActivity.class)));
        section("Dock and usage ranking");button("Usage access",()->context.startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS,Uri.parse("package:"+context.getPackageName()))));
        note("Foreground time is summarized locally. Without usage access, apps use a stable fallback order.");button("Pinned dock apps",this::pins);
        section("App categories and hidden apps");button("Hidden apps",this::hidden);button("Category overrides",this::categories);
        section("Home and wallpaper");toggle("Wallpaper parallax","parallax",false);
        section("Notification widget");toggle("Show notification widget","notificationsEnabled",true);toggle("Return to notifications when coming Home","returnNotifications",false);
        choice("Width",new String[]{"2 columns","3 columns","4 columns"},store.prefs.getInt("notificationWidth",3)-2,i->{store.prefs.edit().putInt("notificationWidth",i+2).apply();changed.run();});
        button("Notification access",()->context.startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        note("Read-only previews. Android may hide private content. Counts refer to active notifications, not messages.");
        section("Haptic intensity");choice("",new String[]{"Off","Light","Medium","Strong"},store.prefs.getInt("haptics",2),i->{store.prefs.edit().putInt("haptics",i).apply();changed.run();});
        section("General and updates");
        try{heading("Installed · "+context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName,18);}catch(Exception ignored){}
        note(updates.installedChangelog());updateText=note("");updateButton=button("Check for updates",()->{if(updates.ready())updates.install((Activity)context);else updates.checkIfStale(this::updateState);});
        updateState();button("GitHub repository",()->context.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/ronoxdegrand/launcher"))));
        button("MIT license",()->{String text="MIT License · Copyright (c) 2026 ronoxdegrand";try(java.io.InputStream in=context.getAssets().open("LICENSE")){text=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(java.io.IOException ignored){}new AlertDialog.Builder(context).setTitle("MIT license").setMessage(text).show();});
        note("Samsung app pairs and prefilled assistant queries are conditional on supported device APIs. They remain disabled until verified on your phone.");
        loader.execute(()->{AppCatalog loaded=new AppCatalog(context);loaded.reload();post(()->{if(!closed)catalog=loaded;});});
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();updates.checkIfStale(this::updateState);}
    private void updateState(){if(closed)return;updateText.setText(updates.status()+"\n"+updates.availableChangelog());updateButton.setText(updates.ready()?updates.status():"Check for updates");}
    private void toggle(String label,String key,boolean fallback){Switch toggle=new Switch(getContext());toggle.setText(label);toggle.setTextColor(Design.ink(getContext()));Design.type(toggle,16,24,400);toggle.setMinHeight(Glass.dp(getContext(),56));toggle.setPadding(0,Glass.dp(getContext(),12),0,Glass.dp(getContext(),12));toggle.setChecked(store.prefs.getBoolean(key,fallback));toggle.setOnCheckedChangeListener((v,on)->{store.prefs.edit().putBoolean(key,on).apply();changed.run();});addView(toggle);}
    private void choice(String title,String[] labels,int selected,java.util.function.IntConsumer action){if(!title.isBlank())heading(title,16);RadioGroup group=new RadioGroup(getContext());for(int i=0;i<labels.length;i++){RadioButton option=new RadioButton(getContext());option.setId(View.generateViewId());option.setText(labels[i]);option.setTextColor(Design.ink(getContext()));Design.type(option,14,20,400);option.setMinHeight(Glass.dp(getContext(),48));group.addView(option);if(i==selected)option.setChecked(true);}group.setOnCheckedChangeListener((g,id)->{for(int i=0;i<g.getChildCount();i++)if(g.getChildAt(i).getId()==id)action.accept(i);});addView(group);}
    private boolean loaded(){if(catalog!=null)return true;Toast.makeText(getContext(),"Apps are loading.",Toast.LENGTH_SHORT).show();return false;}
    private void hidden(){if(!loaded())return;List<AppCatalog.Entry> apps=catalog.all();Set<String> hidden=catalog.hidden();boolean[] checked=new boolean[apps.size()];for(int i=0;i<apps.size();i++)checked[i]=hidden.contains(apps.get(i).key)||hidden.contains(apps.get(i).packageName);
        new AlertDialog.Builder(getContext()).setTitle("Hidden apps").setMultiChoiceItems(apps.stream().map(e->e.label).toArray(String[]::new),checked,(d,i,on)->{if(on)hidden.add(apps.get(i).packageName);else{hidden.remove(apps.get(i).packageName);hidden.remove(apps.get(i).key);}store.prefs.edit().putStringSet("hidden",new HashSet<>(hidden)).apply();changed.run();}).show();}
    private void pins(){if(!loaded())return;List<AppCatalog.Entry> apps=catalog.all();List<String> pins=new ArrayList<>();String stored=store.prefs.getString("dockPins","[]");try{org.json.JSONArray a=new org.json.JSONArray(stored);Set<String> packages=new HashSet<>();for(int i=0;i<a.length()&&pins.size()<4;i++){String key=a.getString(i);AppCatalog.Entry entry=catalog.find(key);if(entry==null||!available(entry)||!packages.add(entry.packageName))continue;pins.add(key);}}catch(org.json.JSONException ignored){}
        String cleaned=new org.json.JSONArray(pins).toString();if(!cleaned.equals(stored)){store.prefs.edit().putString("dockPins",cleaned).apply();changed.run();}
        boolean[] checked=new boolean[apps.size()];for(int i=0;i<apps.size();i++)checked[i]=pins.contains(apps.get(i).key);
        new AlertDialog.Builder(getContext()).setTitle("Pin up to four apps").setMultiChoiceItems(apps.stream().map(e->e.label).toArray(String[]::new),checked,(d,i,on)->{
            AppCatalog.Entry selected=apps.get(i);String key=selected.key;
            if(on&&!available(selected)){((AlertDialog)d).getListView().setItemChecked(i,false);Toast.makeText(getContext(),"This app is no longer available.",Toast.LENGTH_SHORT).show();return;}
            boolean samePackage=pins.stream().map(catalog::find).filter(Objects::nonNull).anyMatch(entry->entry.packageName.equals(selected.packageName));
            if(on&&!samePackage&&pins.size()>=4){((AlertDialog)d).getListView().setItemChecked(i,false);Toast.makeText(getContext(),"Unpin an app before pinning another.",Toast.LENGTH_SHORT).show();return;}
            if(on){pins.removeIf(pin->{AppCatalog.Entry entry=catalog.find(pin);return entry==null||entry.packageName.equals(selected.packageName);});pins.add(key);}else pins.remove(key);
            for(int row=0;row<apps.size();row++)((AlertDialog)d).getListView().setItemChecked(row,pins.contains(apps.get(row).key));
            store.prefs.edit().putString("dockPins",new org.json.JSONArray(pins).toString()).apply();changed.run();}).show();}
    private boolean available(AppCatalog.Entry entry){try{android.content.pm.ActivityInfo info=getContext().getPackageManager().getActivityInfo(entry.component,0);return info.enabled&&info.applicationInfo.enabled;}catch(android.content.pm.PackageManager.NameNotFoundException e){return false;}}
    private void categories(){if(!loaded())return;List<AppCatalog.Entry> apps=catalog.all();new AlertDialog.Builder(getContext()).setTitle("Choose app").setItems(apps.stream().map(e->e.label+" · "+catalog.category(e)).toArray(String[]::new),(d,i)->{
        String[] names={"Use Android category","Games","Music & audio","Video","Photography","Social","News","Maps & travel","Productivity","Uncategorized","Custom…"};
        new AlertDialog.Builder(getContext()).setTitle(apps.get(i).label).setItems(names,(dialog,index)->{
            String key="category:"+apps.get(i).packageName;
            if(index==10){EditText edit=new EditText(getContext());edit.setSingleLine();edit.setText(store.prefs.getString(key,""));edit.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int n){}public void onTextChanged(CharSequence s,int a,int b,int c){store.prefs.edit().putString(key,s.toString().trim()).apply();changed.run();}public void afterTextChanged(android.text.Editable e){}});new AlertDialog.Builder(getContext()).setTitle("Category name").setView(edit).show();}
            else{store.prefs.edit().putString(key,index==0?"":names[index]).apply();changed.run();}
        }).show();}).show();}
    private void section(String label){TextView title=heading(label,14);Design.type(title,14,20,600);title.setTextColor(Design.secondary(getContext()));title.setPadding(0,Glass.dp(getContext(),32),0,Glass.dp(getContext(),12));}
    private TextView heading(String text,int size){TextView view=new TextView(getContext());view.setText(text);view.setTextSize(size);Design.type(view,size,size>=22?32:size>=16?24:20,size>=18?500:400);view.setTextColor(Design.ink(getContext()));addView(view);return view;}
    private TextView note(String text){TextView view=heading(text,14);Design.type(view,14,22,400);view.setTextColor(Design.secondary(getContext()));view.setPadding(0,Glass.dp(getContext(),8),0,Glass.dp(getContext(),16));return view;}
    private Button button(String label,Runnable action){Button button=new Button(getContext());button.setText(label);button.setAllCaps(false);button.setTextColor(Glass.ink(getContext()));button.setTextSize(14);button.setMinHeight(Glass.dp(getContext(),52));Design.control(button);button.setPadding(0,Glass.dp(getContext(),12),0,Glass.dp(getContext(),12));button.setOnClickListener(v->action.run());LinearLayout.LayoutParams row=new LinearLayout.LayoutParams(-1,-2);row.topMargin=Glass.dp(getContext(),6);row.bottomMargin=Glass.dp(getContext(),6);addView(button,row);return button;}
    void close(){closed=true;updates.close();loader.shutdownNow();}
}

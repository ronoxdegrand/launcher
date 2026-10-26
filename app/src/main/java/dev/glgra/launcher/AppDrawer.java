package dev.glgra.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.text.*;
import java.util.*;

/** One drawer for the search button, home swipe and tracked dock gesture. */
final class AppDrawer extends FrameLayout {
    interface Actions {
        void launch(AppCatalog.Entry entry);
        void context(View source, AppCatalog.Entry entry);
        void bindDrag(View source, AppCatalog.Entry entry);
        void close();
    }
    final EditText search;
    private final AppCatalog catalog;
    private final UsageRepository usage;
    private final Actions actions;
    private final LinearLayout results, categories;
    private final ScrollView scrolling;
    private boolean keyboard, opened;
    private String expanded = "";
    private float downY, downX;
    private boolean startedAtTop, directionLocked, downward, dismissing, contextActive;
    private final int touchSlop;
    AppDrawer(Context context, AppCatalog catalog, UsageRepository usage, Actions actions) {
        super(context); this.catalog=catalog;this.usage=usage;this.actions=actions;
        touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        Glass.applySquare(this);setSoundEffectsEnabled(false);
        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),dp(8),dp(16),dp(12));
        TextView handle=new TextView(context);handle.setText("⌄");handle.setTextColor(Glass.ink(context));handle.setGravity(Gravity.CENTER);handle.setSoundEffectsEnabled(false);
        handle.setOnClickListener(v->actions.close());body.addView(handle,new LinearLayout.LayoutParams(-1,dp(32)));
        search=new EditText(context);search.setSingleLine(true);search.setHint("Search apps");search.setTextColor(Glass.ink(context));search.setTextSize(20);search.setSoundEffectsEnabled(false);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setPadding(dp(18),dp(8),dp(18),dp(8));Glass.apply(search);body.addView(search,new LinearLayout.LayoutParams(-1,dp(56)));
        scrolling=new ScrollView(context);scrolling.setVerticalScrollBarEnabled(false);LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);
        results=new LinearLayout(context);results.setOrientation(LinearLayout.VERTICAL);content.addView(results);
        categories=new LinearLayout(context);categories.setOrientation(LinearLayout.VERTICAL);content.addView(categories);
        scrolling.addView(content);body.addView(scrolling,new LinearLayout.LayoutParams(-1,0,1));addView(body,new FrameLayout.LayoutParams(-1,-1));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){refresh();}public void afterTextChanged(Editable e){}});
        search.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_GO || event!=null && event.getKeyCode()==KeyEvent.KEYCODE_ENTER && event.getAction()==KeyEvent.ACTION_UP){
                if(search.getText().toString().isBlank())return true;
                List<AppCatalog.Entry> matches=catalog.search(search.getText().toString(),usage);
                if(!matches.isEmpty())actions.launch(matches.get(0));
                return true;
            }return false;
        });
        search.setOnFocusChangeListener((v,focused)->refresh());
        setOnApplyWindowInsetsListener((view,insets)->{
            boolean shown=insets.isVisible(WindowInsets.Type.ime());
            int bottom=insets.getInsets(WindowInsets.Type.ime()).bottom;
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            setPadding(safe.left,safe.top,safe.right,Math.max(safe.bottom,shown?bottom:0));
            if(keyboard!=shown){keyboard=shown;refresh();}return insets;
        });
        setVisibility(GONE);
    }
    void open(boolean focus){opened=true;setVisibility(VISIBLE);refresh();if(focus)postDelayed(()->{
        if(opened){search.requestFocus();getContext().getSystemService(InputMethodManager.class).showSoftInput(search,InputMethodManager.SHOW_IMPLICIT);}
    },ValueAnimator.areAnimatorsEnabled()?180:0);}
    void reset(){opened=false;android.transition.TransitionManager.endTransitions(categories);getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(),0);search.clearFocus();search.setText("");expanded="";contextActive=false;dismissing=false;scrolling.scrollTo(0,0);setVisibility(GONE);}
    boolean isOpen(){return opened;}
    void refresh(){
        results.removeAllViews();categories.removeAllViews();
        String query=search.getText().toString();
        Map<String,Integer> counts=NotificationRepository.counts();
        for(AppCatalog.Entry entry:catalog.search(query,usage)){
            if(query.isBlank() && results.getChildCount()==4)break;
            LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(6),dp(8),dp(6),dp(8));
            row.addView(icon(entry),new LinearLayout.LayoutParams(dp(48),dp(48)));TextView name=label(entry.label,16);name.setPadding(dp(16),0,0,0);
            row.addView(name,new LinearLayout.LayoutParams(0,-2,1));int count=counts.getOrDefault(entry.packageName,0);
            if(count>0)row.addView(label(String.valueOf(count),14));
            row.setSoundEffectsEnabled(false);row.setOnClickListener(v->actions.launch(entry));row.setOnLongClickListener(v->{showContext(v,entry);return true;});actions.bindDrag(row,entry);results.addView(row);
        }
        if(!query.isBlank() && results.getChildCount()==0)results.addView(label("No apps found",16));
        // Assistant query forwarding remains disabled until a supported route is verified on the user's assistant.
        boolean reveal=!keyboard || !search.hasFocus();
        results.setVisibility(reveal?GONE:VISIBLE);categories.setVisibility(reveal?VISIBLE:GONE);if(reveal)buildCategories();
    }
    private void buildCategories(){
        Map<String,List<AppCatalog.Entry>> groups=new LinkedHashMap<>();
        for(AppCatalog.Entry entry:catalog.ranked(usage))groups.computeIfAbsent(catalog.category(entry),k->new ArrayList<>()).add(entry);
        List<String> names=new ArrayList<>(groups.keySet());
        names.sort(Comparator.comparingDouble((String key)->groups.get(key).stream().mapToDouble(e->usage.score(e.packageName)).sum()).reversed().thenComparing(k->k));
        LinearLayout pair=null;
        for(String name:names){
            List<AppCatalog.Entry> entries=groups.get(name);
            if(name.equals(expanded)){
                finishPair(pair);
                pair=null;LinearLayout block=new LinearLayout(getContext());block.setOrientation(LinearLayout.VERTICAL);block.setPadding(dp(8),dp(12),dp(8),dp(12));
                block.setTransitionName("category-group:"+name);
                TextView heading=label(name+"   −",17);heading.setTransitionName("category-heading:"+name);heading.setOnClickListener(v->expand(""));block.addView(heading);
                GridLayout grid=grid(4);grid.setTransitionName("category-grid:"+name);for(AppCatalog.Entry entry:entries)addCell(grid,appCell(entry),4);block.addView(grid);categories.addView(block);continue;
            }
            if(pair==null){pair=new LinearLayout(getContext());categories.addView(pair,new LinearLayout.LayoutParams(-1,-2));}
            LinearLayout tile=new LinearLayout(getContext());tile.setTransitionName("category-group:"+name);tile.setOrientation(LinearLayout.VERTICAL);tile.setPadding(dp(8),dp(12),dp(8),dp(12));TextView title=label(name,15);title.setTransitionName("category-heading:"+name);tile.addView(title);
            GridLayout grid=grid(2);grid.setTransitionName("category-grid:"+name);for(int i=0;i<Math.min(3,entries.size());i++)addCell(grid,appCell(entries.get(i)),2);
            if(entries.size()>=4){TextView more=label("+"+(entries.size()-3),22);more.setGravity(Gravity.CENTER);more.setOnClickListener(v->expand(name));addCell(grid,more,2);}
            while(grid.getChildCount()<4){View spacer=new View(getContext());spacer.setVisibility(INVISIBLE);addCell(grid,spacer,2);}
            tile.addView(grid);pair.addView(tile,new LinearLayout.LayoutParams(0,-2,1));if(pair.getChildCount()==2)pair=null;
        }
        finishPair(pair);
    }
    private void finishPair(LinearLayout pair){if(pair!=null&&pair.getChildCount()==1){View spacer=new View(getContext());spacer.setVisibility(INVISIBLE);pair.addView(spacer,new LinearLayout.LayoutParams(0,1,1));}}
    private void expand(String name){
        if(ValueAnimator.areAnimatorsEnabled()){
            android.transition.ChangeBounds bounds=new android.transition.ChangeBounds();bounds.setReparent(true);
            android.transition.TransitionSet transition=new android.transition.TransitionSet().setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER);
            transition.addTransition(bounds).addTransition(new android.transition.Fade());transition.setDuration(200);
            transition.setMatchOrder(android.transition.Transition.MATCH_NAME,android.transition.Transition.MATCH_INSTANCE);
            android.transition.TransitionManager.beginDelayedTransition(categories,transition);
        }else android.transition.TransitionManager.endTransitions(categories);
        expanded=name;refresh();
    }
    private GridLayout grid(int columns){GridLayout grid=new GridLayout(getContext());grid.setColumnCount(columns);return grid;}
    private void addCell(GridLayout grid,View child,int columns){GridLayout.LayoutParams lp=new GridLayout.LayoutParams();lp.width=0;lp.height=dp(100);lp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);grid.addView(child,lp);}
    private View appCell(AppCatalog.Entry entry){LinearLayout cell=new LinearLayout(getContext());cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER);
        cell.addView(icon(entry),new LinearLayout.LayoutParams(dp(44),dp(44)));TextView title=label(entry.label,12);title.setMaxLines(2);title.setGravity(Gravity.CENTER);cell.addView(title);
        cell.setTransitionName("category:"+entry.key);cell.setSoundEffectsEnabled(false);cell.setOnClickListener(v->actions.launch(entry));cell.setOnLongClickListener(v->{showContext(v,entry);return true;});actions.bindDrag(cell,entry);return cell;}
    private void showContext(View source,AppCatalog.Entry entry){contextActive=true;actions.context(source,entry);}
    private ImageView icon(AppCatalog.Entry entry){ImageView icon=new ImageView(getContext());icon.setImageDrawable(entry.icon.getConstantState()==null?entry.icon:entry.icon.getConstantState().newDrawable());icon.setContentDescription(entry.label);return icon;}
    private TextView label(String text,int size){TextView view=new TextView(getContext());view.setText(text);view.setTextSize(size);view.setTextColor(Glass.ink(getContext()));view.setSoundEffectsEnabled(false);return view;}
    private int dp(int value){return Glass.dp(getContext(),value);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){downY=event.getY();downX=event.getX();startedAtTop=scrolling.getScrollY()==0;directionLocked=false;downward=false;dismissing=false;contextActive=false;}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!contextActive){
            float dx=event.getX()-downX,dy=event.getY()-downY;
            if(!directionLocked&&Math.max(Math.abs(dx),Math.abs(dy))>touchSlop){directionLocked=true;downward=dy>0&&dy>Math.abs(dx);}
            if(startedAtTop&&directionLocked&&downward)super.requestDisallowInterceptTouchEvent(false);
        }
        return super.dispatchTouchEvent(event);
    }
    @Override public void requestDisallowInterceptTouchEvent(boolean disallow){
        // Keep the chance to claim a downward dismissal before ScrollView locks its own drag.
        if(disallow&&scrolling!=null&&startedAtTop&&!contextActive&&(!directionLocked||downward))return;
        super.requestDisallowInterceptTouchEvent(disallow);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        if(dismissing)return true;
        if(!contextActive&&event.getActionMasked()==MotionEvent.ACTION_MOVE&&startedAtTop&&downward&&event.getY()-downY>dp(48)){dismissing=true;return true;}
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_UP){if(dismissing)actions.close();else performClick();dismissing=false;}
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)dismissing=false;
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}

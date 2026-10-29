package dev.glgra.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.text.*;
import java.util.*;

/** The dock expands into one sheet; its four columns remain the search-result geometry. */
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
    private final HorizontalScrollView resultStrip;
    private final View resultSpace;
    private final LinearLayout header,cluster;
    private int gridRow;
    private boolean keyboard, opened, browsing, touching, refreshPending, closing, searchOnly;
    private String expanded = "";
    private float downY, downX, reveal=1;
    private final Rect dockBounds=new Rect(),revealBounds=new Rect();
    private final ViewOutlineProvider revealOutline=new ViewOutlineProvider(){public void getOutline(View view,android.graphics.Outline outline){outline.setRoundRect(revealBounds,dp(28)*(1-reveal));}};
    private boolean startedAtTop, directionLocked, downward, dismissing, contextActive;
    private final int touchSlop;
    AppDrawer(Context context, AppCatalog catalog, UsageRepository usage, Actions actions) {
        super(context);this.catalog=catalog;this.usage=usage;this.actions=actions;
        touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();Glass.applySquare(this);setSoundEffectsEnabled(false);
        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setClipChildren(false);setClipChildren(false);setClipToPadding(false);
        header=new LinearLayout(context);header.setGravity(Gravity.CENTER);
        View handle=new View(context);android.graphics.drawable.GradientDrawable pill=new android.graphics.drawable.GradientDrawable();pill.setColor(0x66FFFFFF);pill.setCornerRadius(dp(3));handle.setBackground(pill);
        header.addView(handle,new LinearLayout.LayoutParams(dp(34),dp(4)));header.setContentDescription("Close apps");header.setOnClickListener(v->actions.close());body.addView(header,new LinearLayout.LayoutParams(-1,0));header.setVisibility(GONE);
        scrolling=new ScrollView(context);scrolling.setVerticalScrollBarEnabled(false);scrolling.setClipToPadding(false);scrolling.setPadding(0,0,0,0);
        categories=new LinearLayout(context);categories.setOrientation(LinearLayout.VERTICAL);scrolling.addView(categories);
        cluster=new LinearLayout(context);cluster.setOrientation(LinearLayout.VERTICAL);cluster.setClipChildren(false);Glass.area(cluster);
        search=new EditText(context);search.setSingleLine(true);search.setHint("Search apps");search.setTextColor(Glass.ink(context));search.setHintTextColor(0xBBFFFFFF);Design.type(search,22,28,500);search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search,0,0,0);search.setCompoundDrawablePadding(dp(12));search.setSoundEffectsEnabled(false);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);search.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setPadding(dp(24),0,dp(24),0);search.setBackground(new android.graphics.drawable.InsetDrawable(Glass.shape(context,Glass.ROUNDED,0xA310151D),dp(8)));search.setPadding(dp(24),0,dp(24),0);cluster.addView(search,new LinearLayout.LayoutParams(-1,dp(44)));
        resultStrip=new HorizontalScrollView(context);resultStrip.setHorizontalScrollBarEnabled(false);resultStrip.setOverScrollMode(OVER_SCROLL_NEVER);
        results=new LinearLayout(context);results.setOrientation(LinearLayout.HORIZONTAL);resultStrip.addView(results,new HorizontalScrollView.LayoutParams(-2,-1));resultSpace=new View(context);cluster.addView(resultSpace,new LinearLayout.LayoutParams(-1,dp(100)));body.addView(cluster);body.addView(scrolling,new LinearLayout.LayoutParams(-1,0,1));addView(body,new FrameLayout.LayoutParams(-1,-1));FrameLayout.LayoutParams lane=new FrameLayout.LayoutParams(-1,dp(100));lane.topMargin=dp(100);addView(resultStrip,lane);
        resultStrip.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol)sizeResults();});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){resultStrip.scrollTo(0,0);refresh();}public void afterTextChanged(Editable e){}});
        search.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_GO||event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP){
                if(search.getText().toString().isBlank())return true;List<AppCatalog.Entry> matches=catalog.search(search.getText().toString(),usage);if(!matches.isEmpty())actions.launch(matches.get(0));return true;
            }return false;
        });
        search.setOnFocusChangeListener((v,focused)->{if(opened&&!closing){browsing=!focused&&!searchOnly;updatePresentation();}});
        setOnApplyWindowInsetsListener((view,insets)->{applyInsets(insets);boolean shown=insets.isVisible(WindowInsets.Type.ime());
            if(keyboard&&!shown&&opened&&!closing)browsing=!searchOnly;keyboard=shown;if(shown&&!closing)browsing=false;updatePresentation();return insets;});
        setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE){
            @Override public WindowInsets onProgress(WindowInsets insets,List<WindowInsetsAnimation> running){applyInsets(insets);return insets;}
        });
        setVisibility(GONE);
    }
    private void applyInsets(WindowInsets insets){android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());int ime=insets.getInsets(WindowInsets.Type.ime()).bottom;setPadding(safe.left,safe.top,safe.right,Math.max(safe.bottom,ime));}
    void open(boolean focus){open(focus,false);}
    void open(boolean focus,boolean fromSearch){boolean wasOpen=opened;if(!wasOpen)searchOnly=fromSearch;opened=true;closing=false;browsing=false;setVisibility(VISIBLE);if(!wasOpen)refresh();else updatePresentation();if(focus)post(()->{if(opened&&!closing){search.requestFocus();getContext().getSystemService(InputMethodManager.class).showSoftInput(search,InputMethodManager.SHOW_IMPLICIT);}});}
    void hideKeyboard(){closing=true;getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(),0);}
    void reset(){opened=false;touching=false;contextActive=false;refreshPending=false;android.transition.TransitionManager.endTransitions(categories);hideKeyboard();search.clearFocus();search.setText("");expanded="";dismissing=false;browsing=false;scrolling.scrollTo(0,0);resultStrip.scrollTo(0,0);setClipBounds(null);setVisibility(GONE);}
    boolean isOpen(){return opened;}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(opened)setReveal(reveal,dockBounds);}
    void setReveal(float amount,Rect origin){reveal=Math.max(0,Math.min(1,amount));dockBounds.set(origin);float remaining=1-reveal;revealBounds.set(Math.round(origin.left*remaining),Math.round(origin.top*remaining),Math.round(getWidth()+(origin.right-getWidth())*remaining),Math.round(getHeight()+(origin.bottom-getHeight())*remaining));setClipBounds(revealBounds);setOutlineProvider(revealOutline);invalidateOutline();header.setAlpha(reveal);float effects=Math.max(0,Math.min(1,(reveal-.2f)/.8f));scrolling.setAlpha(effects);search.setAlpha(effects);int[] strip=new int[2],parent=new int[2];resultStrip.getLocationOnScreen(strip);getLocationOnScreen(parent);float restingTop=strip[1]-parent[1]-resultStrip.getTranslationY();resultStrip.setTranslationY((origin.top+dp(52)-restingTop)*remaining);for(int i=0;i<results.getChildCount();i++)if(results.getChildAt(i)instanceof AppTile)((AppTile)results.getChildAt(i)).title.setAlpha(reveal);}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){super.onLayout(changed,l,t,r,b);if(opened)setReveal(reveal,dockBounds);}
    void updateGlass(){Glass.applySquare(this);setOutlineProvider(revealOutline);setClipToOutline(true);}
    void setGridRow(int row){if(gridRow==row)return;gridRow=row;search.getLayoutParams().height=row;resultStrip.getLayoutParams().height=row;((FrameLayout.LayoutParams)resultStrip.getLayoutParams()).topMargin=row;resultSpace.getLayoutParams().height=row;resultSpace.requestLayout();search.requestLayout();resultStrip.requestLayout();sizeResults();if(browsing){categories.removeAllViews();buildCategories();}}
    private int rowHeight(){return gridRow>0?gridRow:dp(100);}
    private void sizeResults(){int cell=Math.max(dp(64),resultStrip.getWidth()/4);for(int i=0;i<results.getChildCount();i++){View child=results.getChildAt(i);android.view.ViewGroup.LayoutParams lp=child.getLayoutParams();lp.width=child instanceof AppTile?cell:Math.max(cell,resultStrip.getWidth());lp.height=rowHeight();child.setLayoutParams(lp);}}
    private void updatePresentation(){if(closing)return;resultStrip.setVisibility(browsing?GONE:VISIBLE);resultSpace.setVisibility(browsing?GONE:VISIBLE);scrolling.setVisibility(browsing?VISIBLE:INVISIBLE);if(browsing&&categories.getChildCount()==0)buildCategories();}
    void refresh(){
        if(touching||contextActive){refreshPending=true;return;}refreshPending=false;
        results.removeAllViews();categories.removeAllViews();String query=search.getText().toString();Map<String,Integer> counts=NotificationRepository.counts();
        for(AppCatalog.Entry entry:catalog.search(query,usage)){
            if(query.isBlank()&&results.getChildCount()==4)break;
            AppTile tile=new AppTile(getContext(),entry,counts.getOrDefault(entry.packageName,0),true);tile.title.setAlpha(reveal);
            tile.setOnClickListener(v->actions.launch(entry));tile.setOnLongClickListener(v->{showContext(v,entry);return true;});actions.bindDrag(tile,entry);results.addView(tile,new LinearLayout.LayoutParams(Math.max(dp(64),resultStrip.getWidth()/4),rowHeight()));
        }
        if(!query.isBlank()&&results.getChildCount()==0){TextView empty=label("No apps found",14);empty.setGravity(Gravity.CENTER);results.addView(empty,new LinearLayout.LayoutParams(Math.max(dp(200),resultStrip.getWidth()),rowHeight()));}
        updatePresentation();
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
                pair=null;FrameLayout block=new FrameLayout(getContext());Glass.area(block);
                block.setTransitionName("category-group:"+name);
                TextView heading=label(name,12);heading.setTransitionName("category-heading:"+name);heading.setContentDescription(name);
                GridLayout grid=grid(4);grid.setTransitionName("category-grid:"+name);for(AppCatalog.Entry entry:entries)addCell(grid,appCell(entry),4);TextView collapse=Design.more(getContext(),"−");collapse.setContentDescription("Collapse "+name);collapse.setOnClickListener(v->expand(""));addCell(grid,collapse,4);block.addView(grid,new FrameLayout.LayoutParams(-1,-1));Design.caption(heading);heading.setPadding(dp(16),0,dp(12),0);heading.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);block.addView(heading,new FrameLayout.LayoutParams(-1,dp(20),Gravity.TOP));categories.addView(block,new LinearLayout.LayoutParams(-1,((entries.size()+4)/4)*rowHeight()));continue;
            }
            if(pair==null){pair=new LinearLayout(getContext());categories.addView(pair,new LinearLayout.LayoutParams(-1,-2));}
            FrameLayout tile=new FrameLayout(getContext());tile.setTransitionName("category-group:"+name);Glass.area(tile);TextView title=label(name,12);Design.caption(title);title.setPadding(dp(16),0,dp(12),0);title.setTransitionName("category-heading:"+name);title.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
            GridLayout grid=grid(2);grid.setTransitionName("category-grid:"+name);for(int i=0;i<Math.min(3,entries.size());i++)addCell(grid,appCell(entries.get(i)),2);
            if(entries.size()>=4){TextView more=Design.more(getContext(),"+"+(entries.size()-3));more.setOnClickListener(v->expand(name));addCell(grid,more,2);}
            while(grid.getChildCount()<4){View spacer=new View(getContext());spacer.setVisibility(INVISIBLE);addCell(grid,spacer,2);}
            tile.addView(grid,new FrameLayout.LayoutParams(-1,-1));tile.addView(title,new FrameLayout.LayoutParams(-1,dp(20),Gravity.TOP));pair.addView(tile,new LinearLayout.LayoutParams(0,2*rowHeight(),1));if(pair.getChildCount()==2)pair=null;
        }
        finishPair(pair);
    }
    private void finishPair(LinearLayout pair){if(pair!=null&&pair.getChildCount()==1){View spacer=new View(getContext());spacer.setVisibility(INVISIBLE);pair.addView(spacer,new LinearLayout.LayoutParams(0,1,1));}}
    private void expand(String name){
        if(ValueAnimator.areAnimatorsEnabled()){
            android.transition.ChangeBounds bounds=new android.transition.ChangeBounds();bounds.setReparent(true);
            android.transition.TransitionSet transition=new android.transition.TransitionSet().setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER);
            transition.addTransition(bounds).addTransition(new android.transition.Fade());transition.setDuration(260);transition.setInterpolator(Motion.EFFECTS);
            transition.setMatchOrder(android.transition.Transition.MATCH_NAME,android.transition.Transition.MATCH_INSTANCE);
            android.transition.TransitionManager.beginDelayedTransition(categories,transition);
        }else android.transition.TransitionManager.endTransitions(categories);
        expanded=name;refresh();
    }
    private GridLayout grid(int columns){GridLayout grid=new GridLayout(getContext());grid.setColumnCount(columns);return grid;}
    private void addCell(GridLayout grid,View child,int columns){GridLayout.LayoutParams lp=new GridLayout.LayoutParams();lp.width=0;lp.height=rowHeight();lp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);grid.addView(child,lp);}
    private View appCell(AppCatalog.Entry entry){AppTile cell=new AppTile(getContext(),entry,NotificationRepository.counts().getOrDefault(entry.packageName,0),true);
        cell.setTransitionName("category:"+entry.key);cell.setOnClickListener(v->actions.launch(entry));cell.setOnLongClickListener(v->{showContext(v,entry);return true;});actions.bindDrag(cell,entry);return cell;}
    private void showContext(View source,AppCatalog.Entry entry){contextActive=true;actions.context(source,entry);}
    private ImageView icon(AppCatalog.Entry entry){ImageView icon=new ImageView(getContext());icon.setImageDrawable(entry.icon.getConstantState()==null?entry.icon:entry.icon.getConstantState().newDrawable());icon.setContentDescription(entry.label);return icon;}
    private TextView label(String text,int size){TextView view=new TextView(getContext());view.setText(text);view.setTextSize(size);view.setTextColor(Glass.ink(getContext()));view.setSoundEffectsEnabled(false);return view;}
    private int dp(int value){return Glass.dp(getContext(),value);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){contextActive=false;touching=false;if(refreshPending)refresh();touching=true;downY=event.getY();downX=event.getX();startedAtTop=scrolling.getScrollY()==0;directionLocked=false;downward=false;dismissing=false;}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!contextActive){
            float dx=event.getX()-downX,dy=event.getY()-downY;
            if(!directionLocked&&Math.max(Math.abs(dx),Math.abs(dy))>touchSlop){directionLocked=true;downward=dy>0&&dy>Math.abs(dx);}
            if(startedAtTop&&directionLocked&&downward)super.requestDisallowInterceptTouchEvent(false);
        }
        boolean handled=super.dispatchTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){touching=false;if(refreshPending&&!contextActive)post(this::refresh);}
        return handled;
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

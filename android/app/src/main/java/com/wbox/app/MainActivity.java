package com.wbox.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private int INK,MUTED,BG,WHITE,GREEN,SURFACE,BORDER,SELECTED,GOOD_SOFT; private UiPalette palette;
    private Profiles profiles;
    private LinearLayout root,body,nav;
    private TextView status,upload,download,inbound,outbound,memory,rate,logText;
    private Button power;
    private ProgressBar powerProgress;
    private int powerState=-1;
    private boolean latencyTesting;
    private Future<?> latencyTask;
    private WaveView wave,downloadWave;
    private TextView uploadSpeed,downloadSpeed;
    private ConnectionAdapter connectionAdapter;
    private List<ConnectionRecord> displayedConnections=List.of();
    private String connectionSearch="",logSearch="",settingsSection="";
    private boolean logsPaused;
    private String exportContent;
    private final Set<String> expandedGroups=new HashSet<>();
    private boolean groupsInitialized;
    private String groupsContext="";
    private int page=0,tick;
    private long idleMemory;
    private boolean displayedRunning, importing;
    private String nodeSource="", nodeSearch="";
    private NodeAdapter nodeAdapter;
    private String selectedRow="",lastTapped="";
    private long lastTapTime;
    private AlertDialog activeForm;
    interface NodeMutation {void apply(Profiles staged)throws Exception;}
    private boolean nodesEditable(){if(importing||latencyTesting||WboxVpnService.starting||WboxVpnService.stopping){message("正在处理，请稍候");return false;}return true;}
    private void mutateNodes(NodeMutation mutation,Runnable done,java.util.function.Consumer<Throwable> failed){
        if(!nodesEditable())return;importing=true;
        io.execute(()->{try{Profiles staged=new Profiles(this).copy();mutation.apply(staged);WboxVpnService.commitNodes(this,staged.data).get();runOnUiThread(()->{importing=false;profiles.load();done.run();build();});}catch(Exception error){Throwable cause=error instanceof ExecutionException&&error.getCause()!=null?error.getCause():error;runOnUiThread(()->{importing=false;failed.accept(cause);});}});
    }
    private boolean nodeShortcut(KeyEvent event){
        if(event.getAction()==KeyEvent.ACTION_DOWN&&event.isCtrlPressed()&&!(getCurrentFocus() instanceof EditText)&&activeForm==null&&profiles!=null&&profiles.mode().equals("nodes")){
            if(event.getKeyCode()==KeyEvent.KEYCODE_V){pasteNodes();return true;}
            if(event.getKeyCode()==KeyEvent.KEYCODE_D&&!selectedRow.isEmpty()){editNode(selectedRow);return true;}
        }
        return false;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){return nodeShortcut(event)||super.dispatchKeyEvent(event);}
    @Override public boolean dispatchKeyShortcutEvent(KeyEvent event){return nodeShortcut(event)||super.dispatchKeyShortcutEvent(event);}
    private void pasteNodes(){android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=clipboard.getPrimaryClip();if(clip==null||clip.getItemCount()==0){message("剪贴板中没有文本");return;}CharSequence value=clip.getItemAt(0).getText();if(value==null){message("请复制订阅地址或节点链接");return;}String input=value.toString().trim();beginImport(input,Profiles.subscriptionAddress(input),false,null);}
    private JSONObject displayedGroups;
    private final Map<String,String> delays=new HashMap<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Runnable refresh=new Runnable(){public void run(){update();handler.postDelayed(this,1000);}};
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void applyPalette(){
        String theme=profiles.data.optString("theme","auto");
        boolean dark=theme.equals("dark")||theme.equals("auto")&&(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        palette=new UiPalette(dark);INK=palette.text;MUTED=palette.muted;BG=palette.background;WHITE=palette.card;GREEN=palette.good;SURFACE=palette.surface;BORDER=palette.border;SELECTED=palette.selected;GOOD_SOFT=palette.goodSoft;
        setTheme(dark?R.style.AppThemeDark:R.style.AppTheme);
        getWindow().setStatusBarColor(SURFACE);getWindow().setNavigationBarColor(SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);profiles=new Profiles(this);applyPalette();if(state!=null)page=state.getInt("page",0);build();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("page",page);}
    @Override protected void onResume(){super.onResume();WboxVpnService.setVisible(true);handler.post(refresh);}
    @Override protected void onPause(){WboxVpnService.setVisible(false);handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(refresh);io.shutdownNow();super.onDestroy();}
    private void build(){
        status=upload=download=inbound=outbound=memory=rate=logText=null; power=null; wave=downloadWave=null; uploadSpeed=downloadSpeed=null; nodeAdapter=null; connectionAdapter=null;
        root=column();root.setBackgroundColor(BG);setContentView(root);
        LinearLayout workspace=row();workspace.setPadding(dp(20),dp(12),dp(20),dp(4));
        for(String key:List.of("config","nodes")){Button b=button(key.equals("config")?"配置工作区":"节点工作区",profiles.mode().equals(key)?SELECTED:BG,profiles.mode().equals(key)?INK:MUTED);workspace.addView(b,new LinearLayout.LayoutParams(0,dp(38),1));b.setOnClickListener(v->{if(!editable())return;try{profiles.setWorkspace(key);save();nodeSource="";build();}catch(Exception e){failure(e);}});}
        LinearLayout header=row();header.setPadding(dp(20),dp(16),dp(20),dp(12));header.setBackgroundColor(SURFACE);workspace.setBackgroundColor(SURFACE);
        power=button("▶",SURFACE,INK);power.setTypeface(Typeface.MONOSPACE);power.setTextSize(12);power.setOnClickListener(v->toggle());FrameLayout powerFrame=new FrameLayout(this);powerFrame.addView(power,new FrameLayout.LayoutParams(-1,-1));powerProgress=new ProgressBar(this);powerProgress.setIndeterminateTintList(ColorStateList.valueOf(MUTED));FrameLayout.LayoutParams progressLayout=new FrameLayout.LayoutParams(dp(18),dp(18),Gravity.CENTER);powerFrame.addView(powerProgress,progressLayout);powerProgress.setVisibility(View.GONE);powerState=-1;header.addView(powerFrame,new LinearLayout.LayoutParams(dp(96),dp(44)));
        String[] titles={"概览",profiles.mode().equals("config")?"代理组":"节点",profiles.mode().equals("config")?"配置":"订阅","日志","设置","连接"};
        TextView title=text(titles[page],18,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setPadding(dp(16),0,0,0);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView logo=text("Wbox",22,INK);logo.setTypeface(Typeface.create("serif",Typeface.BOLD));header.addView(logo);root.addView(header);root.addView(workspace);
        body=column();body.setPadding(dp(20),dp(8),dp(20),dp(20));
        if(page==1||page==5)root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        else{ScrollView sc=new ScrollView(this);sc.setClipToPadding(false);sc.setFillViewport(true);sc.addView(body);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));}
        View line=new View(this);line.setBackgroundColor(BORDER);root.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        nav=row();nav.setBackgroundColor(WHITE);nav.setPadding(dp(10),dp(8),dp(10),dp(6));
        String[] tabs={"概览",profiles.mode().equals("config")?"组":"节点",profiles.mode().equals("config")?"配置":"订阅","连接","日志","设置"};
        int[] pageOrder={0,1,2,5,3,4};
        for(int i=0;i<tabs.length;i++){
            final int index=pageOrder[i];LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setContentDescription(tabs[i]);item.setSelected(index==page);
            IconView icon=new IconView(this,index,index==page?INK:MUTED);icon.setBackground(shape(index==page?SELECTED:WHITE,12));item.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(29)));
            TextView label=text(tabs[i],11,index==page?INK:MUTED);label.setPadding(0,dp(5),0,0);label.setGravity(Gravity.CENTER);item.addView(label);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x12000000),null,shape(WHITE,12)));item.setOnClickListener(v->{if(page!=index){page=index;build();}});item.setFocusable(true);
            nav.addView(item,new LinearLayout.LayoutParams(0,dp(54),1));
        }
        root.addView(nav);WboxVpnService.wantsGroups=page==1&&profiles.mode().equals("config");WboxVpnService.wantsConnections=page==5; if(page!=5){WboxVpnService.connectionRows=List.of();displayedConnections=List.of();}displayedRunning=WboxVpnService.running;
        switch(page){case 0:overview();break;case 1:nodesPage();break;case 2:if(profiles.mode().equals("config"))configPage();else subscriptionsPage();break;case 3:logsPage();break;case 5:connectionsPage();break;default:settingsPage();}update();
    }
    private LinearLayout column(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);return x;}
    private LinearLayout row(){LinearLayout x=new LinearLayout(this);x.setGravity(Gravity.CENTER_VERTICAL);x.setOrientation(LinearLayout.HORIZONTAL);return x;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("tnum");t.setIncludeFontPadding(false);t.setLineSpacing(dp(3),1);if(size>=24)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private GradientDrawable shape(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private GradientDrawable outlined(int color,int radius){GradientDrawable g=shape(color,radius);g.setStroke(dp(1),BORDER);return g;}
    private EditText formInput(LinearLayout parent,String label,String value,boolean multiline){
        TextView title=text(label,12,MUTED);title.setPadding(0,dp(12),0,dp(7));parent.addView(title);
        EditText input=new EditText(this);input.setTextColor(INK);input.setHintTextColor(MUTED);input.setTextSize(14);input.setBackground(outlined(WHITE,10));input.setPadding(dp(12),dp(12),dp(12),dp(12));input.setContentDescription(label);input.setText(value);input.setSingleLine(!multiline);if(multiline){input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setGravity(Gravity.TOP);input.setMinLines(4);input.setMaxLines(10);}parent.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;
    }
    private AlertDialog formDialog(String title,LinearLayout form,String action){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);form.setPadding(dp(20),0,dp(20),dp(12));scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(scroll).setNegativeButton("取消",null).setPositiveButton(action,null).create();dialog.show();activeForm=dialog;
        dialog.getWindow().setBackgroundDrawable(outlined(BG,20));dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        for(int which:new int[]{DialogInterface.BUTTON_NEGATIVE,DialogInterface.BUTTON_POSITIVE}){Button b=dialog.getButton(which);b.setAllCaps(false);b.setTextColor(which==DialogInterface.BUTTON_POSITIVE?WHITE:INK);b.setBackground(shape(which==DialogInterface.BUTTON_POSITIVE?INK:SURFACE,10));}
        dialog.setOnDismissListener(d->{if(activeForm==dialog)activeForm=null;});return dialog;
    }
    private void cardHeader(LinearLayout card,String title,Runnable add){LinearLayout header=row();TextView name=text(title,15,INK);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);header.addView(name,new LinearLayout.LayoutParams(0,-2,1));Button plus=button("＋",WHITE,INK);plus.setContentDescription("添加"+title);plus.setOnClickListener(v->add.run());header.addView(plus,new LinearLayout.LayoutParams(dp(44),dp(44)));card.addView(header);}
    private Button button(String title,int bg,int fg){Button b=new Button(this);b.setText(title);b.setTextColor(fg);b.setTextSize(13);b.setAllCaps(false);b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(14),0,dp(14),0);b.setStateListAnimator(null);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18000000),shape(bg,12),null));return b;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(18),dp(18),dp(18),dp(18));c.setBackground(outlined(WHITE,16));return c;}
    private void addCard(LinearLayout c){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);body.addView(c,lp);}
    private void gap(int n){Space s=new Space(this);body.addView(s,new LinearLayout.LayoutParams(1,dp(n)));}
    private void heading(String title,String caption){TextView t=text(title,15,INK);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);body.addView(t);if(caption!=null){TextView c=text(caption,12,MUTED);c.setPadding(0,dp(6),0,dp(16));body.addView(c);}}
    private void action(String name,Runnable task){Button b=button(name,SELECTED,INK);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.bottomMargin=dp(10);body.addView(b,lp);b.setOnClickListener(v->task.run());}
    private void toolbar(String first,Runnable one,String second,Runnable two){LinearLayout bar=row();Button a=button(first,SELECTED,INK),b=button(second,SURFACE,INK);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(46),1);lp.rightMargin=dp(10);bar.addView(a,lp);bar.addView(b,new LinearLayout.LayoutParams(0,dp(46),1));a.setOnClickListener(v->one.run());b.setOnClickListener(v->two.run());body.addView(bar);gap(18);}
    private void item(LinearLayout parent,String title,String detail,Runnable task){LinearLayout r=row();r.setPadding(0,dp(10),0,dp(10));LinearLayout copy=column();copy.addView(text(title,14,INK));if(detail!=null){TextView d=text(detail,12,MUTED);d.setPadding(0,dp(6),dp(8),0);copy.addView(d);}r.addView(copy,new LinearLayout.LayoutParams(0,-2,1));r.addView(text("›",24,MUTED));r.setMinimumHeight(dp(56));r.setOnClickListener(v->task.run());r.setFocusable(true);parent.addView(r);}
    private void pick(String key,String title,String[] values,String[] names){if(!editable())return;int selected=Arrays.asList(values).indexOf(profiles.data.optString(key));new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(names,Math.max(0,selected),(d,index)->{try{profiles.data.put(key,values[index]);if(key.equals("engine"))profiles.data.put("node_engine",values[index]);save();d.dismiss();build();}catch(Exception e){failure(e);}}).setNegativeButton("取消",null).show();}
    private void enginePicker(){if(profiles.mode().equals("config")){message("配置工作区的内核由所选配置决定");return;}pick("engine","代理内核",new String[]{"sing-box","mihomo"},new String[]{"sing-box · JSON","mihomo · YAML"});}
    private void modePicker(){pick("mode","运行模式",new String[]{"nodes","config"},new String[]{"单节点模式","配置文件模式"});}
    private void message(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void failure(Throwable e){String s=e.getMessage();WboxVpnService.log("操作失败："+s);runOnUiThread(()->message(s==null?e.toString():s));}
    private boolean editable(){if(importing||WboxVpnService.running||WboxVpnService.starting){message("请断开连接后修改内核或配置");return false;}return true;}
    private void save(){try{profiles.save();}catch(Exception e){failure(e);}}
    private String currentProfile(){return profiles.data.optString("mode","nodes").equals("config")?"配置文件模式":profiles.data.optString("selected","").isBlank()?"尚未选择节点":profiles.data.optString("selected");}
    private void overview(){
        LinearLayout totals=row();
        for(int i=0;i<2;i++){
            LinearLayout c=card();c.setPadding(dp(16),dp(18),dp(16),dp(14));c.addView(text(i==0?"↑  上传":"↓  下载",14,INK));TextView speed=text("0 B/s",24,INK);speed.setPadding(0,dp(20),0,dp(9));c.addView(speed);TextView total=text("0 B",12,MUTED);c.addView(total);WaveView chart=new WaveView(this,i==0);chart.setInk(INK);c.addView(chart,new LinearLayout.LayoutParams(-1,dp(68)));
            if(i==0){uploadSpeed=speed;upload=total;wave=chart;}else{downloadSpeed=speed;download=total;downloadWave=chart;}
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i==0)lp.rightMargin=dp(12);totals.addView(c,lp);
        }body.addView(totals);gap(14);
        LinearLayout stats=card();TextView stateLabel=text("状态",15,INK);stats.addView(stateLabel);LinearLayout state=row();state.setPadding(0,dp(18),0,dp(14));state.addView(text("内核内存",13,MUTED),new LinearLayout.LayoutParams(0,-2,1));memory=text("—",15,INK);state.addView(memory);stats.addView(state);rate=text("界面内存 —",12,MUTED);stats.addView(rate);addCard(stats);
        LinearLayout counts=card();counts.addView(text("连接",15,INK));LinearLayout c=row();c.setPadding(0,dp(18),0,0);inbound=stat(c,"入站","0");outbound=stat(c,"出站","0");counts.addView(c);counts.setOnClickListener(v->showConnections());addCard(counts);
        LinearLayout config=card();cardHeader(config,profiles.mode().equals("config")?"配置":"节点",this::importDialog);status=text("尚未连接",14,INK);config.addView(status);item(config,"当前配置",currentProfile(),()->{page=profiles.mode().equals("config")?2:1;build();});addCard(config);
    }
    private TextView total(LinearLayout parent,String label){LinearLayout c=card();c.addView(text(label,11,MUTED));TextView value=text("0 B",28,INK);value.setPadding(0,dp(14),0,0);c.addView(value);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(parent.getChildCount()==0)lp.rightMargin=dp(12);parent.addView(c,lp);return value;}
    private TextView stat(LinearLayout parent,String label,String value){LinearLayout c=column();c.addView(text(label,11,MUTED));TextView big=text(value,20,INK);big.setMaxLines(1);big.setAutoSizeTextTypeUniformWithConfiguration(12,20,1,android.util.TypedValue.COMPLEX_UNIT_SP);big.setPadding(0,dp(10),0,0);c.addView(big,new LinearLayout.LayoutParams(-1,dp(36)));parent.addView(c,new LinearLayout.LayoutParams(0,-2,1));return big;}
    private void toggle(){if(importing){message("请等待导入完成");return;}if(latencyTesting&&latencyTask!=null){latencyTask.cancel(true);if(!WboxVpnService.running){message("测速已取消");return;}}if(WboxVpnService.stopping){message("隧道正在关闭，请稍候");return;}if(WboxVpnService.running||WboxVpnService.starting){power.setText("");powerProgress.setVisibility(View.VISIBLE);startService(new Intent(this,WboxVpnService.class).setAction(WboxVpnService.STOP));return;}try{profiles.compile("check");Intent permission=VpnService.prepare(this);if(permission!=null)startActivityForResult(permission,10);else startVpn();}catch(Exception e){failure(e);}}
    private void startVpn(){power.setText("");powerProgress.setVisibility(View.VISIBLE);startForegroundService(new Intent(this,WboxVpnService.class));handler.postDelayed(this::update,100);}
    @Override protected void onActivityResult(int req,int result,Intent intent){super.onActivityResult(req,result,intent);if(req==10){if(result==RESULT_OK)startVpn();else message("需要 VPN 权限才能接管流量");}if(req==12&&result==RESULT_OK&&intent!=null&&exportContent!=null){String content=exportContent;exportContent=null;Uri target=intent.getData();io.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(target)){out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));runOnUiThread(()->message("配置已导出"));}catch(Exception e){failure(e);}});}if(req==11&&result==RESULT_OK&&intent!=null){if(profiles.mode().equals("config")?!editable():!nodesEditable())return;Uri uri=intent.getData();io.execute(()->{try(InputStream in=getContentResolver().openInputStream(uri)){String s=Profiles.readLimited(in);runOnUiThread(()->importValue(s,null));}catch(Exception e){failure(e);}});}}
    private void update(){
        tick++;
        if(power!=null){int next=WboxVpnService.stopping?3:WboxVpnService.starting?1:WboxVpnService.running?2:0;long seconds=Math.max(0,(SystemClock.elapsedRealtime()-WboxVpnService.startedAt)/1000);boolean transition=next==1||next==3;power.setText(transition?"":next==2?String.format(Locale.ROOT,"%d:%02d:%02d ■",seconds/3600,seconds/60%60,seconds%60):"▶");power.setEnabled(next!=3);power.setContentDescription(next==3?"正在停止":next==1?"正在启动，点击取消":next==2?"断开代理连接":"连接代理");powerProgress.setVisibility(transition?View.VISIBLE:View.GONE);if(powerState!=next){powerState=next;power.animate().cancel();power.setAlpha(.55f);power.animate().alpha(1f).setDuration(160).start();}}
        if(page==5&&connectionAdapter!=null&&displayedConnections!=WboxVpnService.connectionRows){displayedConnections=WboxVpnService.connectionRows;connectionAdapter.load();}

        if(page==1&&displayedRunning!=WboxVpnService.running){build();return;}
        if(page==1&&nodeAdapter!=null&&WboxVpnService.running&&displayedGroups!=WboxVpnService.proxies){nodeAdapter.load();nodeAdapter.notifyDataSetChanged();}
        if(status!=null){
            status.setText(WboxVpnService.status.equals("未连接")?"尚未连接":WboxVpnService.status);
            upload.setText(bytes(WboxVpnService.upload));download.setText(bytes(WboxVpnService.download));inbound.setText(Integer.toString(WboxVpnService.inbound));outbound.setText(Integer.toString(WboxVpnService.outbound));
            if(!WboxVpnService.running&&(tick%5==0||idleMemory==0))idleMemory=(long)Debug.getPss()*1024;
            memory.setText(WboxVpnService.running?bytes(WboxVpnService.coreMemory):"—");
            rate.setText("界面内存  "+bytes(WboxVpnService.running?WboxVpnService.appMemory:idleMemory));
            uploadSpeed.setText(bytes(WboxVpnService.upRate)+"/s");downloadSpeed.setText(bytes(WboxVpnService.downRate)+"/s");
            if(wave!=null){List<WboxVpnService.TrafficSample> history=WboxVpnService.trafficSnapshot();wave.setSamples(history);downloadWave.setSamples(history);}
        }
        if(logText!=null&&!logsPaused&&tick%2==0){String value=filteredLogs();if(value.isEmpty())value="连接后，运行日志会显示在这里。";if(!value.contentEquals(logText.getText()))logText.setText(LogStyle.color(value,palette.dark));}
    }
    
    public static String bytes(long n){if(n<1024)return n+" B";double x=n;String[] units={"B","KB","MB","GB","TB"};int i=0;while(x>=1024&&i<4){x/=1024;i++;}return String.format(Locale.ROOT,x>=100?"%.0f %s":"%.1f %s",x,units[i]);}
    private void nodesPage(){
        if(!profiles.mode().equals("config")){
            LinearLayout controls=card();cardHeader(controls,"节点",this::importDialog);Button test=button("测速",WHITE,MUTED);test.setContentDescription("测试全部节点");test.setOnClickListener(v->testAllNodes());((LinearLayout)controls.getChildAt(0)).addView(test,new LinearLayout.LayoutParams(dp(58),dp(44)));
            LinearLayout selectors=row();Button engine=button(profiles.engine()+" ▾",WHITE,INK);engine.setOnClickListener(v->enginePicker());selectors.addView(engine,new LinearLayout.LayoutParams(0,dp(44),1));controls.addView(selectors);
            Button filter=button("全部节点 ▾",WHITE,INK);List<String> labels=new ArrayList<>(List.of("全部节点","手动节点")),keys=new ArrayList<>(List.of("","manual"));JSONObject subscriptions=profiles.data.optJSONObject("subscriptions");if(subscriptions!=null){Iterator<String> it=subscriptions.keys();while(it.hasNext()){String url=it.next();keys.add(url);try{labels.add(new java.net.URL(url).getHost()+" · "+(labels.size()-1));}catch(Exception ignored){labels.add(url);}}}
            filter.setText(labels.get(Math.max(0,keys.indexOf(nodeSource)))+" ▾");filter.setSingleLine(false);selectors.addView(filter,new LinearLayout.LayoutParams(0,dp(44),1));
            filter.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("节点来源").setSingleChoiceItems(labels.toArray(new String[0]),Math.max(0,keys.indexOf(nodeSource)),(dialog,pos)->{nodeSource=keys.get(pos);filter.setText(labels.get(pos)+" ▾");if(nodeAdapter!=null){nodeAdapter.load();nodeAdapter.notifyDataSetChanged();}dialog.dismiss();}).setNegativeButton("取消",null).show());
            EditText search=new EditText(this);search.setSingleLine();search.setTextColor(INK);search.setHintTextColor(MUTED);search.setTextSize(13);search.setHint("搜索名称、地址或协议");search.setText(nodeSearch);search.setBackground(outlined(WHITE,10));search.setPadding(dp(12),0,dp(12),0);controls.addView(search,new LinearLayout.LayoutParams(-1,dp(44)));search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){nodeSearch=s.toString();if(nodeAdapter!=null){nodeAdapter.load();nodeAdapter.notifyDataSetChanged();}}public void afterTextChanged(android.text.Editable e){}});addCard(controls);
        }
        TextView hint=text(profiles.mode().equals("config")?"点击节点切换线路，右侧测试延迟。":"单击选中 · 双击启用 · 长按编辑 · Ctrl+V 导入",11,MUTED);body.addView(hint);gap(12);
        ListView list=new ListView(this);list.setDivider(null);list.setCacheColorHint(android.graphics.Color.TRANSPARENT);list.setSelector(android.R.color.transparent);list.setClipToPadding(false);
        nodeAdapter=new NodeAdapter();list.setAdapter(nodeAdapter);body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView empty=text("你的节点，从这里开始\n\n添加订阅地址或节点链接，\n连接后即可查看代理组。",14,MUTED);empty.setGravity(Gravity.CENTER);body.addView(empty,new LinearLayout.LayoutParams(-1,0,1));list.setEmptyView(empty);
    }
    private final class NodeEntry {
        String name,group,detail;boolean selected,selectable,header;
        NodeEntry(String n,String g,String d,boolean sel,boolean can,boolean h){name=n;group=g;detail=d;selected=sel;selectable=can;header=h;}
        String key(){return String.valueOf(group)+"/"+name;}
    }
    private final class NodeAdapter extends BaseAdapter {
        final List<NodeEntry> entries=new ArrayList<>();
        NodeAdapter(){load();}
        void load(){
            entries.clear();displayedGroups=WboxVpnService.proxies;
            String context=profiles.data.optString("active_config")+":"+WboxVpnService.startedAt;if(!context.equals(groupsContext)){groupsContext=context;expandedGroups.clear();groupsInitialized=false;delays.clear();}
            if(profiles.mode().equals("config")){JSONObject groups=WboxVpnService.proxies;Iterator<String> keys=groups.keys();while(keys.hasNext()){String name=keys.next();JSONObject g=groups.optJSONObject(name);if(g==null||g.optJSONArray("all")==null)continue;JSONArray all=g.optJSONArray("all");if(profiles.engine().equals("sing-box")&&name.equals("GLOBAL"))continue;if(!groupsInitialized){expandedGroups.add(name);groupsInitialized=true;}
                entries.add(new NodeEntry(name,name,g.optString("type")+"  ·  "+all.length(),false,false,true));if(expandedGroups.contains(name))for(int i=0;i<all.length();i++){String n=all.optString(i);JSONObject info=groups.optJSONObject(n);entries.add(new NodeEntry(n,name,info==null?"—":info.optString("type"),g.optString("now").equals(n),g.optString("type").equalsIgnoreCase("Selector"),false));}}}
            else try{for(Node n:profiles.nodes()){
                if(nodeSource.equals("manual")&&!n.source.isEmpty()||!nodeSource.isEmpty()&&!nodeSource.equals("manual")&&!nodeSource.equals(n.source))continue;
                if(!(n.name+" "+n.server+" "+n.protocol).toLowerCase(Locale.ROOT).contains(nodeSearch.toLowerCase(Locale.ROOT)))continue;
                NodeEntry entry=new NodeEntry(n.name,WboxVpnService.running?"Wbox":null,n.protocol.toUpperCase(Locale.ROOT)+" · "+n.server,profiles.data.optString("selected").equals(n.name),true,false);entries.add(entry);
            }}catch(Exception e){failure(e);}
        }
        public int getCount(){return entries.size();}public Object getItem(int p){return entries.get(p);}public long getItemId(int p){return p;}public int getViewTypeCount(){return 2;}public int getItemViewType(int p){return entries.get(p).header?1:0;}
        public View getView(int p,View recycled,ViewGroup parent){
            NodeEntry n=entries.get(p);
            if(n.header){LinearLayout wrap=recycled instanceof LinearLayout?(LinearLayout)recycled:column();wrap.removeAllViews();wrap.setPadding(0,dp(10),0,dp(10));LinearLayout c=card(),head=row();TextView title=text("",15,INK);title.setText(FlagLabels.text(MainActivity.this,n.name,21));title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));Button test=button("测速",WHITE,MUTED);test.setPadding(dp(4),0,dp(4),0);test.setMaxLines(1);test.setEnabled(!latencyTesting);test.setContentDescription("测试代理组 "+n.name);test.setOnClickListener(v->testGroup(n.name));head.addView(test,new LinearLayout.LayoutParams(dp(64),dp(44)));head.addView(text(expandedGroups.contains(n.name)?"⌃":"⌄",18,MUTED));c.addView(head);TextView detail=text(n.detail,12,MUTED);detail.setPadding(0,dp(8),0,dp(12));c.addView(detail);
                if(!expandedGroups.contains(n.name)){JSONObject group=WboxVpnService.proxies.optJSONObject(n.name);JSONArray all=group==null?new JSONArray():group.optJSONArray("all");List<String> values=new ArrayList<>();StringBuilder description=new StringBuilder("节点延迟：");int selected=-1;if(all!=null)for(int i=0;i<all.length();i++){String name=all.optString(i),delay=groupDelay(n.name,name);values.add(delay);if(name.equals(group.optString("now")))selected=i;description.append(name).append(' ').append(delay).append("；");}c.addView(new GroupDelayView(MainActivity.this,values,selected,palette,description.toString()),new LinearLayout.LayoutParams(-1,-2));}
                c.setOnClickListener(v->{if(!expandedGroups.remove(n.name))expandedGroups.add(n.name);load();notifyDataSetChanged();});c.setContentDescription(n.name+"，展开或折叠");c.setFocusable(true);wrap.addView(c);return wrap;}
            NodeRow holder;
            if(recycled==null){holder=new NodeRow();recycled=holder.outer;recycled.setTag(holder);}else holder=(NodeRow)recycled.getTag();
            holder.title.setText(FlagLabels.text(MainActivity.this,n.name,21));holder.detail.setText(n.detail);holder.check.setText("✓");holder.check.setVisibility(n.selected?View.VISIBLE:View.GONE);
            GradientDrawable background=outlined(n.selected||selectedRow.equals(n.name)?SELECTED:WHITE,16);if(n.selected)background.setStroke(dp(1),INK);holder.inner.setBackground(background);
            String delay=profiles.mode().equals("config")?groupDelay(n.group,n.name):delays.getOrDefault(n.key(),"测速");holder.delay.setText(delay);holder.delay.setTextColor(delay.endsWith(" ms")||delay.equals("失败")||delay.equals("超时")?GroupDelayView.tone(delay,palette):MUTED);holder.delay.setVisibility(View.VISIBLE);
            holder.delay.setEnabled(!"测试中".equals(delays.get(n.key())));holder.delay.setOnClickListener(v->testDelay(n));
            holder.inner.setOnClickListener(v->{if(profiles.mode().equals("config")){if(n.selectable)selectNode(n);else message("此代理组由内核自动选择节点");return;}long now=SystemClock.uptimeMillis();selectedRow=n.name;boolean twice=lastTapped.equals(n.name)&&now-lastTapTime<500;lastTapped=twice?"":n.name;lastTapTime=now;notifyDataSetChanged();if(twice)selectNode(n);});
            holder.inner.setOnKeyListener((v,key,event)->{if(event.getAction()==KeyEvent.ACTION_DOWN&&(key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_DPAD_CENTER)){if(n.selectable)selectNode(n);return true;}return false;});
            holder.inner.setOnLongClickListener(v->{if(profiles.mode().equals("config"))return false;selectedRow=n.name;nodeActions(n);return true;});
            holder.inner.setOnGenericMotionListener((v,event)->{if(profiles.mode().equals("nodes")&&event.getActionMasked()==MotionEvent.ACTION_BUTTON_PRESS&&(event.getButtonState()&MotionEvent.BUTTON_SECONDARY)!=0){selectedRow=n.name;nodeActions(n);return true;}return false;});
            return recycled;
        }
    }
    private final class NodeRow {
        final LinearLayout outer=column(),inner=row();final TextView check=text("○",20,INK),title=text("",14,INK),detail=text("",11,MUTED);final Button delay=button("测延迟",SURFACE,MUTED);
        NodeRow(){outer.setPadding(0,0,0,dp(8));inner.setPadding(dp(14),dp(15),dp(10),dp(15));check.setPadding(0,0,dp(12),0);inner.addView(check);LinearLayout labels=column();title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));detail.setSingleLine(true);detail.setEllipsize(TextUtils.TruncateAt.END);detail.setPadding(0,dp(8),dp(6),0);labels.addView(title);labels.addView(detail);inner.addView(labels,new LinearLayout.LayoutParams(0,-2,1));delay.setTextSize(11);delay.setPadding(dp(5),0,dp(5),0);inner.addView(delay,new LinearLayout.LayoutParams(dp(64),dp(44)));inner.setFocusable(true);outer.addView(inner);}
    }
    private void selectNode(NodeEntry n){
        if(n.group==null){if(!editable())return;try{profiles.data.put("selected",n.name);save();build();}catch(Exception e){failure(e);}return;}
        io.execute(()->{try{WboxVpnService.api().select(n.group,n.name);WboxVpnService.proxies=WboxVpnService.api().proxies();runOnUiThread(()->{try{if(n.group.equals("Wbox")){profiles.data.put("selected",n.name);save();}if(page==1)build();}catch(Exception e){failure(e);}});}catch(Exception e){failure(e);}});
    }
    private void nodeActions(NodeEntry entry){
        new AlertDialog.Builder(this).setTitle(entry.name).setItems(new String[]{"设为当前节点","编辑节点","克隆为手动节点","移除节点"},(dialog,which)->{
            if(which==0){selectNode(entry);return;}if(which==1){editNode(entry.name);return;}
            mutateNodes(staged->{List<Node> nodes=staged.nodes();int index=-1;for(int i=0;i<nodes.size();i++)if(nodes.get(i).name.equals(entry.name))index=i;if(index<0)throw new IllegalArgumentException("节点已更新，请重新选择。");if(which==2)NodeEditing.cloneNode(staged,index);else{nodes.remove(index);JSONArray keep=new JSONArray();for(Node n:nodes)keep.put(n.storage());staged.data.put("nodes",keep);if(staged.data.optString("selected").equals(entry.name))staged.data.put("selected",nodes.isEmpty()?"":nodes.get(0).name);}},()->{},this::failure);
        }).show();
    }
    private void editNode(String name){
        if(!nodesEditable())return;
        try{
            List<Node> nodes=profiles.nodes();int found=-1;for(int i=0;i<nodes.size();i++)if(nodes.get(i).name.equals(name))found=i;if(found<0)throw new IllegalArgumentException("节点不存在。");final int index=found;Node original=nodes.get(index);String revision=NodeEditing.revision(original);
            boolean nativeNode=original.nativeSingbox!=null||original.nativeMihomo!=null;
            JSONObject values=nativeNode?new JSONObject((original.nativeSingbox!=null?original.nativeSingbox:original.nativeMihomo).toString()):original.fields();
            LinearLayout form=column();Map<String,EditText> inputs=new LinkedHashMap<>();EditText nativeInput;
            if(nativeNode)nativeInput=formInput(form,original.nativeSingbox!=null?"sing-box 原生节点（JSON）":"mihomo 原生节点（JSON）",values.toString(2),true);
            else{
                nativeInput=null;
                String[][] fields={{"name","名称"},{"server","服务器地址"},{"port","端口"}};
                for(String[] field:fields)inputs.put(field[0],formInput(form,field[1],values.optString(field[0]),false));inputs.get("port").setInputType(InputType.TYPE_CLASS_NUMBER);
                TextView protocol=text("协议  ·  "+original.protocol.toUpperCase(Locale.ROOT),12,MUTED);protocol.setPadding(0,dp(14),0,0);form.addView(protocol);
                if(List.of("vmess","vless","tuic").contains(original.protocol))inputs.put("uuid",formInput(form,"UUID",original.uuid,false));
                if(List.of("http","socks","naive").contains(original.protocol))inputs.put("username",formInput(form,"用户名",original.username,false));
                if(!List.of("vmess","vless").contains(original.protocol)){EditText password=formInput(form,original.protocol.equals("wireguard")?"私钥":"密码",original.password,false);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);inputs.put("password",password);}
                if(List.of("ss","vmess").contains(original.protocol))inputs.put("cipher",formInput(form,"加密方式",original.cipher,false));
                if(original.protocol.equals("vmess"))inputs.put("alterId",formInput(form,"Alter ID",Integer.toString(original.alterId),false));
                if(List.of("vmess","vless","trojan").contains(original.protocol)){
                    Button transport=button("传输协议  ·  "+values.optString("network"),WHITE,INK);form.addView(transport);transport.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("传输协议").setItems(new String[]{"tcp","ws","grpc","http","h2"},(d,which)->{try{String network=new String[]{"tcp","ws","grpc","http","h2"}[which];values.put("network",network);transport.setText("传输协议  ·  "+network);}catch(Exception error){failure(error);}}).show());
                    inputs.put("host",formInput(form,"Host",original.host,false));inputs.put("path",formInput(form,"路径 / gRPC Service name",original.path,false));
                }
                if(original.protocol.equals("vless"))inputs.put("flow",formInput(form,"Flow",original.flow,false));
                if(!List.of("ss","wireguard").contains(original.protocol)){
                    for(String key:List.of("tls","insecure")){Switch toggle=new Switch(this);toggle.setText(key.equals("tls")?"TLS":"跳过证书验证");toggle.setTextColor(INK);toggle.setPadding(0,dp(12),0,dp(8));toggle.setChecked(values.optBoolean(key));toggle.setOnCheckedChangeListener((b,checked)->{try{values.put(key,checked);}catch(Exception error){failure(error);}});form.addView(toggle);}
                    inputs.put("sni",formInput(form,"SNI",original.sni,false));
                }
                LinearLayout advanced=column();advanced.setVisibility(View.GONE);Button reveal=button("高级参数 ▾",BG,MUTED);reveal.setOnClickListener(v->advanced.setVisibility(advanced.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));LinearLayout.LayoutParams advancedButton=new LinearLayout.LayoutParams(-1,dp(44));advancedButton.topMargin=dp(8);form.addView(reveal,advancedButton);form.addView(advanced);
                if(List.of("vmess","vless","trojan").contains(original.protocol)){inputs.put("fingerprint",formInput(advanced,"TLS 指纹",original.fingerprint,false));inputs.put("realityKey",formInput(advanced,"Reality 公钥",original.realityKey,false));inputs.put("shortId",formInput(advanced,"Reality Short ID",original.shortId,false));}
                inputs.put("options",formInput(advanced,"其他协议参数（JSON）",new JSONObject(original.options).toString(2),true));
            }
            if(!original.source.isEmpty()){TextView hint=text("此节点来自订阅，下次更新会覆盖修改。需要长期保留时，可先克隆为手动节点。",12,MUTED);hint.setPadding(0,dp(12),0,0);form.addView(hint);}
            TextView error=text("",12,0xffbd2945);error.setPadding(0,dp(8),0,0);form.addView(error);android.text.TextWatcher clearError=new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){error.setText("");}public void afterTextChanged(android.text.Editable value){}};for(EditText input:inputs.values())input.addTextChangedListener(clearError);if(nativeInput!=null)nativeInput.addTextChangedListener(clearError);AlertDialog dialog=formDialog("编辑节点",form,"保存");
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{try{
                JSONObject edited=nativeNode?new JSONObject(nativeInput.getText().toString()):new JSONObject(values.toString());
                if(!nativeNode)for(Map.Entry<String,EditText> input:inputs.entrySet()){String key=input.getKey(),value=input.getValue().getText().toString();edited.put(key,key.equals("options")?new JSONObject(value):key.equals("port")||key.equals("alterId")?Integer.parseInt(value):value);}
                NodeEditing.prepare(original,edited);dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
                mutateNodes(staged->NodeEditing.replace(staged,index,revision,edited),()->{selectedRow=edited.optString(original.nativeSingbox!=null?"tag":"name");dialog.dismiss();},e->{error.setText(e.getMessage());dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);});
            }catch(Exception e){error.setText(e.getMessage());}});
        }catch(Exception e){failure(e);}
    }
    private void testDelay(NodeEntry n){
        runLatency(List.of(n));
    }
    private String groupDelay(String group,String name){String local=delays.get(group+"/"+name);if(local!=null)return local;JSONObject info=WboxVpnService.proxies.optJSONObject(name);JSONArray history=info==null?null:info.optJSONArray("history");if(history!=null&&history.length()>0){long delay=history.optJSONObject(history.length()-1).optLong("delay");return delay>0?delay+" ms":"失败";}return "未测试";}
    private void testGroup(String name){JSONObject group=WboxVpnService.proxies.optJSONObject(name);JSONArray all=group==null?null:group.optJSONArray("all");List<NodeEntry> entries=new ArrayList<>();if(all!=null)for(int i=0;i<all.length();i++)entries.add(new NodeEntry(all.optString(i),name,"",false,false,false));runLatency(entries);}
    private void testAllNodes(){
        if(nodeAdapter==null)return;
        List<NodeEntry> entries=new ArrayList<>();for(NodeEntry n:nodeAdapter.entries)if(!n.header)entries.add(n);
        runLatency(entries);
    }
    private void runLatency(List<NodeEntry> entries){
        if(importing||latencyTesting||WboxVpnService.starting||WboxVpnService.stopping||entries.isEmpty())return;
        String url=profiles.data.optString("test_url",Profiles.TEST_URL);
        if(profiles.engine().equals("sing-box")&&!url.startsWith("https://")){message("sing-box 测速需要 HTTPS 地址，请在设置中修改。");return;}
        latencyTesting=true;for(NodeEntry n:entries)delays.put(n.key(),"测试中");if(nodeAdapter!=null)nodeAdapter.notifyDataSetChanged();
        latencyTask=io.submit(()->{try{List<String> names=new ArrayList<>();for(NodeEntry n:entries)if(!names.contains(n.name))names.add(n.name);NodeLatencyTester.run(this,profiles,names,(name,value)->runOnUiThread(()->{for(NodeEntry n:entries)if(n.name.equals(name))delays.put(n.key(),value);if(nodeAdapter!=null)nodeAdapter.notifyDataSetChanged();}));}catch(InterruptedException ignored){Thread.currentThread().interrupt();}catch(Exception e){failure(e);}finally{runOnUiThread(()->{latencyTesting=false;for(NodeEntry n:entries)if("测试中".equals(delays.get(n.key())))delays.put(n.key(),"未测试");if(nodeAdapter!=null)nodeAdapter.notifyDataSetChanged();});}});
    }
    private void importDialog(){
        boolean config=profiles.mode().equals("config");if(config?!editable():!nodesEditable())return;
        LinearLayout form=column();EditText name=config?formInput(form,"名称","",false):null;
        EditText input=formInput(form,config?"配置地址或 JSON / YAML 内容":"订阅地址或节点链接","",true);input.setHint(config?"https:// 或完整配置内容":"https://、ss://、vless:// …");
        TextView help=text(config?"远程地址用于下载并更新完整配置，本地内容直接保存在此工作区。":"订阅地址下载并更新一组节点；节点链接直接包含单个节点的参数。自动识别，多个节点每行一条。",12,MUTED);help.setPadding(0,dp(12),0,dp(10));form.addView(help);
        LinearLayout actions=row();Button paste=button("粘贴",SURFACE,INK),file=button("选择文件",SURFACE,INK);LinearLayout.LayoutParams pasteLayout=new LinearLayout.LayoutParams(0,dp(44),1);pasteLayout.rightMargin=dp(8);actions.addView(paste,pasteLayout);actions.addView(file,new LinearLayout.LayoutParams(0,dp(44),1));form.addView(actions);
        paste.setOnClickListener(v->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=clipboard.getPrimaryClip();if(clip!=null&&clip.getItemCount()>0&&clip.getItemAt(0).getText()!=null)input.setText(clip.getItemAt(0).getText());});
        TextView error=text("",12,0xffbd2945);form.addView(error);AlertDialog dialog=formDialog(config?"添加配置":"添加节点",form,"添加");file.setOnClickListener(v->{dialog.dismiss();openFile();});
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{String value=input.getText().toString().trim();if(value.isEmpty()){error.setText("请填写地址或内容。");return;}dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);performImport(value,Profiles.subscriptionAddress(value),config,null,name==null?"":name.getText().toString().trim(),dialog::dismiss,e->{error.setText(e.getMessage());dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);});});
    }
    private void performImport(String value,boolean url,boolean config,String id,String name,Runnable done,java.util.function.Consumer<Throwable> failed){
        if(!config){mutateNodes(staged->staged.importNodes(url?Profiles.fetch(value,true):value,url?value:null),done,failed);return;}
        if(!editable())return;importing=true;
        io.execute(()->{try{String content=url?Profiles.fetch(value,false):value;profiles.importConfig(content,url?value:null,id);if(!name.isEmpty()){profiles.activeConfig().put("name",name);profiles.save();}runOnUiThread(()->{importing=false;done.run();build();});}catch(Exception e){runOnUiThread(()->{importing=false;failed.accept(e);});}});
    }
    private void beginImport(String value,boolean url,boolean config,String id){performImport(value,url,config,id,"",()->message("已添加"),this::failure);}
    private void importValue(String value,String url){beginImport(value,false,profiles.mode().equals("config"),null);}
    private void refreshSubscriptions(){
        JSONObject subscriptions=profiles.data.optJSONObject("subscriptions");if(subscriptions==null||subscriptions.length()==0){message("还没有订阅");return;}
        List<String> urls=new ArrayList<>();subscriptions.keys().forEachRemaining(urls::add);
        mutateNodes(staged->{for(String url:urls)staged.importNodes(Profiles.fetch(url,true),url);},()->message("订阅已更新"),this::failure);
    }
    private void openFile(){if(profiles.mode().equals("config")?!editable():!nodesEditable())return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,11);}
    private void configPage(){
        LinearLayout header=card();cardHeader(header,"配置",this::importDialog);addCard(header);
        JSONArray configs=profiles.configs();
        if(configs.length()==0){LinearLayout c=card();c.addView(text("还没有配置",18,INK));TextView hint=text("添加远程 JSON / YAML，或导入本地文件。\n配置中的代理组、路由与 DNS 会完整保存。",13,MUTED);hint.setPadding(0,dp(14),0,dp(12));c.addView(hint);addCard(c);return;}
        for(int i=0;i<configs.length();i++){JSONObject p=configs.optJSONObject(i);if(p==null)continue;LinearLayout c=card();String id=p.optString("id");boolean active=profiles.data.optString("active_config").equals(id);String detail=p.optString("core")+" · "+(p.optString("url").isEmpty()?"本地配置":"远程配置");
            item(c,(active?"●  ":"")+p.optString("name"),detail,()->{if(!editable())return;try{profiles.activateConfig(id);save();build();}catch(Exception e){failure(e);}});
            LinearLayout actions=row();Button update=button("更新",SURFACE,INK),remove=button("移除",WHITE,MUTED);actions.addView(update,new LinearLayout.LayoutParams(0,dp(40),1));actions.addView(remove,new LinearLayout.LayoutParams(0,dp(40),1));update.setEnabled(!p.optString("url").isEmpty());update.setOnClickListener(v->beginImport(p.optString("url"),true,true,id));remove.setText("更多");remove.setOnClickListener(v->profileActions(p));c.addView(actions);addCard(c);
        }
    }
    private void subscriptionsPage(){
        LinearLayout header=card();cardHeader(header,"订阅",this::importDialog);Button updateAll=button("更新全部",SURFACE,INK);updateAll.setOnClickListener(v->refreshSubscriptions());header.addView(updateAll,new LinearLayout.LayoutParams(-1,dp(40)));addCard(header);
        JSONObject subs=profiles.data.optJSONObject("subscriptions");if(subs==null||subs.length()==0){LinearLayout c=card();c.addView(text("还没有订阅",18,INK));TextView hint=text("订阅地址用于下载并更新一组节点。\n订阅按来源分组，更新时保留手动节点的节点。",13,MUTED);hint.setPadding(0,dp(14),0,dp(12));c.addView(hint);addCard(c);return;}
        Iterator<String> keys=subs.keys();while(keys.hasNext()){String url=keys.next();LinearLayout c=card();String title=url;try{title=new java.net.URL(url).getHost();}catch(Exception ignored){}item(c,title,"点击更新节点",()->beginImport(url,true,false,null));Button remove=button("移除此订阅",WHITE,MUTED);c.addView(remove);remove.setOnClickListener(v->mutateNodes(staged->{JSONArray keep=new JSONArray();for(Node n:staged.nodes())if(!n.source.equals(url))keep.put(n.storage());staged.data.put("nodes",keep);staged.data.getJSONObject("subscriptions").remove(url);},()->{},this::failure));addCard(c);}
    }
    private void profileActions(JSONObject profile){
        new AlertDialog.Builder(this).setTitle(profile.optString("name")).setItems(new String[]{"编辑配置","导出文件","移除配置","重命名"},(dialog,which)->{
            if(which==3){LinearLayout form=column();EditText name=formInput(form,"名称",profile.optString("name"),false);name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(100)});TextView error=text("",12,0xffbd2945);form.addView(error);AlertDialog editor=formDialog("重命名配置",form,"保存");editor.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{try{String value=name.getText().toString().trim();if(value.isEmpty())throw new IllegalArgumentException("请填写配置名称。");profile.put("name",value);profiles.save();editor.dismiss();build();}catch(Exception e){error.setText(e.getMessage());}});return;}
            if(which==1){exportContent=profile.optString("content");startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,profile.optString("core").equals("sing-box")?"config.json":"config.yaml"),12);return;}
            if(!editable())return;
            if(which==0){LinearLayout form=column();EditText edit=formInput(form,"配置内容",profile.optString("content"),true);edit.setTypeface(Typeface.MONOSPACE);TextView error=text("",12,0xffbd2945);form.addView(error);AlertDialog editor=formDialog("编辑配置",form,"保存");editor.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{try{profiles.importConfig(edit.getText().toString(),profile.optString("url").isEmpty()?null:profile.optString("url"),profile.optString("id"));editor.dismiss();build();}catch(Exception e){error.setText(e.getMessage());}});return;}
            try{JSONArray keep=new JSONArray();for(int i=0;i<profiles.configs().length();i++){JSONObject p=profiles.configs().getJSONObject(i);if(!p.optString("id").equals(profile.optString("id")))keep.put(p);}profiles.data.put("configs",keep);if(profiles.data.optString("active_config").equals(profile.optString("id"))){profiles.data.remove("active_config");profiles.data.remove("config_"+profile.optString("core"));if(keep.length()>0)profiles.activateConfig(keep.getJSONObject(0).getString("id"));}save();build();}catch(Exception e){failure(e);}
        }).show();
    }
    private void logsPage(){
        EditText search=searchBox("搜索日志",logSearch,value->{logSearch=value;if(logText!=null)logText.setText(LogStyle.color(filteredLogs(),palette.dark));});body.addView(search);gap(12);
        Button pause=button(logsPaused?"继续":"暂停",SURFACE,INK);pause.setOnClickListener(v->{logsPaused=!logsPaused;pause.setText(logsPaused?"继续":"暂停");});body.addView(pause,new LinearLayout.LayoutParams(-1,dp(40)));gap(12);
        toolbar("复制日志",()->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("Wbox logs",String.join("\n",WboxVpnService.logSnapshot())));message("日志已复制");},"清空日志",()->{synchronized(WboxVpnService.logs){WboxVpnService.logs.clear();}build();});
        LinearLayout c=card();TextView label=text("最近 350 条",11,MUTED);label.setPadding(0,0,0,dp(18));c.addView(label);String value=filteredLogs();logText=text("",11,INK);logText.setText(LogStyle.color(value.isEmpty()?"连接后，运行日志会显示在这里。":value,palette.dark));logText.setTypeface(Typeface.MONOSPACE);logText.setTextIsSelectable(true);logText.setMinHeight(dp(240));c.addView(logText);addCard(c);
    }
    private void settingsPage(){
        if(settingsSection.isEmpty()){
            for(String name:List.of("外观","应用","内核","延迟测试")){LinearLayout c=card();item(c,name,null,()->{settingsSection=name;build();});addCard(c);}TextView about=text("Wbox 0.5.2\nsing-box 1.14.4 / mihomo 1.19.33\n\n国旗：Twemoji · CC-BY-4.0",12,MUTED);about.setPadding(0,dp(16),0,0);body.addView(about);return;
        }
        action("‹  返回设置",()->{settingsSection="";build();});
        if(settingsSection.equals("外观")){LinearLayout c=card();item(c,"主题",profiles.data.optString("theme","auto").equals("auto")?"跟随系统":palette.dark?"深色":"浅色",()->new AlertDialog.Builder(this).setTitle("主题").setItems(new String[]{"跟随系统","浅色","深色"},(d,which)->{try{profiles.data.put("theme",new String[]{"auto","light","dark"}[which]);save();recreate();}catch(Exception e){failure(e);}}).show());addCard(c);return;}

        if(!settingsSection.equals("延迟测试")){LinearLayout network=card();if(settingsSection.equals("内核"))item(network,"代理内核",profiles.engine(),this::enginePicker);
        Switch ipv6=new Switch(this);ipv6.setText("启用 IPv6");ipv6.setTextSize(14);ipv6.setTextColor(INK);ipv6.setPadding(0,dp(14),0,dp(14));ipv6.setChecked(profiles.data.optBoolean("ipv6",true));network.addView(ipv6);ipv6.setOnCheckedChangeListener((b,checked)->{try{profiles.data.put("ipv6",checked);save();message("下次连接生效");}catch(Exception e){failure(e);}});
        String appMode=profiles.data.optString("app_mode","all");item(network,"分应用代理",appMode.equals("all")?"全部应用":appMode.equals("include")?"只代理所选应用":"绕过所选应用",this::appsDialog);addCard(network);return;}
        LinearLayout testCard=card();TextView title=text("延迟测试",14,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);testCard.addView(title);TextView help=text("默认使用 Apple 测试页，sing-box 需要 HTTPS。",11,MUTED);help.setPadding(0,dp(8),0,dp(14));testCard.addView(help);
        EditText test=formInput(testCard,"测试地址",profiles.data.optString("test_url",Profiles.TEST_URL),false);test.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout actions=row();actions.setGravity(Gravity.END);actions.setPadding(0,dp(12),0,0);Button reset=button("恢复默认",SURFACE,INK),saveTest=button("保存",INK,WHITE);LinearLayout.LayoutParams resetLayout=new LinearLayout.LayoutParams(0,dp(44),1);resetLayout.rightMargin=dp(8);actions.addView(reset,resetLayout);actions.addView(saveTest,new LinearLayout.LayoutParams(0,dp(44),1));testCard.addView(actions);reset.setOnClickListener(v->test.setText(Profiles.TEST_URL));saveTest.setOnClickListener(v->{try{String url=test.getText().toString().trim();java.net.URI uri=new java.net.URI(url);if(!List.of("https","http").contains(uri.getScheme())||uri.getHost()==null)throw new IllegalArgumentException("请填写 HTTP / HTTPS 测试地址");profiles.data.put("test_url",url);save();message("已保存");}catch(Exception e){failure(e);}});addCard(testCard);
        LinearLayout about=card();about.addView(text("Wbox 0.5.2",17,INK));TextView info=text("mihomo 1.19.33 / sing-box 1.14.4\n\n原生界面 · 单内核运行 · 后台低频采样\nGPL-3.0 · Android ARM64",12,MUTED);info.setPadding(0,dp(10),0,0);about.addView(info);addCard(about);
    }
    private void appsDialog(){String[] modes={"全部应用","只代理所选应用","绕过所选应用"};String[] keys={"all","include","exclude"};String current=profiles.data.optString("app_mode","all");int index=Arrays.asList(keys).indexOf(current);new AlertDialog.Builder(this).setTitle("分应用模式").setSingleChoiceItems(modes,Math.max(0,index),(dialog,which)->{dialog.dismiss();try{profiles.data.put("app_mode",keys[which]);save();if(which==0){build();return;}chooseApps();}catch(Exception e){failure(e);}}).setNegativeButton("取消",null).show();}
    private void chooseApps(){List<ApplicationInfo> apps=new ArrayList<>(getPackageManager().getInstalledApplications(0));apps.removeIf(a->a.packageName.equals(getPackageName()));apps.sort(Comparator.comparing(a->getPackageManager().getApplicationLabel(a).toString(),String.CASE_INSENSITIVE_ORDER));String[] titles=new String[apps.size()];boolean[] selected=new boolean[apps.size()];Set<String> chosen=new HashSet<>();JSONArray prev=profiles.data.optJSONArray("apps");if(prev!=null)for(int i=0;i<prev.length();i++)chosen.add(prev.optString(i));for(int i=0;i<apps.size();i++){ApplicationInfo a=apps.get(i);titles[i]=getPackageManager().getApplicationLabel(a)+"\n"+a.packageName;selected[i]=chosen.contains(a.packageName);}new AlertDialog.Builder(this).setTitle("选择应用").setMultiChoiceItems(titles,selected,(d,which,checked)->selected[which]=checked).setPositiveButton("保存",(d,w)->{try{JSONArray a=new JSONArray();for(int i=0;i<apps.size();i++)if(selected[i])a.put(apps.get(i).packageName);profiles.data.put("apps",a);save();message("已保存，下次连接生效");build();}catch(Exception e){failure(e);}}).setNegativeButton("取消",null).show();}
    private void showConnections(){page=5;build();}
    private void closeConnection(String id){
        if(!WboxVpnService.running)return;
        io.execute(()->{try{if(id==null)WboxVpnService.api().closeAllConnections();else WboxVpnService.api().closeConnection(id);WboxVpnService.connectionRows=WboxVpnService.api().snapshot(true,WboxVpnService.connectionRows,1).rows;runOnUiThread(this::update);}catch(Exception e){failure(e);}});
    }
    private void connectionDetails(ConnectionRecord c){
        LinearLayout detail=card();
        for(String[] pair:new String[][]{{"网络",c.network},{"来源",c.source},{"目标",c.destination},{"入站",c.inbound},{"出站",c.outbound},{"规则",c.rule},{"上传",bytes(c.upload)},{"下载",bytes(c.download)}}){TextView label=text(pair[0],12,MUTED);label.setPadding(0,dp(12),0,dp(4));detail.addView(label);TextView value=text(pair[1].isEmpty()?"—":pair[1],14,INK);value.setTextIsSelectable(true);detail.addView(value);}
        ScrollView scroll=new ScrollView(this);scroll.addView(detail);
        new AlertDialog.Builder(this).setTitle("连接详情").setView(scroll).setNegativeButton("返回",null).setPositiveButton("关闭连接",(d,w)->closeConnection(c.id)).show();
    }
    private String filteredLogs(){List<String> found=new ArrayList<>();for(String line:WboxVpnService.logSnapshot())if(line.toLowerCase(Locale.ROOT).contains(logSearch.toLowerCase(Locale.ROOT)))found.add(line);return String.join("\n",found);}
    private EditText searchBox(String hint,String value,java.util.function.Consumer<String> changed){EditText search=new EditText(this);search.setTextSize(13);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setHint("⌕  "+hint);search.setSingleLine(true);search.setBackground(outlined(WHITE,14));search.setPadding(dp(14),dp(12),dp(14),dp(12));search.setText(value);search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){changed.accept(s.toString());}public void afterTextChanged(android.text.Editable e){}});return search;}
    private void connectionsPage(){
        LinearLayout controls=row();TextView label=text("活动连接",14,INK);controls.addView(label,new LinearLayout.LayoutParams(0,-2,1));Button close=button("关闭全部",SURFACE,INK);close.setOnClickListener(v->closeConnection(null));controls.addView(close,new LinearLayout.LayoutParams(-2,dp(38)));body.addView(controls);gap(10);
        body.addView(searchBox("搜索连接",connectionSearch,value->{connectionSearch=value;if(connectionAdapter!=null)connectionAdapter.load();}),new LinearLayout.LayoutParams(-1,dp(46)));gap(14);
        ListView list=new ListView(this);list.setDivider(null);list.setSelector(android.R.color.transparent);list.setClipToPadding(false);connectionAdapter=new ConnectionAdapter();list.setAdapter(connectionAdapter);body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView empty=text("暂无匹配的活动连接",14,MUTED);empty.setGravity(Gravity.CENTER);body.addView(empty,new LinearLayout.LayoutParams(-1,0,1));list.setEmptyView(empty);displayedConnections=WboxVpnService.connectionRows;connectionAdapter.load();
    }
    private final class ConnectionAdapter extends BaseAdapter {
        final List<ConnectionRecord> rows=new ArrayList<>();
        void load(){rows.clear();for(ConnectionRecord row:displayedConnections)if(row.matches(connectionSearch))rows.add(row);notifyDataSetChanged();}
        public int getCount(){return rows.size();}public Object getItem(int position){return rows.get(position);}public long getItemId(int position){return position;}
        public View getView(int position,View recycled,ViewGroup parent){ConnectionRow holder;if(recycled==null){holder=new ConnectionRow();recycled=holder.outer;recycled.setTag(holder);}else holder=(ConnectionRow)recycled.getTag();ConnectionRecord c=rows.get(position);holder.network.setText(c.network);holder.target.setText(c.destination);holder.up.setText("↑ "+bytes(c.upRate)+"/s    ↑ "+bytes(c.upload));holder.down.setText("↓ "+bytes(c.downRate)+"/s    ↓ "+bytes(c.download));holder.route.setText(c.inbound+"  ·  "+c.outbound);holder.inner.setContentDescription(c.network+" "+c.destination+" "+c.inbound+" "+c.outbound);holder.inner.setOnClickListener(v->connectionDetails(c));return recycled;}
    }
    private final class ConnectionRow {
        final LinearLayout outer=column(),inner=card();final TextView network=text("",11,MUTED),target=text("",14,INK),up=text("",12,MUTED),down=text("",12,MUTED),route=text("",11,MUTED);
        ConnectionRow(){outer.setPadding(0,0,0,dp(10));inner.setPadding(dp(14),dp(15),dp(14),dp(15));LinearLayout head=row();network.setPadding(dp(7),dp(4),dp(7),dp(4));network.setBackground(shape(BORDER,10));head.addView(network);target.setSingleLine(true);target.setEllipsize(TextUtils.TruncateAt.END);target.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);target.setPadding(dp(8),0,dp(6),0);head.addView(target,new LinearLayout.LayoutParams(0,-2,1));TextView active=text("活动",10,GREEN);active.setPadding(dp(7),dp(4),dp(7),dp(4));active.setBackground(shape(GOOD_SOFT,10));head.addView(active);inner.addView(head);up.setTypeface(Typeface.MONOSPACE);down.setTypeface(Typeface.MONOSPACE);up.setPadding(0,dp(14),0,0);down.setPadding(0,dp(6),0,0);inner.addView(up);inner.addView(down);route.setMaxLines(1);route.setEllipsize(TextUtils.TruncateAt.END);route.setGravity(Gravity.START);route.setPadding(0,dp(10),0,0);inner.addView(route);outer.addView(inner);}
    }
}

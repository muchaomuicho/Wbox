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
    private static final int INK=0xffe5e5e5,MUTED=0xffa1a1a1,BG=0xff1f1f1f,WHITE=0xff282828,GREEN=0xff88b49f;
    private Profiles profiles;
    private LinearLayout root,body,nav;
    private TextView status,upload,download,inbound,outbound,memory,rate,logText;
    private Button power;
    private WaveView wave,downloadWave;
    private TextView uploadSpeed,downloadSpeed;
    private ConnectionAdapter connectionAdapter;
    private List<ConnectionRecord> displayedConnections=List.of();
    private String connectionSearch="",logSearch="",settingsSection="";
    private boolean logsPaused;
    private final Set<String> expandedGroups=new HashSet<>();
    private boolean groupsInitialized;
    private int page=0,tick;
    private long idleMemory;
    private boolean displayedRunning, importing;
    private String nodeSource="", nodeSearch="";
    private NodeAdapter nodeAdapter;
    private JSONObject displayedGroups;
    private final Map<String,String> delays=new HashMap<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Runnable refresh=new Runnable(){public void run(){update();handler.postDelayed(this,1000);}};
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){super.onCreate(state);profiles=new Profiles(this);if(state!=null)page=state.getInt("page",0);build();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("page",page);}
    @Override protected void onResume(){super.onResume();WboxVpnService.visible=true;handler.post(refresh);}
    @Override protected void onPause(){WboxVpnService.visible=false;handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(refresh);io.shutdownNow();super.onDestroy();}
    private void build(){
        status=upload=download=inbound=outbound=memory=rate=logText=null; power=null; wave=downloadWave=null; uploadSpeed=downloadSpeed=null; nodeAdapter=null; connectionAdapter=null;
        root=column();root.setBackgroundColor(BG);setContentView(root);
        LinearLayout workspace=row();workspace.setPadding(dp(20),dp(12),dp(20),dp(4));
        for(String key:List.of("config","nodes")){Button b=button(key.equals("config")?"配置工作区":"节点工作区",profiles.mode().equals(key)?0xff3a3a3a:BG,profiles.mode().equals(key)?INK:MUTED);workspace.addView(b,new LinearLayout.LayoutParams(0,dp(38),1));b.setOnClickListener(v->{if(!editable())return;try{profiles.setWorkspace(key);save();nodeSource="";build();}catch(Exception e){failure(e);}});}
        LinearLayout header=row();header.setPadding(dp(20),dp(12),dp(20),dp(12));
        power=button("▶",0xff282828,INK);power.setTypeface(Typeface.MONOSPACE);power.setTextSize(12);power.setOnClickListener(v->toggle());header.addView(power,new LinearLayout.LayoutParams(dp(96),dp(44)));
        String[] titles={"概览",profiles.mode().equals("config")?"代理组":"节点",profiles.mode().equals("config")?"配置":"订阅","日志","设置","连接"};
        TextView title=text(titles[page],18,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setPadding(dp(16),0,0,0);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.wbox_icon);header.addView(logo,new LinearLayout.LayoutParams(dp(28),dp(28)));root.addView(header);root.addView(workspace);
        body=column();body.setPadding(dp(20),dp(8),dp(20),dp(20));
        if(page==1||page==5)root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        else{ScrollView sc=new ScrollView(this);sc.setClipToPadding(false);sc.setFillViewport(true);sc.addView(body);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));}
        View line=new View(this);line.setBackgroundColor(0xff353535);root.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        nav=row();nav.setBackgroundColor(WHITE);nav.setPadding(dp(10),dp(8),dp(10),dp(6));
        String[] tabs={"概览",profiles.mode().equals("config")?"组":"节点",profiles.mode().equals("config")?"配置":"订阅","连接","日志","设置"};
        int[] pageOrder={0,1,2,5,3,4};
        for(int i=0;i<tabs.length;i++){
            final int index=pageOrder[i];LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setContentDescription(tabs[i]);item.setSelected(index==page);
            IconView icon=new IconView(this,index,index==page?INK:MUTED);icon.setBackground(shape(index==page?0xff414141:WHITE,12));item.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(29)));
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
    private GradientDrawable outlined(int color,int radius){GradientDrawable g=shape(color,radius);g.setStroke(dp(1),0xff3b3b3b);return g;}
    private Button button(String title,int bg,int fg){Button b=new Button(this);b.setText(title);b.setTextColor(fg);b.setTextSize(13);b.setAllCaps(false);b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(14),0,dp(14),0);b.setStateListAnimator(null);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18000000),shape(bg,12),null));return b;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(18),dp(18),dp(18),dp(18));c.setBackground(outlined(WHITE,20));return c;}
    private void addCard(LinearLayout c){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);body.addView(c,lp);}
    private void gap(int n){Space s=new Space(this);body.addView(s,new LinearLayout.LayoutParams(1,dp(n)));}
    private void heading(String title,String caption){TextView t=text(title,15,INK);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);body.addView(t);if(caption!=null){TextView c=text(caption,12,MUTED);c.setPadding(0,dp(6),0,dp(16));body.addView(c);}}
    private void action(String name,Runnable task){Button b=button(name,0xff3a3a3a,INK);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.bottomMargin=dp(10);body.addView(b,lp);b.setOnClickListener(v->task.run());}
    private void toolbar(String first,Runnable one,String second,Runnable two){LinearLayout bar=row();Button a=button(first,0xff3a3a3a,INK),b=button(second,0xff333333,INK);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(46),1);lp.rightMargin=dp(10);bar.addView(a,lp);bar.addView(b,new LinearLayout.LayoutParams(0,dp(46),1));a.setOnClickListener(v->one.run());b.setOnClickListener(v->two.run());body.addView(bar);gap(18);}
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
            LinearLayout c=card();c.setPadding(dp(16),dp(18),dp(16),dp(14));c.addView(text(i==0?"↑  上传":"↓  下载",14,INK));TextView speed=text("0 B/s",24,INK);speed.setPadding(0,dp(20),0,dp(9));c.addView(speed);TextView total=text("0 B",12,MUTED);c.addView(total);WaveView chart=new WaveView(this,i==0);c.addView(chart,new LinearLayout.LayoutParams(-1,dp(68)));
            if(i==0){uploadSpeed=speed;upload=total;wave=chart;}else{downloadSpeed=speed;download=total;downloadWave=chart;}
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i==0)lp.rightMargin=dp(12);totals.addView(c,lp);
        }body.addView(totals);gap(14);
        LinearLayout stats=card();TextView stateLabel=text("状态",15,INK);stats.addView(stateLabel);LinearLayout state=row();state.setPadding(0,dp(18),0,dp(14));state.addView(text("内核内存",13,MUTED),new LinearLayout.LayoutParams(0,-2,1));memory=text("—",15,INK);state.addView(memory);stats.addView(state);rate=text("界面内存 —",12,MUTED);stats.addView(rate);addCard(stats);
        LinearLayout counts=card();counts.addView(text("连接",15,INK));LinearLayout c=row();c.setPadding(0,dp(18),0,0);inbound=stat(c,"入站","0");outbound=stat(c,"出站","0");counts.addView(c);counts.setOnClickListener(v->showConnections());addCard(counts);
        LinearLayout config=card();status=text("尚未连接",14,INK);config.addView(status);item(config,"当前配置",currentProfile(),()->{page=profiles.mode().equals("config")?2:1;build();});addCard(config);
    }
    private TextView total(LinearLayout parent,String label){LinearLayout c=card();c.addView(text(label,11,MUTED));TextView value=text("0 B",28,INK);value.setPadding(0,dp(14),0,0);c.addView(value);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(parent.getChildCount()==0)lp.rightMargin=dp(12);parent.addView(c,lp);return value;}
    private TextView stat(LinearLayout parent,String label,String value){LinearLayout c=column();c.addView(text(label,11,MUTED));TextView big=text(value,20,INK);big.setMaxLines(1);big.setAutoSizeTextTypeUniformWithConfiguration(12,20,1,android.util.TypedValue.COMPLEX_UNIT_SP);big.setPadding(0,dp(10),0,0);c.addView(big,new LinearLayout.LayoutParams(-1,dp(36)));parent.addView(c,new LinearLayout.LayoutParams(0,-2,1));return big;}
    private void toggle(){if(importing){message("请等待导入完成");return;}if(WboxVpnService.stopping){message("隧道正在关闭，请稍候");return;}if(WboxVpnService.running||WboxVpnService.starting){startService(new Intent(this,WboxVpnService.class).setAction(WboxVpnService.STOP));return;}try{profiles.compile("check");Intent permission=VpnService.prepare(this);if(permission!=null)startActivityForResult(permission,10);else startVpn();}catch(Exception e){failure(e);}}
    private void startVpn(){startForegroundService(new Intent(this,WboxVpnService.class));handler.postDelayed(this::update,100);}
    @Override protected void onActivityResult(int req,int result,Intent intent){super.onActivityResult(req,result,intent);if(req==10){if(result==RESULT_OK)startVpn();else message("需要 VPN 权限才能接管流量");}if(req==11&&result==RESULT_OK&&intent!=null){if(!editable())return;Uri uri=intent.getData();io.execute(()->{try(InputStream in=getContentResolver().openInputStream(uri)){String s=Profiles.readLimited(in);runOnUiThread(()->importValue(s,null));}catch(Exception e){failure(e);}});}}
    private void update(){
        tick++;
        if(power!=null){long seconds=Math.max(0,(SystemClock.elapsedRealtime()-WboxVpnService.startedAt)/1000);power.setText(WboxVpnService.running?String.format(Locale.ROOT,"%d:%02d:%02d ■",seconds/3600,seconds/60%60,seconds%60):WboxVpnService.starting?"取消":"▶");power.setEnabled(!WboxVpnService.stopping);power.setContentDescription(WboxVpnService.running?"断开代理连接":"连接代理");}
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
        if(logText!=null&&!logsPaused&&tick%2==0){String value=filteredLogs();if(value.isEmpty())value="连接后，运行日志会显示在这里。";if(!value.contentEquals(logText.getText()))logText.setText(value);}
    }
    
    public static String bytes(long n){if(n<1024)return n+" B";double x=n;String[] units={"B","KB","MB","GB","TB"};int i=0;while(x>=1024&&i<4){x/=1024;i++;}return String.format(Locale.ROOT,x>=100?"%.0f %s":"%.1f %s",x,units[i]);}
    private void nodesPage(){
        if(!profiles.mode().equals("config")){
            toolbar("＋ 导入节点",this::importDialog,"订阅管理",()->{page=2;build();});
            Spinner filter=new Spinner(this);List<String> labels=new ArrayList<>(List.of("全部节点","手动导入")),keys=new ArrayList<>(List.of("","manual"));JSONObject subscriptions=profiles.data.optJSONObject("subscriptions");if(subscriptions!=null){Iterator<String> it=subscriptions.keys();while(it.hasNext()){String url=it.next();keys.add(url);try{labels.add(new java.net.URL(url).getHost()+" · "+(labels.size()-1));}catch(Exception ignored){labels.add(url);}}}
            ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);filter.setAdapter(adapter);filter.setSelection(Math.max(0,keys.indexOf(nodeSource)));body.addView(filter,new LinearLayout.LayoutParams(-1,dp(44)));
            filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){String value=keys.get(pos);if(!value.equals(nodeSource)){nodeSource=value;if(nodeAdapter!=null){nodeAdapter.load();nodeAdapter.notifyDataSetChanged();}}}});
            EditText search=new EditText(this);search.setSingleLine();search.setTextColor(INK);search.setHintTextColor(MUTED);search.setTextSize(13);search.setHint("搜索名称、地址或协议");search.setText(nodeSearch);body.addView(search,new LinearLayout.LayoutParams(-1,dp(44)));search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){nodeSearch=s.toString();if(nodeAdapter!=null){nodeAdapter.load();nodeAdapter.notifyDataSetChanged();}}public void afterTextChanged(android.text.Editable e){}});gap(10);
        }
        TextView hint=text(WboxVpnService.running?"点击节点切换线路，右侧测试延迟。":"点击选择节点 · 长按管理 · 连接后测试延迟",11,MUTED);body.addView(hint);gap(12);
        ListView list=new ListView(this);list.setDivider(null);list.setCacheColorHint(android.graphics.Color.TRANSPARENT);list.setSelector(android.R.color.transparent);list.setClipToPadding(false);
        nodeAdapter=new NodeAdapter();list.setAdapter(nodeAdapter);body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView empty=text("你的节点，从这里开始\n\n导入订阅链接或单节点，\n连接后即可查看代理组。",14,MUTED);empty.setGravity(Gravity.CENTER);body.addView(empty,new LinearLayout.LayoutParams(-1,0,1));list.setEmptyView(empty);
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
            if(profiles.mode().equals("config")){JSONObject groups=WboxVpnService.proxies;Iterator<String> keys=groups.keys();while(keys.hasNext()){String name=keys.next();JSONObject g=groups.optJSONObject(name);if(g==null||g.optJSONArray("all")==null)continue;JSONArray all=g.optJSONArray("all");if(profiles.engine().equals("sing-box")&&name.equals("GLOBAL"))continue;if(!groupsInitialized){expandedGroups.add(name);groupsInitialized=true;}
                entries.add(new NodeEntry(name,name,g.optString("type")+"  ·  "+all.length(),false,false,true));if(expandedGroups.contains(name))for(int i=0;i<all.length();i++){String n=all.optString(i);JSONObject info=groups.optJSONObject(n);entries.add(new NodeEntry(n,name,info==null?"—":info.optString("type"),g.optString("now").equals(n),g.optString("type").equalsIgnoreCase("Selector"),false));if(info!=null){JSONArray history=info.optJSONArray("history");if(history!=null&&history.length()>0){long latency=history.optJSONObject(history.length()-1).optLong("delay");delays.put(name+"/"+n,latency>0?latency+" ms":"超时");}}}}}
            else try{for(Node n:profiles.nodes()){
                if(nodeSource.equals("manual")&&!n.source.isEmpty()||!nodeSource.isEmpty()&&!nodeSource.equals("manual")&&!nodeSource.equals(n.source))continue;
                if(!(n.name+" "+n.server+" "+n.protocol).toLowerCase(Locale.ROOT).contains(nodeSearch.toLowerCase(Locale.ROOT)))continue;
                NodeEntry entry=new NodeEntry(n.name,WboxVpnService.running?"Wbox":null,n.protocol.toUpperCase(Locale.ROOT)+" · "+n.server,profiles.data.optString("selected").equals(n.name),true,false);entries.add(entry);
            }}catch(Exception e){failure(e);}
        }
        public int getCount(){return entries.size();}public Object getItem(int p){return entries.get(p);}public long getItemId(int p){return p;}public int getViewTypeCount(){return 2;}public int getItemViewType(int p){return entries.get(p).header?1:0;}
        public View getView(int p,View recycled,ViewGroup parent){
            NodeEntry n=entries.get(p);
            if(n.header){LinearLayout wrap=recycled instanceof LinearLayout?(LinearLayout)recycled:column();wrap.removeAllViews();wrap.setPadding(0,dp(10),0,dp(10));LinearLayout c=card();TextView title=text(n.name+"   "+(expandedGroups.contains(n.name)?"⌃":"⌄"),16,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);c.addView(title);TextView detail=text(n.detail+(expandedGroups.contains(n.name)?"":"\n"+"● ".repeat(Math.min(24,Integer.parseInt(n.detail.substring(n.detail.lastIndexOf("  ·  ")+5))))),12,MUTED);detail.setPadding(0,dp(10),0,0);c.addView(detail);c.setOnClickListener(v->{if(!expandedGroups.remove(n.name))expandedGroups.add(n.name);load();notifyDataSetChanged();});c.setContentDescription(n.name+"，展开或折叠");c.setFocusable(true);wrap.addView(c);return wrap;}
            NodeRow holder;
            if(recycled==null){holder=new NodeRow();recycled=holder.outer;recycled.setTag(holder);}else holder=(NodeRow)recycled.getTag();
            holder.title.setText(FlagLabels.text(MainActivity.this,n.name,21));holder.detail.setText(n.detail);holder.check.setVisibility(View.GONE);
            GradientDrawable background=outlined(n.selected?0xff414141:0xff202020,16);if(n.selected)background.setStroke(dp(1),0xffdddddd);holder.inner.setBackground(background);
            holder.delay.setText(delays.getOrDefault(n.key(),WboxVpnService.running?"测延迟":"—"));holder.delay.setVisibility(WboxVpnService.running?View.VISIBLE:View.GONE);
            holder.delay.setEnabled(!"测试中".equals(delays.get(n.key())));holder.delay.setOnClickListener(v->testDelay(n));
            holder.inner.setOnClickListener(v->{if(n.selectable)selectNode(n);else message("此代理组由内核自动选择节点");});
            holder.inner.setOnLongClickListener(v->{if(n.group!=null)return false;new AlertDialog.Builder(MainActivity.this).setTitle("删除节点").setMessage(n.name).setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{if(!editable())return;try{JSONArray keep=new JSONArray();for(Node x:profiles.nodes())if(!x.name.equals(n.name))keep.put(x.storage());profiles.data.put("nodes",keep);if(profiles.data.optString("selected").equals(n.name))profiles.data.put("selected",keep.length()==0?"":Node.stored(keep.get(0)).name);save();build();}catch(Exception e){failure(e);}}).show();return true;});
            return recycled;
        }
    }
    private final class NodeRow {
        final LinearLayout outer=column(),inner=row();final TextView check=text("○",20,INK),title=text("",14,INK),detail=text("",11,MUTED);final Button delay=button("测延迟",0xff333333,MUTED);
        NodeRow(){outer.setPadding(0,0,0,dp(8));inner.setPadding(dp(14),dp(15),dp(10),dp(15));check.setPadding(0,0,dp(12),0);inner.addView(check);LinearLayout labels=column();title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));detail.setSingleLine(true);detail.setEllipsize(TextUtils.TruncateAt.END);detail.setPadding(0,dp(8),dp(6),0);labels.addView(title);labels.addView(detail);inner.addView(labels,new LinearLayout.LayoutParams(0,-2,1));delay.setTextSize(11);delay.setPadding(dp(5),0,dp(5),0);inner.addView(delay,new LinearLayout.LayoutParams(dp(64),dp(44)));inner.setFocusable(true);outer.addView(inner);}
    }
    private void selectNode(NodeEntry n){
        if(n.group==null){if(!editable())return;try{profiles.data.put("selected",n.name);save();build();}catch(Exception e){failure(e);}return;}
        io.execute(()->{try{WboxVpnService.api().select(n.group,n.name);WboxVpnService.proxies=WboxVpnService.api().proxies();runOnUiThread(()->{try{if(n.group.equals("Wbox")){profiles.data.put("selected",n.name);save();}if(page==1)build();}catch(Exception e){failure(e);}});}catch(Exception e){failure(e);}});
    }
    private void testDelay(NodeEntry n){
        if(!WboxVpnService.running)return;String url=profiles.data.optString("test_url",Profiles.TEST_URL);
        if(profiles.engine().equals("sing-box")&&!url.startsWith("https://")){message("sing-box 测速需要 HTTPS 地址，请在设置中修改。");return;}
        delays.put(n.key(),"测试中");if(nodeAdapter!=null)nodeAdapter.notifyDataSetChanged();
        io.execute(()->{String result;try{result=WboxVpnService.api().delay(n.name,url)+" ms";}catch(Exception e){result="失败";failure(e);}String value=result;runOnUiThread(()->{delays.put(n.key(),value);if(nodeAdapter!=null)nodeAdapter.notifyDataSetChanged();});});
    }
    private void importDialog(){
        if(!editable())return;boolean config=profiles.mode().equals("config"), subscription=page==2&&!config;
        EditText input=new EditText(this);input.setTextColor(INK);input.setHintTextColor(MUTED);input.setTextSize(13);input.setPadding(dp(20),dp(12),dp(20),dp(12));
        input.setHint(config?"粘贴远程配置地址，或完整 JSON / YAML":subscription?"粘贴 HTTP / HTTPS 节点订阅地址":"粘贴节点分享链接\nSS · VMess · VLESS · Trojan · Hysteria/2 · TUIC · AnyTLS · SOCKS · HTTP · WireGuard（NaiveProxy 仅 Windows）");input.setMinLines(4);input.setMaxLines(10);input.setGravity(Gravity.TOP);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this).setTitle(config?"添加配置":subscription?"添加订阅":"导入节点").setView(input).setNegativeButton("取消",null).setPositiveButton("导入",(d,w)->{String value=input.getText().toString().trim();boolean url=(config||subscription)&&(value.startsWith("https://")||value.startsWith("http://"));beginImport(value,url,config,null);}).show();
    }
    private void beginImport(String value,boolean url,boolean config,String id){
        if(!editable())return;importing=true;message(url?"正在下载…":"正在导入…");
        io.execute(()->{try{String content=url?Profiles.fetch(value,!config):value;String result=config?profiles.importConfig(content,url?value:null,id):profiles.importNodes(content,url?value:null);runOnUiThread(()->{importing=false;message(result);build();});}catch(Exception e){runOnUiThread(()->{importing=false;failure(e);});}});
    }
    private void importValue(String value,String url){beginImport(value,false,profiles.mode().equals("config"),null);}
    private void refreshSubscriptions(){
        if(!editable())return;JSONObject subscriptions=profiles.data.optJSONObject("subscriptions");if(subscriptions==null||subscriptions.length()==0){message("还没有订阅");return;}
        List<String> urls=new ArrayList<>();subscriptions.keys().forEachRemaining(urls::add);importing=true;message("正在更新 "+urls.size()+" 个订阅");
        io.execute(()->{int ok=0,failed=0;String error="";for(String url:urls)try{profiles.importNodes(Profiles.fetch(url,true),url);ok++;}catch(Exception e){failed++;error=e.getMessage();}String result="更新完成："+ok+" 成功，"+failed+" 失败"+(failed>0?" · "+error:"");runOnUiThread(()->{importing=false;message(result);build();});});
    }
    private void openFile(){if(!editable())return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,11);}
    private void configPage(){
        toolbar("＋ 添加远程配置",this::importDialog,"导入文件",this::openFile);
        JSONArray configs=profiles.configs();
        if(configs.length()==0){LinearLayout c=card();c.addView(text("还没有配置",18,INK));TextView hint=text("添加远程 JSON / YAML，或导入本地文件。\n配置中的代理组、路由与 DNS 会完整保存。",13,MUTED);hint.setPadding(0,dp(14),0,dp(12));c.addView(hint);addCard(c);return;}
        for(int i=0;i<configs.length();i++){JSONObject p=configs.optJSONObject(i);if(p==null)continue;LinearLayout c=card();String id=p.optString("id");boolean active=profiles.data.optString("active_config").equals(id);String detail=p.optString("core")+" · "+(p.optString("url").isEmpty()?"本地配置":"远程配置");
            item(c,(active?"●  ":"")+p.optString("name"),detail,()->{if(!editable())return;try{profiles.activateConfig(id);save();build();}catch(Exception e){failure(e);}});
            LinearLayout actions=row();Button update=button("更新",0xff383838,INK),remove=button("移除",WHITE,MUTED);actions.addView(update,new LinearLayout.LayoutParams(0,dp(40),1));actions.addView(remove,new LinearLayout.LayoutParams(0,dp(40),1));update.setEnabled(!p.optString("url").isEmpty());update.setOnClickListener(v->beginImport(p.optString("url"),true,true,id));remove.setOnClickListener(v->{if(!editable())return;try{JSONArray keep=new JSONArray();for(int x=0;x<configs.length();x++)if(!configs.getJSONObject(x).optString("id").equals(id))keep.put(configs.get(x));profiles.data.put("configs",keep);if(active){profiles.data.remove("active_config");profiles.data.remove("config_"+p.optString("core"));if(keep.length()>0)profiles.activateConfig(keep.getJSONObject(0).getString("id"));}save();build();}catch(Exception e){failure(e);}});c.addView(actions);addCard(c);
        }
    }
    private void subscriptionsPage(){
        toolbar("＋ 添加订阅",this::importDialog,"更新全部",this::refreshSubscriptions);
        JSONObject subs=profiles.data.optJSONObject("subscriptions");if(subs==null||subs.length()==0){LinearLayout c=card();c.addView(text("还没有订阅",18,INK));TextView hint=text("添加 HTTP / HTTPS 订阅链接。\n订阅按来源分组，更新时保留手动导入的节点。",13,MUTED);hint.setPadding(0,dp(14),0,dp(12));c.addView(hint);addCard(c);return;}
        Iterator<String> keys=subs.keys();while(keys.hasNext()){String url=keys.next();LinearLayout c=card();String title=url;try{title=new java.net.URL(url).getHost();}catch(Exception ignored){}item(c,title,"点击更新节点",()->beginImport(url,true,false,null));Button remove=button("移除此订阅",WHITE,MUTED);c.addView(remove);remove.setOnClickListener(v->{if(!editable())return;try{JSONArray keep=new JSONArray();for(Node n:profiles.nodes())if(!n.source.equals(url))keep.put(n.storage());profiles.data.put("nodes",keep);subs.remove(url);save();build();}catch(Exception e){failure(e);}});addCard(c);}
    }
    private void logsPage(){
        EditText search=searchBox("搜索日志",logSearch,value->{logSearch=value;if(logText!=null)logText.setText(filteredLogs());});body.addView(search);gap(12);
        Button pause=button(logsPaused?"继续":"暂停",0xff333333,INK);pause.setOnClickListener(v->{logsPaused=!logsPaused;pause.setText(logsPaused?"继续":"暂停");});body.addView(pause,new LinearLayout.LayoutParams(-1,dp(40)));gap(12);
        toolbar("复制日志",()->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("Wbox logs",String.join("\n",WboxVpnService.logSnapshot())));message("日志已复制");},"清空日志",()->{synchronized(WboxVpnService.logs){WboxVpnService.logs.clear();}build();});
        LinearLayout c=card();TextView label=text("最近 350 条",11,MUTED);label.setPadding(0,0,0,dp(18));c.addView(label);String value=filteredLogs();logText=text(value.isEmpty()?"连接后，运行日志会显示在这里。":value,11,0xffb6b6b6);logText.setTypeface(Typeface.MONOSPACE);logText.setTextIsSelectable(true);logText.setMinHeight(dp(240));c.addView(logText);addCard(c);
    }
    private void settingsPage(){
        if(settingsSection.isEmpty()){
            for(String name:List.of("应用","内核","延迟测试")){LinearLayout c=card();item(c,name,null,()->{settingsSection=name;build();});addCard(c);}TextView about=text("Wbox 0.4.0\nsing-box 1.14.4 / mihomo 1.19.33\n\n国旗：Twemoji · CC-BY-4.0",12,MUTED);about.setPadding(0,dp(16),0,0);body.addView(about);return;
        }
        action("‹  返回设置",()->{settingsSection="";build();});

        if(!settingsSection.equals("延迟测试")){LinearLayout network=card();if(settingsSection.equals("内核"))item(network,"代理内核",profiles.engine(),this::enginePicker);
        Switch ipv6=new Switch(this);ipv6.setText("启用 IPv6");ipv6.setTextSize(14);ipv6.setTextColor(INK);ipv6.setPadding(0,dp(14),0,dp(14));ipv6.setChecked(profiles.data.optBoolean("ipv6",true));network.addView(ipv6);ipv6.setOnCheckedChangeListener((b,checked)->{try{profiles.data.put("ipv6",checked);save();message("下次连接生效");}catch(Exception e){failure(e);}});
        String appMode=profiles.data.optString("app_mode","all");item(network,"分应用代理",appMode.equals("all")?"全部应用":appMode.equals("include")?"只代理所选应用":"绕过所选应用",this::appsDialog);addCard(network);return;}
        LinearLayout testCard=card();TextView title=text("延迟测试",14,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);testCard.addView(title);TextView help=text("默认使用 Apple 测试页，sing-box 需要 HTTPS。",11,MUTED);help.setPadding(0,dp(8),0,dp(14));testCard.addView(help);
        EditText test=new EditText(this);test.setTextSize(12);test.setTextColor(INK);test.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);test.setText(profiles.data.optString("test_url",Profiles.TEST_URL));test.setBackground(outlined(BG,10));test.setPadding(dp(12),dp(12),dp(12),dp(12));testCard.addView(test);
        Button saveTest=button("保存测试地址",0xff333333,INK);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(44));lp.topMargin=dp(12);testCard.addView(saveTest,lp);saveTest.setOnClickListener(v->{try{String url=test.getText().toString().trim();if(!url.startsWith("https://")&&!url.startsWith("http://"))throw new IllegalArgumentException("请填写 HTTP / HTTPS 地址");profiles.data.put("test_url",url);save();message("已保存");}catch(Exception e){failure(e);}});addCard(testCard);
        LinearLayout about=card();about.addView(text("Wbox 0.4.0",17,INK));TextView info=text("mihomo 1.19.33 / sing-box 1.14.4\n\n原生界面 · 单内核运行 · 后台低频采样\nGPL-3.0 · Android ARM64",12,MUTED);info.setPadding(0,dp(10),0,0);about.addView(info);addCard(about);
    }
    private void appsDialog(){String[] modes={"全部应用","只代理所选应用","绕过所选应用"};String[] keys={"all","include","exclude"};String current=profiles.data.optString("app_mode","all");int index=Arrays.asList(keys).indexOf(current);new AlertDialog.Builder(this).setTitle("分应用模式").setSingleChoiceItems(modes,Math.max(0,index),(dialog,which)->{dialog.dismiss();try{profiles.data.put("app_mode",keys[which]);save();if(which==0){build();return;}chooseApps();}catch(Exception e){failure(e);}}).setNegativeButton("取消",null).show();}
    private void chooseApps(){List<ApplicationInfo> apps=new ArrayList<>(getPackageManager().getInstalledApplications(0));apps.removeIf(a->a.packageName.equals(getPackageName()));apps.sort(Comparator.comparing(a->getPackageManager().getApplicationLabel(a).toString(),String.CASE_INSENSITIVE_ORDER));String[] titles=new String[apps.size()];boolean[] selected=new boolean[apps.size()];Set<String> chosen=new HashSet<>();JSONArray prev=profiles.data.optJSONArray("apps");if(prev!=null)for(int i=0;i<prev.length();i++)chosen.add(prev.optString(i));for(int i=0;i<apps.size();i++){ApplicationInfo a=apps.get(i);titles[i]=getPackageManager().getApplicationLabel(a)+"\n"+a.packageName;selected[i]=chosen.contains(a.packageName);}new AlertDialog.Builder(this).setTitle("选择应用").setMultiChoiceItems(titles,selected,(d,which,checked)->selected[which]=checked).setPositiveButton("保存",(d,w)->{try{JSONArray a=new JSONArray();for(int i=0;i<apps.size();i++)if(selected[i])a.put(apps.get(i).packageName);profiles.data.put("apps",a);save();message("已保存，下次连接生效");build();}catch(Exception e){failure(e);}}).setNegativeButton("取消",null).show();}
    private void showConnections(){page=5;build();}
    private String filteredLogs(){List<String> found=new ArrayList<>();for(String line:WboxVpnService.logSnapshot())if(line.toLowerCase(Locale.ROOT).contains(logSearch.toLowerCase(Locale.ROOT)))found.add(line);return String.join("\n",found);}
    private EditText searchBox(String hint,String value,java.util.function.Consumer<String> changed){EditText search=new EditText(this);search.setTextSize(13);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setHint("⌕  "+hint);search.setSingleLine(true);search.setBackground(outlined(WHITE,14));search.setPadding(dp(14),dp(12),dp(14),dp(12));search.setText(value);search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){changed.accept(s.toString());}public void afterTextChanged(android.text.Editable e){}});return search;}
    private void connectionsPage(){
        body.addView(searchBox("搜索连接",connectionSearch,value->{connectionSearch=value;if(connectionAdapter!=null)connectionAdapter.load();}),new LinearLayout.LayoutParams(-1,dp(46)));gap(14);
        ListView list=new ListView(this);list.setDivider(null);list.setSelector(android.R.color.transparent);list.setClipToPadding(false);connectionAdapter=new ConnectionAdapter();list.setAdapter(connectionAdapter);body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView empty=text("暂无匹配的活动连接",14,MUTED);empty.setGravity(Gravity.CENTER);body.addView(empty,new LinearLayout.LayoutParams(-1,0,1));list.setEmptyView(empty);displayedConnections=WboxVpnService.connectionRows;connectionAdapter.load();
    }
    private final class ConnectionAdapter extends BaseAdapter {
        final List<ConnectionRecord> rows=new ArrayList<>();
        void load(){rows.clear();for(ConnectionRecord row:displayedConnections)if(row.matches(connectionSearch))rows.add(row);notifyDataSetChanged();}
        public int getCount(){return rows.size();}public Object getItem(int position){return rows.get(position);}public long getItemId(int position){return position;}
        public View getView(int position,View recycled,ViewGroup parent){ConnectionRow holder;if(recycled==null){holder=new ConnectionRow();recycled=holder.outer;recycled.setTag(holder);}else holder=(ConnectionRow)recycled.getTag();ConnectionRecord c=rows.get(position);holder.network.setText(c.network);holder.target.setText(c.destination);holder.up.setText("↑ "+bytes(c.upRate)+"/s    ↑ "+bytes(c.upload));holder.down.setText("↓ "+bytes(c.downRate)+"/s    ↓ "+bytes(c.download));holder.route.setText(c.inbound+"  ·  "+c.outbound);holder.inner.setContentDescription(c.network+" "+c.destination+" "+c.inbound+" "+c.outbound);return recycled;}
    }
    private final class ConnectionRow {
        final LinearLayout outer=column(),inner=card();final TextView network=text("",11,MUTED),target=text("",14,INK),up=text("",12,MUTED),down=text("",12,MUTED),route=text("",11,MUTED);
        ConnectionRow(){outer.setPadding(0,0,0,dp(10));inner.setPadding(dp(14),dp(15),dp(14),dp(15));LinearLayout head=row();network.setPadding(dp(7),dp(4),dp(7),dp(4));network.setBackground(shape(0xff3b3b3b,10));head.addView(network);target.setSingleLine(true);target.setEllipsize(TextUtils.TruncateAt.END);target.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);target.setPadding(dp(8),0,dp(6),0);head.addView(target,new LinearLayout.LayoutParams(0,-2,1));TextView active=text("活动",10,GREEN);active.setPadding(dp(7),dp(4),dp(7),dp(4));active.setBackground(shape(0xff34403a,10));head.addView(active);inner.addView(head);up.setTypeface(Typeface.MONOSPACE);down.setTypeface(Typeface.MONOSPACE);up.setPadding(0,dp(14),0,0);down.setPadding(0,dp(6),0,0);inner.addView(up);inner.addView(down);route.setMaxLines(1);route.setEllipsize(TextUtils.TruncateAt.END);route.setGravity(Gravity.LEFT);route.setPadding(0,dp(10),0,0);inner.addView(route);outer.addView(inner);}
    }
}

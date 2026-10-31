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
    private static final int INK=0xff1b1e23,MUTED=0xff7c838e,BG=0xfff6f7f9,WHITE=0xffffffff,GREEN=0xff15845b;
    private Profiles profiles;
    private LinearLayout root,body,nav;
    private TextView status,upload,download,inbound,outbound,memory,rate,logText;
    private Button power;
    private WaveView wave;
    private int page=0,tick;
    private long idleMemory;
    private boolean displayedRunning;
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
        status=upload=download=inbound=outbound=memory=rate=logText=null; power=null; wave=null; nodeAdapter=null;
        root=column();root.setBackgroundColor(BG);setContentView(root);
        LinearLayout header=row();header.setPadding(dp(22),dp(16),dp(22),dp(14));
        String[] titles={"概览","代理节点","配置与订阅","运行日志","设置"};
        LinearLayout words=column();TextView title=text(titles[page],28,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);words.addView(title);
        String[] captions={"自由连接，简单一点。","找到适合你的连接。","以你习惯的方式连接。","了解内核正在做什么。","让 Wbox 按你的习惯运行。"};
        TextView subtitle=text(captions[page],12,MUTED);subtitle.setPadding(0,dp(5),0,0);words.addView(subtitle);
        header.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.wbox_icon);header.addView(logo,new LinearLayout.LayoutParams(dp(34),dp(34)));root.addView(header);
        body=column();body.setPadding(dp(20),dp(8),dp(20),dp(20));
        if(page==1)root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        else{ScrollView sc=new ScrollView(this);sc.setClipToPadding(false);sc.setFillViewport(true);sc.addView(body);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));}
        View line=new View(this);line.setBackgroundColor(0xffe9ecf0);root.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        nav=row();nav.setBackgroundColor(WHITE);nav.setPadding(dp(10),dp(8),dp(10),dp(6));
        String[] tabs={"概览","节点","配置","日志","设置"};
        for(int i=0;i<tabs.length;i++){
            final int index=i;LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setContentDescription(tabs[i]);item.setSelected(i==page);
            IconView icon=new IconView(this,i,i==page?WHITE:MUTED);icon.setBackground(shape(i==page?INK:WHITE,12));item.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(29)));
            TextView label=text(tabs[i],11,i==page?INK:MUTED);label.setPadding(0,dp(5),0,0);label.setGravity(Gravity.CENTER);item.addView(label);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x12000000),null,shape(WHITE,12)));item.setOnClickListener(v->{if(page!=index){page=index;build();}});item.setFocusable(true);
            nav.addView(item,new LinearLayout.LayoutParams(0,dp(54),1));
        }
        root.addView(nav);displayedRunning=WboxVpnService.running;
        switch(page){case 0:overview();break;case 1:nodesPage();break;case 2:configPage();break;case 3:logsPage();break;default:settingsPage();}update();
    }
    private LinearLayout column(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);return x;}
    private LinearLayout row(){LinearLayout x=new LinearLayout(this);x.setGravity(Gravity.CENTER_VERTICAL);x.setOrientation(LinearLayout.HORIZONTAL);return x;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("tnum");t.setIncludeFontPadding(false);t.setLineSpacing(dp(3),1);if(size>=24)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private GradientDrawable shape(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private GradientDrawable outlined(int color,int radius){GradientDrawable g=shape(color,radius);g.setStroke(dp(1),0xffe8ebef);return g;}
    private Button button(String title,int bg,int fg){Button b=new Button(this);b.setText(title);b.setTextColor(fg);b.setTextSize(13);b.setAllCaps(false);b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(14),0,dp(14),0);b.setStateListAnimator(null);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18000000),shape(bg,12),null));return b;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(18),dp(18),dp(18),dp(18));c.setBackground(outlined(WHITE,20));return c;}
    private void addCard(LinearLayout c){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(14);body.addView(c,lp);}
    private void gap(int n){Space s=new Space(this);body.addView(s,new LinearLayout.LayoutParams(1,dp(n)));}
    private void heading(String title,String caption){TextView t=text(title,15,INK);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);body.addView(t);if(caption!=null){TextView c=text(caption,12,MUTED);c.setPadding(0,dp(6),0,dp(16));body.addView(c);}}
    private void action(String name,Runnable task){Button b=button(name,INK,WHITE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(48));lp.bottomMargin=dp(10);body.addView(b,lp);b.setOnClickListener(v->task.run());}
    private void toolbar(String first,Runnable one,String second,Runnable two){LinearLayout bar=row();Button a=button(first,INK,WHITE),b=button(second,0xffeceef2,INK);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(46),1);lp.rightMargin=dp(10);bar.addView(a,lp);bar.addView(b,new LinearLayout.LayoutParams(0,dp(46),1));a.setOnClickListener(v->one.run());b.setOnClickListener(v->two.run());body.addView(bar);gap(18);}
    private void item(LinearLayout parent,String title,String detail,Runnable task){LinearLayout r=row();r.setPadding(0,dp(10),0,dp(10));LinearLayout copy=column();copy.addView(text(title,14,INK));if(detail!=null){TextView d=text(detail,12,MUTED);d.setPadding(0,dp(6),dp(8),0);copy.addView(d);}r.addView(copy,new LinearLayout.LayoutParams(0,-2,1));r.addView(text("›",24,MUTED));r.setMinimumHeight(dp(56));r.setOnClickListener(v->task.run());r.setFocusable(true);parent.addView(r);}
    private void pick(String key,String title,String[] values,String[] names){if(!editable())return;int selected=Arrays.asList(values).indexOf(profiles.data.optString(key));new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(names,Math.max(0,selected),(d,index)->{try{profiles.data.put(key,values[index]);save();d.dismiss();build();}catch(Exception e){failure(e);}}).setNegativeButton("取消",null).show();}
    private void enginePicker(){pick("engine","代理内核",new String[]{"sing-box","mihomo"},new String[]{"sing-box · JSON","mihomo · YAML"});}
    private void modePicker(){pick("mode","运行模式",new String[]{"nodes","config"},new String[]{"单节点模式","配置文件模式"});}
    private void message(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void failure(Throwable e){String s=e.getMessage();WboxVpnService.log("操作失败："+s);runOnUiThread(()->message(s==null?e.toString():s));}
    private boolean editable(){if(WboxVpnService.running||WboxVpnService.starting){message("请断开连接后修改内核或配置");return false;}return true;}
    private void save(){try{profiles.save();}catch(Exception e){failure(e);}}
    private String currentProfile(){return profiles.data.optString("mode","nodes").equals("config")?"配置文件模式":profiles.data.optString("selected","").isBlank()?"尚未选择节点":profiles.data.optString("selected");}
    private void overview(){
        LinearLayout hero=row();hero.setPadding(dp(22),dp(20),dp(20),dp(20));hero.setBackground(shape(INK,24));
        LinearLayout state=column();state.addView(text("连接状态",12,0xffa2a9b4));status=text("尚未连接",26,WHITE);status.setPadding(0,dp(12),0,dp(10));state.addView(status);TextView current=text(profiles.engine()+" · "+currentProfile()+"  ›",11,0xffa2a9b4);current.setSingleLine(true);current.setEllipsize(TextUtils.TruncateAt.END);state.addView(current);state.setContentDescription("选择当前配置");state.setOnClickListener(v->{page=profiles.mode().equals("config")?2:1;build();});hero.addView(state,new LinearLayout.LayoutParams(0,-2,1));
        power=button("连接",GREEN,WHITE);power.setTextSize(14);hero.addView(power,new LinearLayout.LayoutParams(dp(76),dp(58)));power.setOnClickListener(v->toggle());body.addView(hero);gap(14);
        LinearLayout totals=row();upload=total(totals,"↑  本次上传");download=total(totals,"↓  本次下载");body.addView(totals);gap(14);
        LinearLayout graph=card();LinearLayout caption=row();TextView label=text("流量趋势",14,INK);label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);caption.addView(label,new LinearLayout.LayoutParams(0,-2,1));caption.addView(text("─ 下载  ┄ 上传",10,MUTED));graph.addView(caption);
        wave=new WaveView(this);graph.addView(wave,new LinearLayout.LayoutParams(-1,dp(118)));rate=text("↑ 0 B/s     ↓ 0 B/s",11,MUTED);graph.addView(rate);addCard(graph);
        LinearLayout stats=card(),counts=row();stats.setPadding(dp(18),dp(16),dp(18),dp(16));inbound=stat(counts,"入站连接","0");outbound=stat(counts,"出站连接","0");memory=stat(counts,"内存占用","—");stats.addView(counts);Button detail=button("查看活动连接  →",0xfff2f4f7,INK);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(42));lp.topMargin=dp(12);stats.addView(detail,lp);detail.setOnClickListener(v->showConnections());addCard(stats);
    }
    private TextView total(LinearLayout parent,String label){LinearLayout c=card();c.addView(text(label,11,MUTED));TextView value=text("0 B",28,INK);value.setPadding(0,dp(14),0,0);c.addView(value);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(parent.getChildCount()==0)lp.rightMargin=dp(12);parent.addView(c,lp);return value;}
    private TextView stat(LinearLayout parent,String label,String value){LinearLayout c=column();c.addView(text(label,11,MUTED));TextView big=text(value,20,INK);big.setMaxLines(1);big.setAutoSizeTextTypeUniformWithConfiguration(12,20,1,android.util.TypedValue.COMPLEX_UNIT_SP);big.setPadding(0,dp(10),0,0);c.addView(big,new LinearLayout.LayoutParams(-1,dp(36)));parent.addView(c,new LinearLayout.LayoutParams(0,-2,1));return big;}
    private void toggle(){if(WboxVpnService.stopping){message("隧道正在关闭，请稍候");return;}if(WboxVpnService.running||WboxVpnService.starting){startService(new Intent(this,WboxVpnService.class).setAction(WboxVpnService.STOP));return;}try{profiles.compile("check");Intent permission=VpnService.prepare(this);if(permission!=null)startActivityForResult(permission,10);else startVpn();}catch(Exception e){failure(e);}}
    private void startVpn(){startForegroundService(new Intent(this,WboxVpnService.class));handler.postDelayed(this::update,100);}
    @Override protected void onActivityResult(int req,int result,Intent intent){super.onActivityResult(req,result,intent);if(req==10){if(result==RESULT_OK)startVpn();else message("需要 VPN 权限才能接管流量");}if(req==11&&result==RESULT_OK&&intent!=null){if(!editable())return;Uri uri=intent.getData();io.execute(()->{try(InputStream in=getContentResolver().openInputStream(uri)){String s=Profiles.readLimited(in);runOnUiThread(()->importValue(s,null));}catch(Exception e){failure(e);}});}}
    private void update(){
        tick++;
        if(page==1&&displayedRunning!=WboxVpnService.running){build();return;}
        if(page==1&&nodeAdapter!=null&&WboxVpnService.running&&displayedGroups!=WboxVpnService.proxies){nodeAdapter.load();nodeAdapter.notifyDataSetChanged();}
        if(status!=null){
            status.setText(WboxVpnService.status.equals("未连接")?"尚未连接":WboxVpnService.status);
            power.setText(WboxVpnService.running?"断开":WboxVpnService.starting?"取消":"连接");power.setEnabled(!WboxVpnService.stopping);
            power.setContentDescription(WboxVpnService.running?"断开代理连接":"连接代理");
            upload.setText(bytes(WboxVpnService.upload));download.setText(bytes(WboxVpnService.download));inbound.setText(Integer.toString(WboxVpnService.inbound));outbound.setText(Integer.toString(WboxVpnService.outbound));
            if(!WboxVpnService.running&&(tick%5==0||idleMemory==0))idleMemory=(long)Debug.getPss()*1024;
            memory.setText(WboxVpnService.running?bytes(WboxVpnService.memory):bytes(idleMemory));
            rate.setText("↑ "+bytes(WboxVpnService.upRate)+"/s     ↓ "+bytes(WboxVpnService.downRate)+"/s");
            if(wave!=null)wave.setSamples(WboxVpnService.trafficSnapshot());
        }
        if(logText!=null&&tick%2==0){String value=String.join("\n",WboxVpnService.logSnapshot());if(value.isEmpty())value="连接后，运行日志会显示在这里。";if(!value.contentEquals(logText.getText()))logText.setText(value);}
    }
    
    public static String bytes(long n){if(n<1024)return n+" B";double x=n;String[] units={"B","KB","MB","GB","TB"};int i=0;while(x>=1024&&i<4){x/=1024;i++;}return String.format(Locale.ROOT,x>=100?"%.0f %s":"%.1f %s",x,units[i]);}
    private void nodesPage(){
        toolbar("＋ 导入节点",this::importDialog,"更新订阅",this::refreshSubscriptions);
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
            if(WboxVpnService.running){JSONObject groups=WboxVpnService.proxies;Iterator<String> keys=groups.keys();while(keys.hasNext()){String name=keys.next();JSONObject g=groups.optJSONObject(name);if(g==null||g.optJSONArray("all")==null)continue;JSONArray all=g.optJSONArray("all");entries.add(new NodeEntry(name,name,g.optString("type")+" · "+all.length()+" 个节点",false,false,true));for(int i=0;i<all.length();i++){String n=all.optString(i);entries.add(new NodeEntry(n,name,name,g.optString("now").equals(n),g.optString("type").equalsIgnoreCase("Selector"),false));}}}
            else try{for(Node n:profiles.nodes())entries.add(new NodeEntry(n.name,null,n.protocol.toUpperCase(Locale.ROOT)+" · "+n.server,profiles.data.optString("selected").equals(n.name),true,false));}catch(Exception e){failure(e);}
        }
        public int getCount(){return entries.size();}public Object getItem(int p){return entries.get(p);}public long getItemId(int p){return p;}public int getViewTypeCount(){return 2;}public int getItemViewType(int p){return entries.get(p).header?1:0;}
        public View getView(int p,View recycled,ViewGroup parent){
            NodeEntry n=entries.get(p);
            if(n.header){TextView t=recycled instanceof TextView?(TextView)recycled:text("",13,INK);t.setText(n.name+"  ·  "+n.detail);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setPadding(dp(2),dp(16),0,dp(12));return t;}
            NodeRow holder;
            if(recycled==null){holder=new NodeRow();recycled=holder.outer;recycled.setTag(holder);}else holder=(NodeRow)recycled.getTag();
            holder.title.setText(n.name);holder.detail.setText(n.selected?"当前使用  ·  "+n.detail:n.detail);holder.check.setText(n.selected?"●":"○");
            GradientDrawable background=outlined(n.selected?0xfff0f2f5:WHITE,16);if(n.selected)background.setStroke(dp(1),0xff787f89);holder.inner.setBackground(background);
            holder.delay.setText(delays.getOrDefault(n.key(),WboxVpnService.running?"测延迟":"—"));holder.delay.setVisibility(WboxVpnService.running?View.VISIBLE:View.GONE);
            holder.delay.setEnabled(!"测试中".equals(delays.get(n.key())));holder.delay.setOnClickListener(v->testDelay(n));
            holder.inner.setOnClickListener(v->{if(n.selectable)selectNode(n);else message("此代理组由内核自动选择节点");});
            holder.inner.setOnLongClickListener(v->{if(n.group!=null)return false;new AlertDialog.Builder(MainActivity.this).setTitle("删除节点").setMessage(n.name).setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{if(!editable())return;try{JSONArray keep=new JSONArray();for(Node x:profiles.nodes())if(!x.name.equals(n.name))keep.put(x.raw);profiles.data.put("nodes",keep);if(profiles.data.optString("selected").equals(n.name))profiles.data.put("selected",keep.length()==0?"":Node.parse(keep.getString(0)).name);save();build();}catch(Exception e){failure(e);}}).show();return true;});
            return recycled;
        }
    }
    private final class NodeRow {
        final LinearLayout outer=column(),inner=row();final TextView check=text("○",20,INK),title=text("",14,INK),detail=text("",11,MUTED);final Button delay=button("测延迟",0xfff1f3f6,MUTED);
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
    private void importDialog(){if(!editable())return;EditText input=new EditText(this);input.setTextSize(13);input.setHint("https://订阅地址 或 ss://、vmess://、vless:// 等分享链接\n也可粘贴 JSON / YAML 配置");input.setMinLines(4);input.setMaxLines(10);input.setGravity(Gravity.TOP);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);new AlertDialog.Builder(this).setTitle("导入到 Wbox").setView(input).setNegativeButton("取消",null).setPositiveButton("导入",(d,w)->{String value=input.getText().toString().trim();if(value.startsWith("https://")||value.startsWith("http://")){message("正在获取订阅");io.execute(()->{try{String fetched=Profiles.fetch(value);runOnUiThread(()->importValue(fetched,value));}catch(Exception e){failure(e);}});}else importValue(value,null);}).show();}
    private void importValue(String value,String url){if(!editable())return;try{message(profiles.importText(value,url));build();}catch(Exception e){failure(e);}}
    private void refreshSubscriptions(){if(!editable())return;Set<String> urls=new LinkedHashSet<>();JSONObject s=profiles.data.optJSONObject("subscriptions");if(s!=null)s.keys().forEachRemaining(urls::add);for(String engine:List.of("sing-box","mihomo")){String u=profiles.data.optString("config_url_"+engine);if(!u.isBlank())urls.add(u);}if(urls.isEmpty()){message("没有保存的订阅地址");return;}message("正在更新 "+urls.size()+" 个订阅");io.execute(()->{for(String url:urls){try{String content=Profiles.fetch(url);runOnUiThread(()->importValue(content,url));}catch(Exception e){failure(e);}}});}
    private void configPage(){
        LinearLayout mode=card();item(mode,"运行模式",profiles.data.optString("mode","nodes").equals("config")?"配置文件模式":"单节点模式",this::modePicker);item(mode,"代理内核",profiles.engine(),this::enginePicker);addCard(mode);
        toolbar("从文件导入",()->{if(!editable())return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,11);},"粘贴或订阅",this::importDialog);
        LinearLayout source=card();TextView label=text(profiles.engine().equals("sing-box")?"JSON 配置":"YAML 配置",14,INK);label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);source.addView(label);
        EditText editor=new EditText(this);editor.setTextSize(12);editor.setTextColor(INK);editor.setHintTextColor(MUTED);editor.setHint(profiles.engine().equals("sing-box")?"在这里粘贴 sing-box JSON 配置":"在这里粘贴 mihomo YAML 配置");editor.setTypeface(Typeface.MONOSPACE);editor.setGravity(Gravity.TOP);editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);editor.setText(profiles.data.optString("config_"+profiles.engine()));editor.setBackground(shape(0xfff6f7f9,10));editor.setPadding(dp(12),dp(12),dp(12),dp(12));LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(240));ep.topMargin=dp(14);source.addView(editor,ep);
        Button saveConfig=button("保存并使用",INK,WHITE);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(46));bp.topMargin=dp(14);source.addView(saveConfig,bp);saveConfig.setOnClickListener(v->{if(!editable())return;try{String cfg=editor.getText().toString().trim();if(cfg.isEmpty())throw new IllegalArgumentException("请先输入配置内容");if(profiles.engine().equals("sing-box"))new JSONObject(cfg);else Profiles.yamlMap(cfg);profiles.data.put("config_"+profiles.engine(),cfg).put("mode","config");save();message("配置已保存");build();}catch(Exception e){failure(e);}});addCard(source);
        LinearLayout subscriptions=card();JSONObject saved=profiles.data.optJSONObject("subscriptions");item(subscriptions,"已保存的订阅",(saved==null?0:saved.length())+" 个节点订阅 · 点击更新",this::refreshSubscriptions);addCard(subscriptions);
    }
    private void logsPage(){
        toolbar("复制日志",()->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("Wbox logs",String.join("\n",WboxVpnService.logSnapshot())));message("日志已复制");},"清空日志",()->{synchronized(WboxVpnService.logs){WboxVpnService.logs.clear();}build();});
        LinearLayout c=card();TextView label=text("最近 350 条",11,MUTED);label.setPadding(0,0,0,dp(18));c.addView(label);String value=String.join("\n",WboxVpnService.logSnapshot());logText=text(value.isEmpty()?"连接后，运行日志会显示在这里。":value,11,0xff606975);logText.setTypeface(Typeface.MONOSPACE);logText.setTextIsSelectable(true);logText.setMinHeight(dp(240));c.addView(logText);addCard(c);
    }
    private void settingsPage(){
        LinearLayout network=card();item(network,"代理内核",profiles.engine(),this::enginePicker);
        Switch ipv6=new Switch(this);ipv6.setText("启用 IPv6");ipv6.setTextSize(14);ipv6.setTextColor(INK);ipv6.setPadding(0,dp(14),0,dp(14));ipv6.setChecked(profiles.data.optBoolean("ipv6",true));network.addView(ipv6);ipv6.setOnCheckedChangeListener((b,checked)->{try{profiles.data.put("ipv6",checked);save();message("下次连接生效");}catch(Exception e){failure(e);}});
        String appMode=profiles.data.optString("app_mode","all");item(network,"分应用代理",appMode.equals("all")?"全部应用":appMode.equals("include")?"只代理所选应用":"绕过所选应用",this::appsDialog);addCard(network);
        LinearLayout testCard=card();TextView title=text("延迟测试",14,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);testCard.addView(title);TextView help=text("默认使用 Apple 测试页，sing-box 需要 HTTPS。",11,MUTED);help.setPadding(0,dp(8),0,dp(14));testCard.addView(help);
        EditText test=new EditText(this);test.setTextSize(12);test.setTextColor(INK);test.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);test.setText(profiles.data.optString("test_url",Profiles.TEST_URL));test.setBackground(outlined(BG,10));test.setPadding(dp(12),dp(12),dp(12),dp(12));testCard.addView(test);
        Button saveTest=button("保存测试地址",0xffeceef2,INK);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(44));lp.topMargin=dp(12);testCard.addView(saveTest,lp);saveTest.setOnClickListener(v->{try{String url=test.getText().toString().trim();if(!url.startsWith("https://")&&!url.startsWith("http://"))throw new IllegalArgumentException("请填写 HTTP / HTTPS 地址");profiles.data.put("test_url",url);save();message("已保存");}catch(Exception e){failure(e);}});addCard(testCard);
        LinearLayout about=card();about.addView(text("Wbox 0.2.0",17,INK));TextView info=text("mihomo 1.19.33 / sing-box 1.14.4\n\n原生界面 · 单内核运行 · 后台低频采样\nGPL-3.0 · Android ARM64",12,MUTED);info.setPadding(0,dp(10),0,0);about.addView(info);addCard(about);
    }
    private void appsDialog(){String[] modes={"全部应用","只代理所选应用","绕过所选应用"};String[] keys={"all","include","exclude"};String current=profiles.data.optString("app_mode","all");int index=Arrays.asList(keys).indexOf(current);new AlertDialog.Builder(this).setTitle("分应用模式").setSingleChoiceItems(modes,Math.max(0,index),(dialog,which)->{dialog.dismiss();try{profiles.data.put("app_mode",keys[which]);save();if(which==0){build();return;}chooseApps();}catch(Exception e){failure(e);}}).setNegativeButton("取消",null).show();}
    private void chooseApps(){List<ApplicationInfo> apps=new ArrayList<>(getPackageManager().getInstalledApplications(0));apps.removeIf(a->a.packageName.equals(getPackageName()));apps.sort(Comparator.comparing(a->getPackageManager().getApplicationLabel(a).toString(),String.CASE_INSENSITIVE_ORDER));String[] titles=new String[apps.size()];boolean[] selected=new boolean[apps.size()];Set<String> chosen=new HashSet<>();JSONArray prev=profiles.data.optJSONArray("apps");if(prev!=null)for(int i=0;i<prev.length();i++)chosen.add(prev.optString(i));for(int i=0;i<apps.size();i++){ApplicationInfo a=apps.get(i);titles[i]=getPackageManager().getApplicationLabel(a)+"\n"+a.packageName;selected[i]=chosen.contains(a.packageName);}new AlertDialog.Builder(this).setTitle("选择应用").setMultiChoiceItems(titles,selected,(d,which,checked)->selected[which]=checked).setPositiveButton("保存",(d,w)->{try{JSONArray a=new JSONArray();for(int i=0;i<apps.size();i++)if(selected[i])a.put(apps.get(i).packageName);profiles.data.put("apps",a);save();message("已保存，下次连接生效");build();}catch(Exception e){failure(e);}}).setNegativeButton("取消",null).show();}
    private void showConnections(){JSONArray cs=WboxVpnService.connections.optJSONArray("connections");StringBuilder s=new StringBuilder();if(cs!=null)for(int i=0;i<Math.min(cs.length(),120);i++){JSONObject c=cs.optJSONObject(i);JSONObject m=c==null?null:c.optJSONObject("metadata");if(m==null)continue;s.append(m.optString("network")).append(" ").append(m.optString("host",m.optString("destinationIP"))).append(':').append(m.optString("destinationPort")).append("\n").append(m.optString("sourceIP")).append(" → ").append(c.optJSONArray("chains")).append("\n↑ ").append(bytes(c.optLong("upload"))).append("  ↓ ").append(bytes(c.optLong("download"))).append("\n\n");}new AlertDialog.Builder(this).setTitle("活动连接 · "+WboxVpnService.inbound).setMessage(s.length()==0?"当前没有活动连接":s.toString()).setPositiveButton("关闭",null).show();}
}

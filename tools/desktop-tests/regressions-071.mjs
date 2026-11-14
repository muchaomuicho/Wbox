import {_electron as electron} from 'playwright';
import {mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {pathToFileURL as fileURL} from 'node:url';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'../..'),data=join(root,'.tools/desktop-071-runtime');
const version=JSON.parse(await readFile(join(root,'desktop/package.json'),'utf8')).version;
await mkdir(data,{recursive:true});
const checks=[],hits=[0,0],errors=[],rejectProxy=[false,false];
let app,page,tray,failure=null;
const check=(ok,name)=>{assert(ok,name);checks.push(name);console.log('PASS',name);};
const wait=async(fn,timeout=30000)=>{const end=Date.now()+timeout;while(Date.now()<end){if(await fn())return;await new Promise(r=>setTimeout(r,100));}throw Error('Timed out');};
const listen=server=>new Promise(r=>server.listen(0,'127.0.0.1',r));
const cert=await readFile(join(root,'tools/fixtures/latency-localhost-cert.pem'),'utf8'),key=await readFile(join(root,'tools/fixtures/latency-localhost-key.pem'),'utf8');
const tls=https.createServer({cert,key},async(req,res)=>{await new Promise(r=>setTimeout(r,60));res.end('TLS');});await listen(tls);
const origins=await Promise.all([0,1].map(async i=>{const s=http.createServer(async(req,res)=>{await new Promise(r=>setTimeout(r,60));res.end(`WBOX-${i}`);});await listen(s);return s;}));
const proxies=await Promise.all([0,1].map(async i=>{const s=http.createServer(async(req,res)=>{hits[i]++;await new Promise(r=>setTimeout(r,60));if(rejectProxy[i])res.writeHead(502);res.end(`WBOX-${i}`);});s.on('connect',(req,client,head)=>{hits[i]++;if(rejectProxy[i]){client.end('HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n');return;}const upstream=net.connect(req.url.startsWith('93.184.216.34:')?origins[i].address().port:tls.address().port,'127.0.0.1',()=>{client.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length)upstream.write(head);client.pipe(upstream).pipe(client);});upstream.on('error',()=>client.destroy());client.on('error',()=>upstream.destroy());client.on('close',()=>upstream.destroy());});await listen(s);return s;}));
const rejected=http.createServer((req,res)=>{res.writeHead(502);res.end();});rejected.on('connect',(_req,socket)=>socket.end('HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n'));await listen(rejected);
const call=(method,args={})=>page.evaluate(({method,args})=>window.wbox.call(method,args),{method,args});
const visibleTray=()=>app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().some(w=>w.getTitle()==='Wbox Tray'&&w.isVisible()));
const openTray=async()=>{await page.evaluate(()=>window.wbox.application('showTray'));await wait(visibleTray);tray=app.windows().find(p=>p.url().includes('tray.html'));};
const closeTray=async()=>{await tray.evaluate(()=>window.wbox.application('closeTray'));};
const openGroups=async()=>{await openTray();await tray.getByRole('button',{name:'组',exact:true}).hover();await tray.getByRole('button',{name:'全部测速',exact:true}).waitFor();};
const openGroup=async name=>{await tray.locator('[data-tray-submenu-parent="2"]').filter({has:tray.locator('span:first-child').filter({hasText:new RegExp(`^${name}$`)})}).hover();await tray.getByRole('button',{name:'测速',exact:true}).waitFor();};
async function forward(index){
 const logs=await call('logs'),entry=logs.map(l=>l.message).findLast(m=>m.includes('本地代理 127.0.0.1:')),port=Number(entry.match(/127\.0\.0\.1:(\d+)/)[1]);
 const body=await new Promise((done,reject)=>{const req=http.get({host:'127.0.0.1',port,headers:{Host:'93.184.216.34'},path:'http://93.184.216.34/forward'},r=>{let value='';r.on('data',c=>value+=c);r.on('end',()=>done(value));});req.setTimeout(8000,()=>req.destroy(Error('Forwarding timed out')));req.on('error',reject);});
 check(body===`WBOX-${index}`,`Current core forwards through Node${index} only`);
}
try{
 await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'nodes',SystemProxy:false,Silent:false,AutoConnect:false,Configs:[]}));
 // Capture the real Tray instance before application startup, without a production test hook.
 const bootstrap=join(data,'bootstrap.mjs');
 await writeFile(bootstrap,`import {Tray} from 'electron';const on=Tray.prototype.on;Tray.prototype.on=function(...args){globalThis.wboxTestTray=this;return on.apply(this,args)};await import(${JSON.stringify(fileURL(join(root,'desktop/out/main/index.js')).href)});`);
 app=await electron.launch({executablePath:process.env.WBOX_EXE||join(root,'desktop/node_modules/electron/dist/electron.exe'),args:process.env.WBOX_EXE?[]:[bootstrap],env:{...process.env,WBOX_DATA_DIR:data}});
 await wait(()=>{page=app.windows().find(p=>p.url().includes('index.html'));return !!page;});page.setDefaultTimeout(12000);page.on('pageerror',e=>errors.push(e.message));
 if(!process.env.WBOX_EXE){
  await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().includes('index.html')).close());
  await wait(()=>app.evaluate(({BrowserWindow})=>!BrowserWindow.getAllWindows().some(w=>w.webContents?.getURL().includes('index.html'))));
  await app.evaluate(()=>globalThis.wboxTestTray.emit('right-click'));
  await wait(visibleTray);check(!app.windows().some(p=>p.url().includes('index.html')),'Tray right click opens only the tray menu after GUI release');
  await app.evaluate(()=>globalThis.wboxTestTray.emit('click'));
  await wait(()=>{page=app.windows().find(p=>p.url().includes('index.html'));return !!page;});
  check(!(await visibleTray()),'Tray left click recreates the GUI and closes the menu');
  await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().includes('index.html')).minimize());
  await app.evaluate(()=>globalThis.wboxTestTray.emit('click'));
  await wait(()=>app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().some(w=>w.webContents.getURL().includes('index.html')&&w.isVisible()&&!w.isMinimized())));
  check(true,'Tray left click restores a minimized GUI');
 }
 await page.getByText(version,{exact:true}).waitFor();
 await page.evaluate(()=>localStorage.setItem('wbox.theme',JSON.stringify('dark')));await page.reload();
 await call('importInput',{text:[...proxies.map((p,i)=>`http://127.0.0.1:${p.address().port}#Node${i}`),`http://127.0.0.1:${rejected.address().port}#Rejected`].join('\n')});
 await page.getByRole('button',{name:'节点与订阅',exact:true}).click();
 for(const core of ['mihomo','sing-box']){
  await call('settings',{core,delayUrl:core==='mihomo'?'http://93.184.216.34/check':`https://localhost:${tls.address().port}/check`});
  await call('selectNode',{index:0});await call('start');
  let groups=await call('trayGroups'),tag=(await call('state')).nodes[0].tag;
  check(groups.some(g=>g.name==='Microsoft')&&groups.some(g=>g.name==='AI'),core+' tray exposes real template policy groups');
  check(!groups.some(g=>g.details.some(n=>n.name.includes('Node1')||n.name.includes('Rejected'))),core+' running groups exclude unselected library nodes');
  check(await page.getByRole('button',{name:'代理',exact:true}).count()===0,'Node GUI keeps its independent list with no policy-group page');
  await openGroups();await openGroup('节点选择');
  const cascade=await tray.evaluate(()=>{
   const parent=document.querySelector('[data-tray-submenu-parent="1"][data-tray-submenu-active="true"]'),groups=document.querySelector('[data-tray-cascade-level="1"]'),nodes=document.querySelector('[data-tray-cascade-level="2"]');
   const rect=element=>{const r=element?.getBoundingClientRect();return r?{left:r.left,right:r.right,top:r.top,bottom:r.bottom}:null;};
   return {side:document.documentElement.dataset.trayCascadeSide,parent:rect(parent),groups:rect(groups),nodes:rect(nodes),width:innerWidth,height:innerHeight};
  });
  check(cascade.side==='left'&&cascade.nodes&&cascade.groups&&cascade.parent&&cascade.nodes.right<=cascade.parent.left+1&&cascade.nodes.left>=0&&cascade.nodes.top>=0&&cascade.nodes.bottom<=cascade.height+1,core+' third-level tray node menu opens left and stays within the window');
  const before=hits.slice(),started=(await call('service')).startedAt;
  await tray.getByRole('button',{name:'测速',exact:true}).click();
  await wait(async()=>!(await call('state')).delayTesting,45000);
  check(hits[0]>before[0]&&hits[1]===before[1],core+' tray group latency tests the current node, not the library');
  check((await call('service')).startedAt===started&&(await call('state')).selectedNode===0,'Tray latency preserves the connection and selected node');
  await wait(async()=>/ms|timeout/.test((await call('state')).nodes[0].delayText));
  check(true,'Tray result updates the node workspace delay');
  if(core==='sing-box'){
   const timedOut=tray.locator('[data-tray-cascade-level="2"]').getByText('timeout',{exact:true}).first();await timedOut.waitFor();
   check(await timedOut.evaluate(el=>{const probe=document.createElement('span');probe.style.color='var(--danger)';document.body.append(probe);const matches=getComputedStyle(el).color===getComputedStyle(probe).color;probe.remove();return matches;}),'Tray timeout is red');
  }
  await openGroup('Microsoft');
  await tray.locator('[data-tray-cascade-level="2"]').getByRole('button').filter({has:tray.getByText('节点选择',{exact:true})}).click();
  await wait(async()=>(await call('trayGroups')).find(g=>g.name==='Microsoft').selected==='节点选择');
  check(true,core+' tray selection changes the live policy group');
  await closeTray();
  await call('traySelect',{group:'节点选择',node:tag});
  await call('stop');await call('start');
  check((await call('trayGroups')).find(g=>g.name==='Microsoft').selected==='节点选择',core+' node-workspace policy choice survives restart');
  let rejectedChoice=false;try{await call('traySelect',{group:'节点选择',node:(await call('state')).nodes[1].tag});}catch{rejectedChoice=true;}
  check(rejectedChoice&&(await call('state')).selectedNode===0,'Tray refuses nodes that are not part of the running group');
  await call('selectNode',{index:1});await forward(1);
  groups=await call('trayGroups');tag=(await call('state')).nodes[1].tag;
  check(groups.find(g=>g.name==='节点选择').selected===tag&&!groups.some(g=>g.details.some(n=>n.name.includes('Node0'))),core+' switching nodes keeps the current-node policy binding');
  await call('testOne',{index:2});await wait(async()=>!(await call('state')).delayTesting);
  const failed=page.getByRole('button',{name:'测试节点 Rejected 的延迟',exact:true});await wait(async()=>(await failed.innerText())==='timeout');
  check(await failed.locator('span').evaluate(el=>{const probe=document.createElement('span');probe.style.color='var(--danger)';document.body.append(probe);const matches=getComputedStyle(el).color===getComputedStyle(probe).color;probe.remove();return matches;}),'Node workspace failure displays lowercase red timeout');
  check(await failed.evaluate(el=>getComputedStyle(el).backgroundColor==='rgba(0, 0, 0, 0)'&&getComputedStyle(el).fontSize==='11px'),'Timeout keeps transparent background and small text');
  await openGroups();const baseline=hits.slice();await tray.getByRole('button',{name:'全部测速',exact:true}).click();await wait(async()=>!(await call('state')).delayTesting,45000);
  check(hits[0]===baseline[0]&&hits[1]>baseline[1]&&(await call('service')).running,core+' test-all measures runtime leaves without restarting or loading other nodes');
  if(core==='mihomo'){
   check((await call('trayGroups')).find(g=>g.name==='节点选择').details.find(n=>n.name===tag).delay>0,'Tray has a successful measurement before a node-list retry');
   rejectProxy[1]=true;await call('testOne',{index:1});await wait(async()=>!(await call('state')).delayTesting);rejectProxy[1]=false;
   check((await call('state')).nodes[1].delayText==='timeout'&&(await call('trayGroups')).find(g=>g.name==='节点选择').details.find(n=>n.name===tag).delay===0,'Node-list retry replaces the older tray delay with timeout');
  }
  await openGroup('节点选择');await tray.screenshot({path:join(root,`docs/previews/${version}-tray-${core}.png`)});
  await closeTray();await page.screenshot({path:join(root,`docs/previews/${version}-nodes-${core}.png`)});await call('stop');
 }
 await call('settings',{mode:'config'});
 await call('createProfile',{name:'Two groups',content:JSON.stringify({certificate:{certificate:[cert]},outbounds:[{type:'selector',tag:'First',outbounds:['A']},{type:'selector',tag:'Second',outbounds:['B']},...proxies.map((p,i)=>({type:'http',tag:['A','B'][i],server:'127.0.0.1',server_port:p.address().port}))]})});
 await call('settings',{delayUrl:`https://localhost:${tls.address().port}/test`});await call('start');await openGroups();const previous=hits.slice();
 await tray.getByRole('button',{name:'全部测速',exact:true}).click();await wait(async()=>!(await call('state')).delayTesting);
 check(hits.every((v,i)=>v>previous[i]),'Configuration tray test-all includes both groups instead of dropping concurrent requests');
 check((await call('trayGroups')).every(g=>g.details.every(n=>n.delay>0)),'Configuration group results remain available');
 await closeTray();await call('stop');
 await call('stop');
 for(const engine of ['mihomo','sing-box']){
  await call('settings',{mode:'nodes',core:engine});await call('selectNode',{index:0});
  await call('delayNodes');
  await call('start');check((await call('state')).running&&(await call('state')).selectedNode===0,engine+' can start the selected node while isolated latency continues');
  await wait(async()=>!(await call('state')).delayTesting,45000);
  check((await call('state')).nodes.every(node=>/ms$|^timeout$/.test(node.delayText)),engine+' starting the connection preserves completed node measurements');
  await call('delayNodes');await call('selectNode',{index:1});await forward(1);
  check((await call('state')).selectedNode===1&&(await call('state')).running,engine+' selecting a node during latency applies the new node immediately');
  await wait(async()=>!(await call('state')).delayTesting,45000);
  check((await call('state')).nodes.every(node=>/ms$|^timeout$/.test(node.delayText)),engine+' selecting a node preserves results from the isolated test');
  await call('stop');await call('settings',{mode:'config'});
  const profile=(index,tag='Active')=>engine==='mihomo'?JSON.stringify({proxies:[{name:tag,type:'http',server:'127.0.0.1',port:proxies[index].address().port}],'proxy-groups':[{name:'Main',type:'select',proxies:[tag]}],rules:['MATCH,Main']}):JSON.stringify({outbounds:[{type:'http',tag,server:'127.0.0.1',server_port:proxies[index].address().port}],route:{final:tag}});
  await call('createProfile',{name:engine+' Live',content:profile(0)});
  let currentState=await call('state');let active=currentState.configs.find(c=>c.path===currentState.configPath);
  await call('start');await forward(0);
  await call('writeProfile',{id:active.id,content:profile(1)});await forward(1);
  check((await call('state')).running,engine+' editing the active config reloads and forwards through the new proxy');
  const saved=await call('readProfile',{id:active.id});let failed=false;
  const invalid=engine==='mihomo'?JSON.stringify({proxies:[{name:'Broken',type:'unsupported-wbox'}],'proxy-groups':[{name:'Main',type:'select',proxies:['Broken']}],rules:['MATCH,Main']}):JSON.stringify({outbounds:[{type:'unsupported-wbox',tag:'Broken'}],route:{final:'Broken'}});
  try{await call('writeProfile',{id:active.id,content:invalid});}catch{failed=true;}
  check(failed&&(await call('state')).running&&(await call('readProfile',{id:active.id}))===saved,engine+' invalid live config restores both saved content and the running connection');await forward(1);
  const started=(await call('service')).startedAt;await call('renameProfile',{id:active.id,name:engine+' Current'});
  check((await call('service')).startedAt===started,engine+' renaming a config does not interrupt its connection');
  if(engine==='mihomo'){
   const ruleResponses=[];
   const slowRules=http.createServer((_req,res)=>ruleResponses.push(res));await listen(slowRules);
   try{
    const original=await call('readProfile',{id:active.id}),beforeLogs=await call('logs');
    const slow=JSON.parse(profile(0));
    slow['rule-providers']={Slow:{type:'http',behavior:'classical',proxy:'DIRECT',url:`http://127.0.0.1:${slowRules.address().port}/rules`,interval:86400}};
    slow.rules=['RULE-SET,Slow,DIRECT','MATCH,Main'];
    let reloadFinished=false;
    const reload=call('writeProfile',{id:active.id,content:JSON.stringify(slow)}).then(()=>null,error=>error).finally(()=>{reloadFinished=true;});
    await wait(async()=>ruleResponses.length>0&&(await call('service')).starting);
    const cancel=call('cancelTest');await new Promise(r=>setTimeout(r,300));
    check(!reloadFinished&&(await call('service')).starting,'Canceling latency does not cancel the main core reload');
    const began=Date.now();await call('stop');await cancel;await reload;
    const stopped=await call('service'),afterLogs=await call('logs');
    check(Date.now()-began<1500&&!stopped.running&&!stopped.starting,'Stopping during an active config reload stays disconnected and completes promptly');
    check(afterLogs.filter(l=>l.id>Math.max(0,...beforeLogs.map(l=>l.id))).every(l=>!l.message.includes('已启动（')),'A canceled reload never starts the previous connection during rollback');
    check((await call('readProfile',{id:active.id}))===original,'Canceling a live reload restores the original profile content');
    await call('start');await forward(1);check((await call('service')).running,'An explicit start after canceling a reload can connect again');
   }finally{for(const response of ruleResponses)if(!response.destroyed)response.end('payload:\n  - DOMAIN,example.invalid\n');slowRules.closeAllConnections();slowRules.close();}
  }
  await call('createProfile',{name:engine+' Other',content:profile(0,'Other')});
  const other=(await call('state')).configs.find(c=>c.name===engine+' Other');await call('selectProfile',{id:other.id});await forward(0);
  check((await call('state')).running,engine+' switching profiles takes effect while connected');
  await call('settings',{mode:'nodes',core:engine});await forward(1);check((await call('state')).mode==='nodes',engine+' switching workspaces applies immediately');
  const nextEngine=engine==='mihomo'?'sing-box':'mihomo';await call('settings',{core:nextEngine});await forward(1);check((await call('state')).core===nextEngine,'Node core switching applies immediately');
  await call('stop');
 }
 await call('settings',{mode:'config'});
 let sourceIndex=0,downloadPause=0;
 const remoteSource=http.createServer(async(req,res)=>{await new Promise(r=>setTimeout(r,downloadPause));res.setHeader('Subscription-Userinfo','upload=10;download=20;total=1024;expire=0');res.end(JSON.stringify({proxies:[{name:'Remote',type:'http',server:'127.0.0.1',port:proxies[sourceIndex].address().port}],'proxy-groups':[{name:'Main',type:'select',proxies:['Remote']}],rules:['MATCH,Main']}));});
 await listen(remoteSource);
 try{
  const url=`http://127.0.0.1:${remoteSource.address().port}/config`;
  await call('createProfile',{name:'Remote plan',remoteUrl:url});const current=await call('state'),remote=current.configs.find(c=>c.path===current.configPath);
  await call('start');await forward(0);sourceIndex=1;downloadPause=3000;
  const download=call('updateProfile',{id:remote.id});await new Promise(r=>setTimeout(r,150));
  const began=Date.now();await call('stop');check(Date.now()-began<1500,'Stopping is responsive during a subscription download');
  await download;check(!(await call('state')).running,'A completed download preserves a user-requested stop');
  await call('start');await forward(1);downloadPause=0;sourceIndex=0;await call('updateProfile',{id:remote.id});await forward(0);
  check((await call('state')).running,'Synchronizing the active remote profile applies immediately');
  sourceIndex=1;await call('refreshSubscriptions');await wait(async()=>!(await call('state')).subscriptionUpdating);await forward(1);
  check((await call('state')).configs.find(c=>c.id===remote.id).subscription.usage.download===20,'Update-all reloads active profiles and their quota');
  downloadPause=3000;const beforeRevision=(await call('state')).configs.find(c=>c.id===remote.id).updated;const stale=call('updateProfile',{id:remote.id}).then(()=>false,()=>true);await new Promise(r=>setTimeout(r,150));
  await call('writeProfile',{id:remote.id,content:JSON.stringify({proxies:[{name:'Edited',type:'http',server:'127.0.0.1',port:proxies[0].address().port}],'proxy-groups':[{name:'Main',type:'select',proxies:['Edited']}],rules:['MATCH,Main']})});
  const staleRejected=await stale;if(!staleRejected)console.log('STALE DIAGNOSTIC',JSON.stringify({beforeRevision,after:(await call('state')).configs.find(c=>c.id===remote.id).updated,content:await call('readProfile',{id:remote.id})}));
  check(staleRejected,'A stale download cannot overwrite a simultaneous config edit');await forward(0);await call('stop');
 }finally{remoteSource.closeAllConnections();remoteSource.close();}
 await call('sortLibrary',{order:'name'});check((await call('state')).librarySort==='name','Library sort persists in preferences');
 await page.getByRole('button',{name:'配置',exact:true}).click();
 await page.getByRole('button',{name:/配置操作/}).first().click();
 await page.screenshot({path:join(root,`docs/previews/${version}-windows-profiles.png`)});
 await page.getByText('预览',{exact:true}).click();await page.getByText(/预览 ·/).waitFor();check(true,'Card menu opens a read-only config preview');
 await page.getByRole('button',{name:'关闭',exact:true}).click();
 await page.screenshot({path:join(root,`docs/previews/${version}-windows-library.png`)});
check(errors.length===0,'No renderer errors');
}catch(e){failure=String(e);if(page&&!page.isClosed())try{await page.screenshot({path:join(root,`docs/previews/${version}-failure.png`),timeout:5000});}catch{}throw e;}
finally{if(app)await app.close();for(const server of [tls,rejected,...origins,...proxies]){server.closeAllConnections();server.close();}await writeFile(join(root,`docs/verification/${version}-${process.env.WBOX_EXE?'packaged':'desktop'}.json`),JSON.stringify({passed:failure===null,failure,checks,errors},null,2));await rm(data,{recursive:true,force:true});}

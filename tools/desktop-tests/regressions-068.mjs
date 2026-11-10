import {_electron as electron} from 'playwright';
import {mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import http from 'node:http';
import net from 'node:net';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'../..'),data=join(root,'windows/regressions-068-runtime');
await mkdir(data,{recursive:true});await mkdir(join(root,'docs/previews'),{recursive:true});
const checks=[],timing={};const check=(ok,name)=>{assert(ok,name);checks.push(name);console.log('PASS',name);};
let subscription='',subscriptionWait=0;
const origin=http.createServer(async(req,res)=>{
  if(req.url==='/subscription'){await new Promise(r=>setTimeout(r,subscriptionWait));res.writeHead(200,{'Content-Type':'application/json'});res.end(subscription);return;}
  await new Promise(r=>setTimeout(r,60));res.writeHead(204,{'Connection':'keep-alive'});res.end();
});
await new Promise(r=>origin.listen(0,'127.0.0.1',r));const originPort=origin.address().port;
const proxy=http.createServer();proxy.on('connect',(req,client,head)=>{
  setTimeout(()=>{const upstream=net.connect(originPort,'127.0.0.1',()=>{client.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length)upstream.write(head);client.pipe(upstream).pipe(client);});upstream.on('error',()=>client.destroy());client.on('error',()=>upstream.destroy());client.on('close',()=>upstream.destroy());},240);
});
await new Promise(r=>proxy.listen(0,'127.0.0.1',r));const proxyPort=proxy.address().port;
await writeFile(join(data,'settings.json'),JSON.stringify({SystemProxy:false,Silent:false,AutoConnect:false,Configs:[]}));
const app=await electron.launch({executablePath:process.env.WBOX_EXE||join(root,'desktop/node_modules/electron/dist/electron.exe'),args:process.env.WBOX_EXE?[]:[join(root,'desktop/out/main/index.js')],env:{...process.env,WBOX_DATA_DIR:data}});
let page;const errors=[];
const wait=async(fn,timeout=30000)=>{const end=Date.now()+timeout;while(Date.now()<end){if(await fn())return;await new Promise(r=>setTimeout(r,80));}throw Error('Timed out');};
try{
  await wait(()=>{page=app.windows().find(p=>p.url().includes('index.html'));return Boolean(page);});
  page.on('pageerror',e=>errors.push(e.message));
  await page.getByRole('button',{name:'代理',exact:true}).waitFor();
  await wait(async()=>app.windows().some(p=>p.url().includes('tray.html')));
  await new Promise(r=>setTimeout(r,600));
  const begin=performance.now();await page.evaluate(()=>window.wbox.application('showTray'));
  await wait(()=>app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().some(w=>w.getTitle()==='Wbox Tray'&&w.isVisible())));
  timing.firstTrayMs=Math.round(performance.now()-begin);check(timing.firstTrayMs<500,'Preloaded tray first open completes within 500 ms');
  let tray=app.windows().find(p=>p.url().includes('tray.html'));
  await tray.evaluate(()=>window.wbox.application('closeTray'));
  const config={'unified-delay':false,proxies:['A','B'].map(name=>({name,type:'http',server:'127.0.0.1',port:proxyPort})),'proxy-groups':[{name:'Main',type:'select',proxies:['A','B']}],rules:['MATCH,Main']};
  const call=(method,args={})=>page.evaluate(({method,args})=>window.wbox.call(method,args),{method,args});
  await call('createProfile',{name:'Regression',content:JSON.stringify(config)});await call('settings',{delayUrl:`http://127.0.0.1:${originPort}/test`});
  await page.getByRole('button',{name:'代理',exact:true}).click();await page.locator('button[title="B"]').waitFor();
  check(await page.getByRole('tab').count()===0,'Windows proxy page uses upstream cards without mobile tabs');
  await page.locator('button[title="B"]').click();await wait(async()=>(await call('groups'))[0].selected==='B');
  check(!(await call('service')).running,'Offline node selection saves without starting a core');
  let saved=JSON.parse(await readFile(join(data,'settings.json'),'utf8'));check(saved.Configs[0].GroupSelections.Main==='B','Offline choice is persisted per configuration');
  await call('testGroup',{tag:'Main'});await wait(async()=>!(await call('state')).delayTesting);
  let groups=await call('groups');timing.offlineDelayMs=groups[0].details.map(n=>n.delay);
  check(groups[0].selected==='B','Offline test restores the selected node');
  check(groups[0].details.every(n=>n.delay>=45&&n.delay<200),'Native unified-delay excludes the 240 ms proxy handshake');
  await call('start');check((await call('groups'))[0].selected==='B','Starting mihomo restores the offline selection');
  await page.locator('button[title="A"]').click();await wait(async()=>(await call('groups'))[0].selected==='A');
  check(true,'Connected proxy card switches the live core');
  await page.waitForTimeout(1100);await page.screenshot({path:join(root,'docs/previews/0.6.8-windows-proxies.png')});
  await call('stop');await call('start');check((await call('groups'))[0].selected==='A','Restart restores the live selection');
  const source=await readFile((await call('state')).configPath,'utf8');check(JSON.parse(source)['unified-delay']===false,'Timing adaptation does not overwrite source configuration');
  await page.evaluate(()=>window.wbox.application('showTray'));tray=app.windows().find(p=>p.url().includes('tray.html'));
  await wait(async()=>await tray.locator('[data-tray-submenu-parent="1"]').count()>1);
  await tray.locator('[data-tray-submenu-parent="1"]').first().hover();
  await tray.locator('[data-tray-submenu-parent="2"]').first().hover();
  await tray.getByText('测速',{exact:true}).waitFor();
  await tray.getByText('测速',{exact:true}).click();
  await wait(async()=>!(await call('state')).delayTesting);
  await wait(async()=>await tray.getByText(/^[1-9]\d*ms$/).count()>0);
  const font=await tray.evaluate(()=>{
    const el=document.createElement('span');el.textContent='1488 ms';el.style.cssText='font:12px var(--font-sans);letter-spacing:normal';document.body.append(el);const width=el.getBoundingClientRect().width;el.style.display='inline-block';const actual=el.getBoundingClientRect().width;el.remove();return {width:actual,loaded:document.fonts.check('12px "Schibsted Grotesk Variable"')};
  });check(font.loaded&&font.width<70,'Tray latency digits use the loaded text font at normal width');
  await tray.screenshot({path:join(root,'docs/previews/0.6.8-windows-tray.png')});await tray.evaluate(()=>window.wbox.application('closeTray'));
  await call('stop');
  const sb={outbounds:[{type:'selector',tag:'Main',outbounds:['A','B']},{type:'direct',tag:'A'},{type:'direct',tag:'B'}]};
  await call('createProfile',{name:'Sing selection',content:JSON.stringify(sb)});await call('select',{group:'Main',node:'B'});await call('start');
  check((await call('groups'))[0].selected==='B','Sing-box restores offline selection too');await call('select',{group:'Main',node:'A'});check((await call('groups'))[0].selected==='A','Sing-box online node selection succeeds');await call('stop');
  subscription=JSON.stringify(config);await call('createProfile',{name:'Remote',remoteUrl:`http://127.0.0.1:${originPort}/subscription`});
  const remote=(await call('state')).configs.find(c=>c.name==='Remote');subscription=JSON.stringify({...config,'log-level':'warning'});subscriptionWait=900;
  await call('settings',{subscriptionAutoUpdate:true,subscriptionUpdateMinutes:15});await call('autoUpdate');
  const responsive=performance.now();await call('state');check(performance.now()-responsive<300,'Background download does not block controller commands');
  await call('renameProfile',{id:remote.id,name:'Renamed while downloading'});
  await wait(async()=>!(await call('state')).subscriptionUpdating);
  const updated=await call('state');check(updated.configs.some(c=>c.id===remote.id&&c.name==='Renamed while downloading')&&!updated.subscriptionUpdateError,'Scheduled update preserves concurrent profile rename');
  check((await call('readProfile',{id:remote.id})).includes('warning'),'Scheduled remote configuration was applied');
  const attempt=updated.subscriptionLastAttempt;await call('autoUpdate');check((await call('state')).subscriptionLastAttempt===attempt,'Repeated timer tick respects the configured interval');
  check(errors.length===0,'No renderer exceptions');
}finally{
  await app.close();origin.closeAllConnections();proxy.closeAllConnections();origin.close();proxy.close();
  await writeFile(join(root,'docs/verification/0.6.8-desktop.json'),JSON.stringify({checks,timing,errors},null,2));
  if(data===join(root,'windows/regressions-068-runtime'))await rm(data,{recursive:true,force:true});
}

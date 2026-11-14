import {_electron as electron} from 'playwright';
import {mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {createServer} from 'node:http';
import {connect} from 'node:net';
import {resolve,join} from 'node:path';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'../..'),data=join(root,`.tools/ui-workspace-077-${process.pid}`);
const checks=[],errors=[],measurements={};let app,page,failure=null;
const check=(ok,label)=>{assert.ok(ok,label);checks.push(label);console.log('PASS',label);};
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(fn,label){const end=Date.now()+20000;while(!await fn()){if(Date.now()>end)throw Error('Timed out '+label);await pause(100);}}
const call=(method,args={})=>page.evaluate(({method,args})=>window.wbox.call(method,args),{method,args});
const resize=async(width,height)=>{await app.evaluate(({BrowserWindow},{width,height})=>BrowserWindow.getAllWindows().find(window=>window.webContents.getURL().includes('index.html')).setSize(width,height),{width,height});await pause(250);};
const card=id=>page.locator(`.wbox-config-card[data-library-id="${id}"]`);
const order=()=>page.locator('.wbox-config-card').evaluateAll(cards=>cards.map(card=>card.dataset.libraryId));
const menu=()=>page.locator('.context-menu[role="menu"]');
async function rightClick(which,position){await which.scrollIntoViewIfNeeded();await pause(100);const rect=await which.boundingBox();const offset=position??{x:Math.min(80,rect.width/3),y:rect.height/2};const point={x:Math.round(rect.x+offset.x),y:Math.round(rect.y+offset.y)};await page.mouse.click(point.x,point.y,{button:'right'});await menu().waitFor();await pause(170);return point;}
const server=createServer((_request,response)=>{setTimeout(()=>{response.writeHead(204);response.end();},120);});
const sockets=new Set();
server.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));});
server.on('connect',(_request,client,head)=>{
 const upstream=connect(server.address().port,'127.0.0.1',()=>{client.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length)upstream.write(head);client.pipe(upstream).pipe(client);});
 upstream.on('error',()=>client.destroy());client.on('error',()=>upstream.destroy());client.on('close',()=>upstream.destroy());
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const now=Date.now(),updated=new Date(now-2*3600000).toISOString(),expire=Math.floor((now+18*86400000)/1000);
const content=JSON.stringify({proxies:[{name:'Harbor A',type:'http',server:'127.0.0.1',port:server.address().port},{name:'Harbor B · 较长的备用节点名称用于检查边界',type:'http',server:'127.0.0.1',port:server.address().port}],'proxy-groups':[{name:'Proxy',type:'select',proxies:['Harbor A','Harbor B · 较长的备用节点名称用于检查边界']}],rules:['MATCH,Proxy']});
const names=['Orion · 日常配置','Atlas · 备用配置','一份较长的配置名称，用来检查窄窗口下的换行和按钮位置','本地配置'];
async function launch(){
 app=await electron.launch({executablePath:process.env.WBOX_EXE||join(root,'desktop/node_modules/electron/dist/electron.exe'),args:process.env.WBOX_EXE?[]:[join(root,'desktop/out/main/index.js')],env:{...process.env,WBOX_DATA_DIR:data}});
 await until(()=>{page=app.windows().find(window=>window.url().includes('index.html'));return page;},'main window');
 page.setDefaultTimeout(15000);page.on('pageerror',error=>errors.push(error.message));await page.waitForFunction(()=>!!window.wbox);
 await page.evaluate(()=>{localStorage.setItem('wbox.theme',JSON.stringify('light'));localStorage.setItem('wbox.accent',JSON.stringify('blue'));});await page.reload();
 await resize(1080,820);await page.getByRole('button',{name:'配置',exact:true}).click();await card('p0').waitFor();
}
try{
 await mkdir(data,{recursive:true});const configs=[];
 for(let i=0;i<names.length;i++){const Id='p'+i,Path=join(data,Id+'.json');await writeFile(Path,content);configs.push({Id,Name:names[i],Path,Core:'mihomo',Url:i===3?'':`https://example.invalid/${Id}`,Updated:updated,Subscription:i===3?null:{Name:names[i],AutoUpdate:false,UpdateMinutes:1440,LastSuccess:updated,Usage:{Upload:1073741824,Download:2147483648,Total:107374182400,Expire:expire}}});}
 await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',Core:'mihomo',ConfigPath:configs[0].Path,SystemProxy:false,NativeInbounds:false,Silent:false,AutoConnect:false,Configs:configs,DelayUrl:'http://93.184.216.34/latency',SubscriptionSettingsMigrated:true}));
 await launch();
 const before=await call('state'),beforeOrder=await order();
 const point=await rightClick(card('p1'));const contextRect=await menu().boundingBox();measurements.contextMenu={point,rect:contextRect};
 check(Math.abs(contextRect.x-point.x)<=2&&Math.abs(contextRect.y-point.y)<=2,'Right-click menu opens at the pointer and extends to its right');
 const actions=(await menu().getByRole('button').allTextContents()).map(text=>text.trim());
 check(actions.join(',')==='编辑配置,预览,更多,导出配置,删除','Configuration right-click exposes all existing actions');
 await page.keyboard.press('Escape');await menu().waitFor({state:'detached'});
 check(true,'Escape dismisses the configuration context menu');
 await page.getByRole('button',{name:'配置操作 · '+names[1],exact:true}).click();
 const dotsMenu=page.locator('.popover-menu[role="menu"]');await dotsMenu.waitFor();
 check((await dotsMenu.getByRole('button').allTextContents()).map(text=>text.trim()).join(',')===actions.join(','),'Three-dot and card context menus share the same actions');
 const dots=await page.getByRole('button',{name:'配置操作 · '+names[1],exact:true}).boundingBox(),dotsRect=await dotsMenu.boundingBox();
 check(Math.abs(dotsRect.x+dotsRect.width-dots.x-dots.width)<=2,'Three-dot menu remains anchored to its own button');
 await page.keyboard.press('Escape');await rightClick(card('p1'));
 await menu().getByRole('button',{name:'预览',exact:true}).click();await page.getByRole('dialog',{name:'预览 · '+names[1],exact:true}).waitFor();
 await page.getByRole('button',{name:'关闭',exact:true}).click();
 await until(()=>card('p1').evaluate(element=>document.activeElement===element),'context preview focus return');
 check(true,'Right-click preview opens the clicked configuration and restores focus to that card');
 await rightClick(card('p1'));await page.locator('.wbox-library-heading > span').click();await menu().waitFor({state:'detached'});
 const unchanged=await call('state');
 check(unchanged.configPath===before.configPath&&!unchanged.running&&!unchanged.starting&&(await order()).join(',')===beforeOrder.join(','),'Right-click and menu dismissal do not select, connect, edit or reorder configurations');
 measurements.compact=await card('p0').evaluate(element=>({height:element.getBoundingClientRect().height,title:parseFloat(getComputedStyle(element.querySelector('strong')).fontSize),meta:parseFloat(getComputedStyle(element.querySelector('.wbox-library-meta')).fontSize)}));
 const legacySpacing=await page.addStyleTag({content:'.wbox-config-card {padding:20px 22px!important}.wbox-config-card .wbox-library-title {gap:12px!important;margin-bottom:14px!important}.wbox-config-card .wbox-library-actions {gap:8px!important}.wbox-config-card .wbox-subscription-traffic {gap:6px!important;margin-top:8px!important}.wbox-config-card .wbox-library-meta {margin-top:12px!important}'});
 measurements.legacyHeight=(await card('p0').boundingBox()).height;
 await legacySpacing.evaluate(element=>element.remove());
 check(measurements.legacyHeight-measurements.compact.height>=20&&measurements.compact.title>=18&&measurements.compact.meta>=14,'Configuration cards save vertical space without shrinking readable text');
 await card('p1').locator('[data-time-kind="expiry"]').click();await card('p1').locator('[data-time-kind="updated"]').click();
 check(/天后到期/.test(await card('p1').locator('[data-time-kind="expiry"]').innerText())&&/更新于 \d{4}\//.test(await card('p1').locator('[data-time-kind="updated"]').innerText()),'Time switches remain clickable inside compact draggable cards');
 const target=await card('p1').boundingBox();await card('p0').dragTo(card('p1'),{sourcePosition:{x:24,y:65},targetPosition:{x:target.width-24,y:65}});
 await until(async()=>(await order()).slice(0,2).join(',')==='p1,p0','direct configuration drag');
 check(!(await call('state')).running&&(await call('state')).configPath===before.configPath,'Direct card drag still reorders without selecting or connecting');
 await page.evaluate(()=>document.activeElement?.blur());await page.screenshot({path:join(root,'docs/previews/0.7.7-windows-configurations.png')});
 await resize(800,560);await card('p2').scrollIntoViewIfNeeded();
 const edge=await card('p2').boundingBox();await rightClick(card('p2'),{x:edge.width-8,y:Math.min(edge.height-8,120)});const edgeMenu=await menu().boundingBox();const viewport=await page.evaluate(()=>({width:innerWidth,height:innerHeight}));
 check(edgeMenu.x>=8&&edgeMenu.y>=8&&edgeMenu.x+edgeMenu.width<=viewport.width-7&&edgeMenu.y+edgeMenu.height<=viewport.height-7,'Context menu remains inside the narrow window near the right and bottom edges');
 await page.keyboard.press('Escape');
 check(await card('p2').evaluate(element=>getComputedStyle(element).outlineColor===getComputedStyle(document.querySelector('.wbox-config-card.current')).borderColor),'Keyboard focus uses the selected theme color');
 check(await page.locator('.wbox-config-card').evaluateAll(cards=>cards.every(card=>card.scrollWidth<=card.clientWidth))&&await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Long names and exact dates fit compact cards at minimum window width');
 await page.evaluate(()=>document.activeElement?.blur());
 await page.screenshot({path:join(root,'docs/previews/0.7.7-windows-configurations-narrow.png')});
 await resize(1080,820);await call('settings',{mode:'nodes'});await call('importInput',{text:`http://127.0.0.1:${server.address().port}#Node%20A\nhttp://127.0.0.1:${server.address().port}#Node%20B`});
 await page.getByRole('button',{name:'节点与订阅',exact:true}).click();
 const rows=page.locator('.wbox-node-table tbody tr');await rows.nth(1).waitFor();await rows.nth(1).locator('td').nth(1).click();
 await until(async()=>(await call('state')).selectedNode===1,'single-click node selection');
 const chosen=await call('state'),saved=JSON.parse(await readFile(join(data,'settings.json'),'utf8'));
 check(!chosen.running&&!chosen.starting&&saved.SelectedNode===1&&await rows.nth(1).getAttribute('aria-selected')==='true','One click persists and highlights an offline node without starting the core');
 await rows.nth(0).click({button:'right'});await menu().waitFor();
 check((await call('state')).selectedNode===1,'Opening another node context menu does not change the selected node');await page.keyboard.press('Escape');
 await rows.nth(0).locator('.wbox-node-delay').click();await until(async()=>!(await call('state')).delayTesting&&/\d+ ms/.test((await call('state')).nodes[0].delayText),'single-node latency');
 check((await call('state')).selectedNode===1&&!(await call('state')).running,'Clicking a node latency action preserves the offline selection');
 await rows.nth(0).locator('td').nth(1).dblclick();await until(async()=>(await call('service')).running,'explicit double-click connect');
 check((await call('state')).selectedNode===0,'Explicit double-click still connects the chosen node');
 await rows.nth(1).locator('td').nth(1).click();await until(async()=>{const state=await call('state');return state.selectedNode===1&&state.running&&!state.starting;},'connected single-click switch');
 check(true,'Single-click switches nodes while an existing connection is running');
 await call('stop');await call('settings',{mode:'config'});await call('selectProfile',{id:'p0'});await page.getByRole('button',{name:'代理',exact:true}).click();
 const delay=page.getByRole('button',{name:'测试节点 Harbor A 的延迟',exact:true});await delay.waitFor();await delay.click();
 await until(async()=>/\d+\s*ms/.test(await delay.innerText()),'proxy card numeric latency');
 measurements.delay=await delay.evaluate(button=>{const card=button.parentElement,meta=card.querySelector('button:first-child > span:last-child'),text=button.querySelector('span'),box=card.getBoundingClientRect(),value=text.getBoundingClientRect(),label=meta.getBoundingClientRect();return {right:box.right-value.right,bottom:box.bottom-value.bottom,baselineDifference:Math.abs(value.bottom-label.bottom),clickHeight:button.getBoundingClientRect().height};});
 check(measurements.delay.right>=10&&measurements.delay.right<=14&&measurements.delay.bottom>=8&&measurements.delay.bottom<=13&&measurements.delay.baselineDifference<=2&&measurements.delay.clickHeight>=28,'Latency text sits at the bottom-right, aligned with protocol text, with its full click target');
 check(!(await call('state')).running,'Proxy-card latency measurement leaves the main core disconnected');
 await page.evaluate(()=>document.activeElement?.blur());await page.screenshot({path:join(root,'docs/previews/0.7.7-windows-proxy-latency.png')});
 await resize(800,560);check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Proxy cards with long node names fit the minimum window');
 check(errors.length===0,'No renderer errors');
}catch(error){failure=error.stack;console.error(error);if(page)try{measurements.failureState=await call('state');measurements.failureLogs=await call('logs');}catch{}}finally{
 if(app){try{await call('stop');}catch{}await app.close();}
 for(const socket of sockets)socket.destroy();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));
 assert.ok(data.startsWith(join(root,'.tools')+'\\'));await rm(data,{recursive:true,force:true});
 await writeFile(join(root,'docs/verification/0.7.7-windows-workspace.json'),JSON.stringify({passed:!failure,checks,errors,measurements,failure,build:process.env.WBOX_EXE?'packaged':'development',screenshots:['docs/previews/0.7.7-windows-configurations.png','docs/previews/0.7.7-windows-configurations-narrow.png','docs/previews/0.7.7-windows-proxy-latency.png'],isolatedDataRemoved:true},null,2)+'\n');
}
if(failure)process.exitCode=1;

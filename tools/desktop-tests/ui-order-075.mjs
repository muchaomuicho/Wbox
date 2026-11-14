import {_electron as electron} from 'playwright';
import {mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {createServer} from 'node:http';
import {resolve,join} from 'node:path';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'../..'),data=join(root,`.tools/ui-order-075-${process.pid}`);
const checks=[],errors=[];let app,page,failure=null,pendingResponse,holdUpdates=false,requests=0;
const check=(ok,label)=>{assert.ok(ok,label);checks.push(label);console.log('PASS',label);};
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn,label){const end=Date.now()+20000;while(!await fn()){if(Date.now()>end)throw Error('Timed out '+label);await pause(100);}}
const call=(method,args={})=>page.evaluate(({method,args})=>window.wbox.call(method,args),{method,args});
const now=Date.now(),expire=Math.floor((now+18*86400000)/1000);
const content='proxies: []\nproxy-groups:\n  - {name: Proxy, type: select, proxies: [DIRECT]}\nrules: ["MATCH,Proxy"]\n';
const respond=response=>{response.setHeader('Subscription-Userinfo',`upload=1024; download=2048; total=107374182400; expire=${expire}`);response.end(content+'# updated subscription\n');};
const server=createServer((request,response)=>{requests++;if(holdUpdates)pendingResponse=response;else respond(response);});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin=`http://127.0.0.1:${server.address().port}`;
const names=['Atlas','Beta','Cygnus','Draco','Echo','Fornax','Gemini','Horizon','Indigo','Jade'];
const options=(name,date)=>({Name:name,AutoUpdate:false,UpdateMinutes:1440,LastAttempt:date,LastSuccess:date,Usage:{Upload:1024,Download:2048,Total:107374182400,Expire:expire},Error:''});
async function launch(){
 app=await electron.launch({executablePath:process.env.WBOX_EXE||join(root,'desktop/node_modules/electron/dist/electron.exe'),args:process.env.WBOX_EXE?[]:[join(root,'desktop/out/main/index.js')],env:{...process.env,WBOX_DATA_DIR:data}});
 await until(()=>{page=app.windows().find(window=>window.url().includes('index.html'));return page;},'main window');
 page.setDefaultTimeout(15000);page.on('pageerror',error=>errors.push(error.message));await page.waitForFunction(()=>!!window.wbox);
 await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(window=>window.webContents.getURL().includes('index.html')).setSize(1080,820));
 await page.getByRole('button',{name:'配置',exact:true}).click();await page.locator('.wbox-library-card').first().waitFor();
}
const config=id=>page.locator(`.wbox-library-card[data-library-id="${id}"]`);
const order=()=>page.locator('.wbox-library-card[data-library-id]').evaluateAll(cards=>cards.map(card=>card.dataset.libraryId));
async function drag(id,anchor,after=true){
 await config(id).scrollIntoViewIfNeeded();await config(anchor).scrollIntoViewIfNeeded();
 const target=await config(anchor).boundingBox();
 const columns=await page.locator('.wbox-library-grid').evaluate(grid=>getComputedStyle(grid).gridTemplateColumns.split(/\s+/).length);
 await config(id).dragTo(config(anchor),{sourcePosition:{x:25,y:80},targetPosition:columns>1?{x:after?target.width-25:25,y:80}:{x:25,y:after?target.height-25:25}});
 await until(async()=>{const ids=(await call('state')).configs.map(profile=>profile.id),from=ids.indexOf(id),to=ids.indexOf(anchor);return after?from===to+1:from===to-1;},'drag order '+id);
 await pause(350);
}
try{
 await mkdir(data,{recursive:true});const configs=[];
 for(let i=0;i<names.length;i++){const Id='p'+i,Path=join(data,Id+'.yaml'),Updated=new Date(now-i*3600000).toISOString();await writeFile(Path,content);configs.push({Id,Name:names[i],Path,Core:'mihomo',Url:`${origin}/${Id}`,Updated,Subscription:options(names[i],Updated)});}
 const subscriptions=['s1','s2','s3'].map(id=>origin+'/'+id),settings=Object.fromEntries(subscriptions.map((url,i)=>[url,options('Nodes '+(i+1),new Date(now-i*3600000).toISOString())]));
 await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',Core:'mihomo',ConfigPath:configs[0].Path,SystemProxy:false,NativeInbounds:false,Silent:false,AutoConnect:false,Configs:configs,Subscriptions:subscriptions,SubscriptionSettings:settings,SubscriptionSettingsMigrated:true}));
 await launch();
 check(await config('p0').getAttribute('draggable')==='true','Configuration cards themselves are draggable');
 const before=await call('state');await drag('p0','p1',true);
 check((await order()).slice(0,3).join(',')==='p1,p0,p2','Direct card drag reorders within the same grid row');
 await drag('p0','p3',true);
 check((await order()).slice(0,4).join(',')==='p1,p2,p3,p0','Direct card drag reorders across grid rows');
 const unchanged=await call('state');check(unchanged.configPath===before.configPath&&!unchanged.running,'Dragging does not select another configuration or connect');
 await config('p1').scrollIntoViewIfNeeded();const start=await config('p1').boundingBox();
 await page.mouse.move(start.x+25,start.y+80);await page.mouse.down();await page.mouse.move(start.x+60,start.y+80,{steps:5});
 const scroll=await config('p1').evaluate(card=>{for(let parent=card.parentElement;parent;parent=parent.parentElement)if(/(auto|scroll)/.test(getComputedStyle(parent).overflowY)&&parent.scrollHeight>parent.clientHeight)return {top:parent.scrollTop,rect:parent.getBoundingClientRect().toJSON()};return null;});
 check(!!scroll,'Long card grids use the content scroll container');
 await page.mouse.move(scroll.rect.right-100,scroll.rect.bottom-8,{steps:12});
 await until(()=>config('p9').evaluate(card=>{const rect=card.getBoundingClientRect();return rect.top<innerHeight-120;}),'drag auto-scroll reveals lower rows');
 const end=await config('p9').boundingBox();await page.mouse.move(end.x+end.width-25,Math.min(end.y+80,scroll.rect.bottom-10),{steps:8});await page.mouse.up();
 await until(async()=>(await call('state')).configs.at(-1).id==='p1','scrolling drag commits at end');await pause(350);
 check((await order()).at(-1)==='p1','Dragging near the window edge scrolls and can reach the final row');
 await call('sortLibrary',{order:'name'});await until(async()=>(await order())[0]==='p0','automatic name sort');
 await drag('p0','p3',true);const manual=await order();
 check((await call('state')).librarySort==='default'&&manual.slice(0,4).join(',')==='p1,p2,p3,p0','Dragging under name sort switches to the visible manual order');
 check((await call('profiles')).profiles.map(profile=>profile.id).join(',')===manual.join(',')&&(await call('trayProfiles')).profiles.map(profile=>profile.id).join(',')===manual.join(','),'Overview and tray pickers share card order');
 await config('p0').scrollIntoViewIfNeeded();const beforeTime=await order();
 await config('p0').locator('[data-time-kind="expiry"]').click();await config('p0').locator('[data-time-kind="updated"]').click();
 check((await order()).join(',')===beforeTime.join(',')&&await config('p0').locator('[data-time-kind="expiry"]').innerText()==='18 天后到期','Expiry and update toggles remain normal inside draggable cards');
 await page.getByRole('button',{name:'配置操作 · Atlas',exact:true}).click();check(await page.getByRole('menu').getByRole('button',{name:'预览',exact:true}).count()===1,'Card menus remain clickable');await page.keyboard.press('Escape');
 const oldRequests=requests;await page.getByRole('button',{name:'更新订阅 · Atlas',exact:true}).click();
 await until(async()=>requests>oldRequests&&!(await call('state')).subscriptionUpdating,'subscription update');
 check((await order()).join(',')===beforeTime.join(',')&&(await call('readProfile',{id:'p0'})).includes('# updated subscription'),'Updating a draggable card preserves its manual position');
 holdUpdates=true;const update=call('updateProfile',{id:'p2'});await until(()=>!!pendingResponse,'held subscription download');
 await config('p0').scrollIntoViewIfNeeded();const from=await config('p0').boundingBox();await page.mouse.move(from.x+25,from.y+80);await page.mouse.down();await page.mouse.move(from.x+60,from.y+80,{steps:5});
 await call('createProfile',{name:'New during drag',content});const added=(await call('state')).configs.find(profile=>profile.name==='New during drag').id;
 await call('renameProfile',{id:'p3',name:'Draco edited during drag'});await call('removeProfile',{id:'p8'});
 await config('p3').scrollIntoViewIfNeeded();const destination=await config('p3').boundingBox();await page.mouse.move(destination.x+25,destination.y+80,{steps:10});await page.mouse.up();
 await until(async()=>{const ids=(await call('state')).configs.map(profile=>profile.id);return ids.indexOf('p0')===ids.indexOf('p3')-1;},'concurrent drag save');
 respond(pendingResponse);pendingResponse=null;holdUpdates=false;await update;
 const latest=await call('state');
 check(latest.configs.length===10&&latest.configs.some(profile=>profile.id===added)&&!latest.configs.some(profile=>profile.id==='p8')&&latest.configs.find(profile=>profile.id==='p3').name==='Draco edited during drag','Dragging retains simultaneous additions and edits and respects deletions');
 check((await call('readProfile',{id:'p2'})).includes('# updated subscription')&&latest.configs.findIndex(profile=>profile.id==='p0')===latest.configs.findIndex(profile=>profile.id==='p3')-1,'A pending subscription response preserves the newly saved order');
 await call('reorderLibrary',{kind:'config',id:'p0',anchor:'deleted-anchor',after:true});
 check((await call('state')).configs.map(profile=>profile.id).join(',')===latest.configs.map(profile=>profile.id).join(','),'A deleted drag anchor is ignored without losing configurations');
 await call('sortLibrary',{order:'updated'});await until(async()=>(await call('state')).librarySort==='updated','updated sort');
 const sorted=(await call('state')).configs.map(profile=>profile.id);await drag(sorted[0],sorted[1],true);
 check((await call('state')).librarySort==='default','Dragging under recent-update sort also returns to manual order');
 await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(window=>window.webContents.getURL().includes('index.html')).setSize(800,560));await pause(300);
 const narrow=await order();await drag(narrow[0],narrow[1],true);
 check(await page.locator('.wbox-library-grid').evaluate(grid=>getComputedStyle(grid).gridTemplateColumns.split(/\s+/).length)===1,'Direct drag also reorders the single-column minimum window');
 check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Dragging cards and insertion markers fit the minimum window width');
 await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(window=>window.webContents.getURL().includes('index.html')).setSize(1080,820));await pause(300);
 const overviewState=await call('state'),overviewSelected=overviewState.configs.find(profile=>profile.path===overviewState.configPath);
 await page.evaluate(()=>location.hash='#/overview');await page.getByRole('button',{name:overviewSelected.name+' · '+overviewSelected.core,exact:true}).click();
 check((await page.getByRole('listbox').getByRole('option').allTextContents()).map(text=>text.trim()).join(',')===overviewState.configs.map(profile=>profile.name+' · '+profile.core).join(','),'The actual overview configuration dropdown displays the same manual order');
 await page.keyboard.press('Escape');await page.getByRole('button',{name:'配置',exact:true}).click();await page.locator('.wbox-library-card').first().waitFor();
 const persisted=await order(),selected=(await call('state')).configPath;
 await page.evaluate(()=>document.activeElement?.blur());await pause(350);await page.screenshot({path:join(root,'docs/previews/0.7.5-windows-order.png')});
 await app.close();app=null;await launch();
 check((await order()).join(',')===persisted.join(',')&&(await call('state')).configPath===selected&&(await call('state')).librarySort==='default','Manual order and selection survive an application restart');
 const saved=JSON.parse(await readFile(join(data,'settings.json'),'utf8'));
 check(saved.Configs.map(profile=>profile.Id).join(',')===persisted.join(','),'Manual order is stored in the actual preferences file');
 await call('settings',{mode:'nodes'});await page.getByRole('button',{name:'节点与订阅',exact:true}).click();
 const nodeCards=page.locator('.wbox-library-card');await nodeCards.first().waitFor();const lastNode=await nodeCards.nth(2).boundingBox();
 const nodeColumns=await page.locator('.wbox-library-grid').evaluate(grid=>getComputedStyle(grid).gridTemplateColumns.split(/\s+/).length);
 await nodeCards.nth(0).dragTo(nodeCards.nth(2),{sourcePosition:{x:25,y:80},targetPosition:nodeColumns>1?{x:lastNode.width-25,y:80}:{x:25,y:lastNode.height-25}});
 await until(async()=>(await call('state')).subscriptions.at(-1)===subscriptions[0],'subscription card drag');
 check((await call('state')).subscriptions.join(',')===[subscriptions[1],subscriptions[2],subscriptions[0]].join(','),'Node subscription cards use the same direct drag behavior');
 check(errors.length===0,'No renderer errors');
}catch(error){failure=error.stack;console.error(error);}finally{
 if(pendingResponse)respond(pendingResponse);if(app){try{await call('stop');}catch{}await app.close();}
 await new Promise(resolve=>server.close(resolve));assert.ok(data.startsWith(join(root,'.tools')+'\\'));await rm(data,{recursive:true,force:true});
 await writeFile(join(root,'docs/verification/0.7.5-windows-order.json'),JSON.stringify({passed:!failure,checks,errors,failure,build:process.env.WBOX_EXE?'packaged':'development',screenshots:['docs/previews/0.7.5-windows-order.png'],isolatedDataRemoved:true},null,2)+'\n');
}
if(failure)process.exitCode=1;

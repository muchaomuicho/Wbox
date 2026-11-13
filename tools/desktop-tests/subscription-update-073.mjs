import {_electron as electron} from 'playwright';
import {mkdir,writeFile,rm} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {once} from 'node:events';
import assert from 'node:assert/strict';
import http from 'node:http';

const root=resolve(import.meta.dirname,'../..');
const data=join(root,`.tools/subscription-update-073-runtime-${process.pid}`);
const previews=join(root,'docs/previews');
const checks=[],rendererErrors=[],screenshots=[];
const check=(ok,name)=>{assert.ok(ok,name);checks.push(name);console.log('PASS',name);};
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(predicate,label,timeout=12000){const start=Date.now();while(!await predicate()){if(Date.now()-start>timeout)throw new Error(`Timed out: ${label}`);await delay(40);}}
const resources=new Map();
for(const name of ['config-a','config-b','nodes-a','nodes-b'])resources.set(name,{name,count:0,version:1,hold:false,status:200,pending:[]});
const configBody=(name,version)=>`# Synthetic ${name} revision ${version}\nmixed-port: 7890\nproxies: []\nrules:\n  - MATCH,DIRECT\n`;
const body=(name,version)=>name.startsWith('config')?configBody(name,version):`trojan://synthetic@127.0.0.1:19101#${name}-revision-${version}`;
const release=resource=>{resource.hold=false;for(const finish of resource.pending.splice(0))finish();};
const server=http.createServer((request,response)=>{
  const resource=resources.get(request.url.slice(1));
  if(!resource){response.writeHead(404);response.end();return;}
  resource.count++;
  const {name,version,status}=resource;
  const finish=()=>{response.writeHead(status,{'Content-Type':'text/plain; charset=utf-8','Subscription-Userinfo':`upload=1073741824; download=${version*1073741824}; total=107374182400; expire=1893456000`});response.end(status===200?body(name,version):'Synthetic service unavailable');};
  if(resource.hold)resource.pending.push(finish);else finish();
});
let app,page,appProcess;
await mkdir(data,{recursive:true});await mkdir(previews,{recursive:true});
await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',SystemProxy:false,Silent:false,AutoConnect:false,SubscriptionAutoUpdate:false,Configs:[]}));
try {
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const url=name=>`http://127.0.0.1:${server.address().port}/${name}`;
  app=await electron.launch({executablePath:process.env.WBOX_EXE||join(root,'desktop/node_modules/electron/dist/electron.exe'),args:process.env.WBOX_EXE?[]:[join(root,'desktop/out/main/index.js')],env:{...process.env,WBOX_DATA_DIR:data}});
  appProcess=app.process();
  await until(()=>{page=app.windows().find(window=>window.url().includes('index.html'));return page;},'main window');
  page.setDefaultTimeout(12000);page.on('pageerror',error=>rendererErrors.push(error.message));
  await page.waitForFunction(()=>!!window.wbox);
  const call=(method,args={})=>page.evaluate(({method,args})=>window.wbox.call(method,args),{method,args});
  const update=name=>page.getByRole('button',{name:`更新订阅 · ${name}`,exact:true});
  const busy=async(name,value=true)=>until(async()=>await update(name).getAttribute('aria-busy')===String(value),`${name} busy=${value}`);
  const allIdle=()=>until(async()=>!(await call('state')).subscriptionUpdating,'backend update complete');
  const configPage=async()=>{await call('settings',{mode:'config'});await page.getByRole('button',{name:'配置',exact:true}).click();await update('远程配置 A').waitFor();};
  const nodePage=async()=>{await call('settings',{mode:'nodes'});await page.getByRole('button',{name:'节点与订阅',exact:true}).click();await update('节点订阅 A').waitFor();};
  const shot=async name=>{const path=join(previews,`0.7.3-windows-${name}.png`);await page.evaluate(()=>{document.activeElement?.blur();return Promise.all(document.getAnimations().filter(animation=>Number.isFinite(animation.effect?.getTiming().iterations)).map(animation=>animation.finished.catch(()=>{})));});await page.screenshot({path});screenshots.push(path.slice(root.length+1).replaceAll('\\','/'));};
  const noGeneric=async()=>check(await page.getByText('正在处理…',{exact:true}).count()===0,'Subscription update does not show generic processing text');
  await page.evaluate(()=>{localStorage.setItem('wbox.theme',JSON.stringify('light'));localStorage.setItem('wbox.accent',JSON.stringify('blue'));});
  await page.reload();
  for(const [name,label] of [['config-a','远程配置 A'],['config-b','远程配置 B']])await call('createProfile',{name:label,remoteUrl:url(name)});
  await call('createProfile',{name:'本地配置',content:configBody('local',1)});
  for(const [name,label] of [['nodes-a','节点订阅 A'],['nodes-b','节点订阅 B']]){
    await call('importNodes',{url:url(name)});
    await call('editSubscription',{id:'',oldUrl:url(name),url:url(name),name:label,autoUpdate:false,updateMinutes:1440});
  }
  const seeded=await call('state');
  const configA=seeded.configs.find(config=>config.name==='远程配置 A');
  const configB=seeded.configs.find(config=>config.name==='远程配置 B');
  check(!seeded.systemProxy&&!seeded.running,'Isolated fixture never starts a core or enables system proxy');
  await configPage();
  check(await update('本地配置').count()===0,'Local configurations do not receive a subscription update button');
  await page.getByRole('button',{name:'配置操作 · 远程配置 A',exact:true}).click();
  check(await page.getByRole('menu').getByRole('button',{name:'同步',exact:true}).count()===0,'Remote configuration overflow menu has no Sync action');
  check(await page.getByRole('menu').getByRole('button',{name:'预览',exact:true}).isVisible(),'Other configuration menu actions remain available');
  await page.keyboard.press('Escape');

  const a=resources.get('config-a');a.version=2;a.hold=true;const beforeA=a.count;
  const firstFrame=await update('远程配置 A').evaluate(button=>new Promise(resolve=>{button.click();button.click();requestAnimationFrame(()=>resolve({busy:button.getAttribute('aria-busy'),disabled:button.disabled}));}));
  check(firstFrame.busy==='true'&&firstFrame.disabled,'Single update responds on the first animation frame and immediately locks its source');
  await until(()=>a.count===beforeA+1,'single request reached server');await delay(250);
  check(a.count===beforeA+1,'Two immediate clicks produce only one download');
  check(await update('远程配置 B').isEnabled()&&await update('远程配置 B').getAttribute('aria-busy')==='false','An unrelated card remains idle and enabled');
  const pending=await call('state');
  check(pending.subscriptionUpdatingSources.length===1&&pending.subscriptionUpdatingSources[0]===`config:${configA.id}`,'Backend reports only the source with a pending download');
  await noGeneric();
  const ring=update('远程配置 A').locator('.wbox-subscription-update-ring');
  const motion=await ring.evaluate(async element=>{const style=getComputedStyle(element),first=style.transform;await new Promise(resolve=>setTimeout(resolve,230));return {linear:style.animationTimingFunction,infinite:style.animationIterationCount,duration:style.animationDuration,moved:first!==getComputedStyle(element).transform,same:element.isConnected,size:[element.clientWidth,element.clientHeight]};});
  check(motion.linear==='linear'&&motion.infinite==='infinite'&&motion.moved&&motion.same,'Update ring rotates continuously without rebuilding its DOM element');
  await shot('subscription-updating-light');
  await page.getByRole('button',{name:'设置',exact:true}).click();await page.getByRole('button',{name:'配置',exact:true}).click();await busy('远程配置 A');
  check(await update('远程配置 A').isDisabled(),'Returning from another page preserves the update state');
  await page.reload();await page.getByRole('button',{name:'配置',exact:true}).click();await busy('远程配置 A');
  check(await update('远程配置 B').isEnabled(),'Reload recovers per-source progress without blocking the other card');
  release(a);await busy('远程配置 A',false);await allIdle();
  check((await call('readProfile',{id:configA.id})).includes('revision 2'),'Successful update applies the new remote configuration');
  check((await call('state')).configs.find(config=>config.id===configA.id).subscription.usage.download===2*1073741824,'Successful update refreshes subscription traffic');

  const savedA=await call('readProfile',{id:configA.id});a.status=503;
  await update('远程配置 A').click();await page.getByRole('dialog').waitFor();await busy('远程配置 A',false);await allIdle();
  check((await page.getByRole('dialog').innerText()).includes('503'),'Download failure is shown through the existing error dialog');
  check(await call('readProfile',{id:configA.id})===savedA,'Failed download preserves the original configuration');
  await page.getByRole('dialog').getByRole('button').last().click();
  a.status=200;a.version=3;await update('远程配置 A').click();await busy('远程配置 A',false);
  check((await call('readProfile',{id:configA.id})).includes('revision 3'),'A failed update can be retried successfully');

  await nodePage();
  await page.getByRole('button',{name:'订阅操作 · 节点订阅 A',exact:true}).click();
  check(await page.getByRole('menu').getByRole('button',{name:'同步',exact:true}).count()===0,'Node subscription overflow menu has no Sync action');
  check(await page.getByRole('menu').getByRole('button',{name:'编辑订阅',exact:true}).isVisible(),'Node subscription editing remains available');
  await page.keyboard.press('Escape');
  const n=resources.get('nodes-a');n.version=2;n.hold=true;const beforeN=n.count;
  await update('节点订阅 A').evaluate(button=>{button.click();button.click();});await busy('节点订阅 A');await until(()=>n.count===beforeN+1,'node request');
  await delay(200);check(n.count===beforeN+1&&await update('节点订阅 B').isEnabled(),'Node update also deduplicates while leaving unrelated subscriptions enabled');
  check((await call('state')).subscriptionUpdatingSources.includes(`subscription:${url('nodes-a')}`),'Node update reports its real source in backend state');
  await noGeneric();release(n);await busy('节点订阅 A',false);await allIdle();
  check((await call('state')).nodes.some(node=>node.name==='nodes-a-revision-2'),'Successful node update replaces the downloaded nodes');
  const savedNodes=(await call('state')).nodes.filter(node=>node.sourceSubscription===url('nodes-a'));
  n.status=503;await update('节点订阅 A').click();await page.getByRole('dialog').waitFor();await busy('节点订阅 A',false);
  check(JSON.stringify((await call('state')).nodes.filter(node=>node.sourceSubscription===url('nodes-a')))===JSON.stringify(savedNodes),'Node download failure keeps existing nodes and unlocks the update button');
  await page.getByRole('dialog').getByRole('button').last().click();n.status=200;n.version=3;
  await update('节点订阅 A').click();await busy('节点订阅 A',false);
  check((await call('state')).nodes.some(node=>node.name==='nodes-a-revision-3'),'Node subscription recovers on the next successful update');

  await configPage();for(const resource of resources.values()){resource.version=4;resource.hold=true;}
  const batchCount=a.count;
  await page.getByRole('button',{name:'全部更新',exact:true}).first().click();
  await until(()=>a.count===batchCount+1,'batch request');await delay(500);
  const batch=await call('state');
  check(batch.subscriptionUpdating&&batch.subscriptionUpdatingSources.length===4,'Accepted batch update stays active and includes each remote source');
  await busy('远程配置 A');await busy('远程配置 B');
  check(await page.getByRole('button',{name:'全部更新',exact:true}).first().getAttribute('aria-busy')==='true','All-update button stays busy after the start request returns');
  await noGeneric();
  await page.evaluate(()=>{localStorage.setItem('wbox.theme',JSON.stringify('dark'));localStorage.setItem('wbox.accent',JSON.stringify('pink'));});
  await page.reload();await page.getByRole('button',{name:'配置',exact:true}).click();await busy('远程配置 A');await shot('subscription-updating-dark');
  await nodePage();await busy('节点订阅 A');await busy('节点订阅 B');
  check(await update('节点订阅 A').isDisabled()&&await update('节点订阅 B').isDisabled(),'Batch targets preserve their pending state when switching workspaces');
  await shot('node-subscription-updating-dark');
  for(const resource of resources.values())release(resource);
  await allIdle();await busy('节点订阅 A',false);await busy('节点订阅 B',false);
  check((await call('state')).nodes.filter(node=>node.name.endsWith('revision-4')).length===2,'Batch completes all node updates before clearing progress');
  await configPage();await busy('远程配置 A',false);await busy('远程配置 B',false);
  check((await call('readProfile',{id:configA.id})).includes('revision 4')&&(await call('readProfile',{id:configB.id})).includes('revision 4'),'Batch completes both configuration updates');

  const b=resources.get('config-b');b.status=503;const savedB=await call('readProfile',{id:configB.id});
  await page.getByRole('button',{name:'全部更新',exact:true}).first().click();await allIdle();
  await until(async()=>await page.getByRole('button',{name:'全部更新',exact:true}).first().getAttribute('aria-busy')==='false','batch error spinner ends');
  check((await call('state')).configs.find(config=>config.id===configB.id).subscription.error.length>0,'Partial batch failure appears on the affected card');
  check(await call('readProfile',{id:configB.id})===savedB,'Partial batch failure retains the affected configuration');
  b.status=200;b.version=5;await page.getByRole('button',{name:'全部更新',exact:true}).first().click();await allIdle();
  await until(async()=>await page.getByRole('button',{name:'全部更新',exact:true}).first().getAttribute('aria-busy')==='false','batch recovery');
  check((await call('state')).configs.find(config=>config.id===configB.id).subscription.error===''&&(await call('readProfile',{id:configB.id})).includes('revision 5'),'Retrying a failed batch clears the card error and refreshes its content');
  await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(window=>window.webContents.getURL().includes('index.html')).setSize(800,560));
  await delay(450);
  const toolbar=await page.getByRole('button',{name:'全部更新',exact:true}).first().evaluate(button=>{const update=button.getBoundingClientRect(),picker=document.querySelector('.wbox-workspace-picker').getBoundingClientRect();return {updateLeft:update.left,pickerRight:picker.right,controlsWidth:getComputedStyle(document.documentElement).getPropertyValue('--window-controls-width'),viewport:innerWidth};});
  check(toolbar.updateLeft>toolbar.pickerRight,'Toolbar update button does not overlap the workspace picker after window resize');
  check(await page.locator('.wbox-library-card').evaluateAll(cards=>cards.every(card=>card.scrollWidth<=card.clientWidth+1)),'Dedicated circular controls fit cards at the minimum window size');
  await shot('subscription-updated-compact');
  check(rendererErrors.length===0,'No renderer errors during subscription update checks');
  const result={build:process.env.WBOX_EXE?'packaged':'development',passed:checks.length,checks,rendererErrors,animation:motion,compactToolbar:toolbar,screenshots};
  await writeFile(join(root,`docs/verification/0.7.3-windows-subscription-update${process.env.WBOX_EXE?'-packaged':''}.json`),JSON.stringify(result,null,2)+'\n');
  console.log(JSON.stringify(result));
} finally {
  for(const resource of resources.values())release(resource);
  server.closeAllConnections();server.close();
  if(app){await Promise.race([app.close(),delay(5000)]);if(appProcess.exitCode===null){appProcess.kill();await once(appProcess,'exit');}}
  assert(data.startsWith(join(root,'.tools','subscription-update-073-runtime-')));
  await rm(data,{recursive:true,force:true});
}

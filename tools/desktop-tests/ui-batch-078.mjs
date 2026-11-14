import {_electron as electron} from 'playwright';
import {mkdir,writeFile,rm} from 'node:fs/promises';
import {createServer} from 'node:http';
import {connect} from 'node:net';
import {resolve,join} from 'node:path';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'../..'),data=join(root,`.tools/ui-batch-078-${process.pid}`);
const checks=[],errors=[],measurements={},sockets=new Set();
const names=['Harbor A','Harbor B','Harbor C','Harbor D','Harbor E'];
const held=names.map(()=>false),waiting=names.map(()=>new Set()),latency=names.map(()=>35);
let app,page,failure=null;
const check=(ok,label)=>{assert.ok(ok,label);checks.push(label);console.log('PASS',label);};
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(fn,label,timeout=20000){const end=Date.now()+timeout;while(!await fn()){if(Date.now()>end)throw Error('Timed out '+label);await pause(25);}}
const call=(method,args={})=>page.evaluate(({method,args})=>window.wbox.call(method,args),{method,args});
const release=index=>{held[index]=false;for(const finish of waiting[index])finish();waiting[index].clear();};
const releaseAll=()=>names.forEach((_,i)=>release(i));
const holdAll=()=>held.fill(true);
const servers=await Promise.all(names.map(async(_,index)=>{
  const server=createServer((_request,response)=>{
    const finish=()=>{if(!response.destroyed)setTimeout(()=>{response.writeHead(204);response.end();},latency[index]);};
    if(held[index]){waiting[index].add(finish);response.on('close',()=>waiting[index].delete(finish));}else finish();
  });
  server.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));});
  server.on('connect',(_request,client,head)=>{
    const upstream=connect(server.address().port,'127.0.0.1',()=>{client.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length)upstream.write(head);client.pipe(upstream).pipe(client);});
    upstream.on('error',()=>client.destroy());client.on('error',()=>upstream.destroy());client.on('close',()=>upstream.destroy());
  });
  await new Promise(done=>server.listen(0,'127.0.0.1',done));return server;
}));
const content=JSON.stringify({proxies:names.map((name,index)=>({name,type:'http',server:'127.0.0.1',port:servers[index].address().port})),'proxy-groups':[{name:'Main',type:'select',proxies:names}],rules:['MATCH,Main']});
const row=index=>page.locator('.wbox-node-table tbody tr').nth(index);
const nodeButton=index=>row(index).locator('.wbox-node-delay');
const groupButton=index=>page.getByRole('button',{name:`测试节点 ${names[index]} 的延迟`,exact:true});
const snapshot=()=>page.locator('.wbox-node-delay').evaluateAll(buttons=>buttons.map(button=>({text:button.textContent.trim(),spinner:!!button.querySelector('.spinner')})));
async function allDone(label){await until(async()=>!(await call('state')).delayTesting&&!(await snapshot()).some(item=>item.spinner)&&await page.locator('.wbox-delay-action .spinner').count()===0,label);}
async function nextFrameClick(button){return button.evaluate(async element=>{
  const start=performance.now();element.click();await new Promise(done=>requestAnimationFrame(()=>requestAnimationFrame(done)));
  return {elapsedMs:performance.now()-start,items:[...document.querySelectorAll('.wbox-node-delay')].map(button=>({text:button.textContent.trim(),spinner:!!button.querySelector('.spinner')}))};
});}
async function singleThenBatchBaseline(button,batch,label){
  await button.click();await allDone(label+' single baseline');
  await until(async()=>/\d+\s*ms/.test(await button.innerText()),label+' single numeric result');
  await batch.click();await allDone(label+' batch baseline');
  check((await snapshot()).length===names.length&&(await snapshot()).every(item=>/\d+\s*ms/.test(item.text)),label+' obtains all baseline results');
  // Exercise the former single-result cache immediately before the new batch.
  await button.click();await allDone(label+' repeated single baseline');
}
async function verifyBatch(batch,label){
  const before=await snapshot(),selection=await call('state');
  holdAll();latency.fill(170);
  const immediate=await nextFrameClick(batch);measurements[label+'Immediate']=immediate;
  check(immediate.items.length===names.length&&immediate.items.every(item=>item.spinner&&!item.text),label+' shows every target spinner on the first painted frame, including queued targets');
  await until(async()=>(await call('state')).delayTesting,label+' begins');
  await until(()=>waiting[0].size>0,label+' first request waits');
  release(0);
  const firstStart=Date.now();await until(async()=>{const items=await snapshot();return !items[0].spinner&&/\d+\s*ms/.test(items[0].text);},label+' first result');
  const partial=await snapshot();measurements[label+'Partial']={elapsedMs:Date.now()-firstStart,items:partial};
  check(Number.parseInt(partial[0].text)>=140&&partial[0].text!==before[0].text&&partial.slice(1).every(item=>item.spinner&&!item.text),label+' replaces the old single result while unfinished targets keep spinning');
  check((await call('state')).delayTesting,label+' publishes the first value before the batch finishes');
  if(label==='Groups')await page.screenshot({path:join(root,'docs/previews/0.7.8-windows-batch.png')});
  releaseAll();await allDone(label+' completion');
  check((await snapshot()).every(item=>!item.spinner&&Number.parseInt(item.text)>=140),label+' leaves every completed target on its fresh value');
  const after=await call('state');check(after.selectedNode===selection.selectedNode&&after.running===selection.running,label+' preserves node selection and connection state');
  return await snapshot();
}
async function verifyCancel(batch,cancel,label){
  const before=await snapshot();holdAll();await batch.click();
  await until(async()=>(await snapshot()).every(item=>item.spinner),label+' cancellation batch');
  const began=Date.now();await cancel.click();
  await until(async()=>!(await call('state')).delayTesting&&!(await snapshot()).some(item=>item.spinner),label+' cancellation clears');
  measurements[label+'CancelMs']=Date.now()-began;
  check((await snapshot()).every((item,index)=>item.text===before[index].text||(label==='Nodes'&&!item.text)),label+' cancellation clears pending indicators and shows only retained results');
  releaseAll();await allDone(label+' cancellation action settles');
}
try{
  await mkdir(data,{recursive:true});
  const profiles=[0,1].map(index=>({Id:'profile'+index,Name:'Batch profile '+index,Path:join(data,`profile${index}.json`),Core:'mihomo',Url:''}));
  for(const profile of profiles)await writeFile(profile.Path,content);
  await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'nodes',Core:'mihomo',NodeCore:'mihomo',ConfigPath:profiles[0].Path,SystemProxy:false,NativeInbounds:false,Silent:false,AutoConnect:false,Configs:profiles,DelayUrl:'http://93.184.216.34/latency',SubscriptionSettingsMigrated:true}));
  app=await electron.launch({executablePath:process.env.WBOX_EXE||join(root,'desktop/node_modules/electron/dist/electron.exe'),args:process.env.WBOX_EXE?[]:[join(root,'desktop/out/main/index.js')],env:{...process.env,WBOX_DATA_DIR:data}});
  await until(()=>{page=app.windows().find(window=>window.url().includes('index.html'));return page;},'main window');
  page.setDefaultTimeout(15000);page.on('pageerror',error=>errors.push(error.message));await page.waitForFunction(()=>!!window.wbox);
  await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(window=>window.webContents.getURL().includes('index.html')).setSize(1100,820));
  await call('importInput',{text:names.map((name,index)=>`http://127.0.0.1:${servers[index].address().port}#${encodeURIComponent(name)}`).join('\n')});
  await page.getByRole('button',{name:'节点与订阅',exact:true}).click();await row(4).waitFor();
  await row(4).locator('td').nth(1).click();await until(async()=>(await call('state')).selectedNode===4,'offline selection');
  await row(0).click({button:'right'});const menu=page.locator('.context-menu[role="menu"]');await menu.waitFor();
  const actions=(await menu.getByRole('menuitem').allTextContents()).map(text=>text.replace(/Ctrl\+D/g,'').trim());
  check(actions.join(',')==='编辑节点,克隆为手动节点,移除节点','Node context menu retains edit and clone, removes connect');
  const danger=await menu.getByRole('menuitem',{name:'移除节点',exact:true}).evaluate(button=>{const color=getComputedStyle(button).color;const expected=document.createElement('span');expected.style.color='var(--danger)';button.append(expected);const result={danger:button.classList.contains('danger'),color,expected:getComputedStyle(expected).color};expected.remove();return result;});
  check(danger.danger&&danger.color===danger.expected,'Remove node uses the existing red danger menu style');
  check((await call('state')).selectedNode===4&&!(await call('state')).running,'Opening another node menu does not select or connect it');
  await page.screenshot({path:join(root,'docs/previews/0.7.8-windows-node-menu.png')});await page.keyboard.press('Escape');
  const nodeBatch=page.getByRole('button',{name:'测试全部节点',exact:true});
  await singleThenBatchBaseline(nodeButton(0),nodeBatch,'Nodes');
  await verifyBatch(nodeBatch,'Nodes');
  await verifyCancel(nodeBatch,page.getByRole('button',{name:'取消测速',exact:true}),'Nodes');
  await call('settings',{mode:'config'});await call('selectProfile',{id:profiles[0].Id});
  await page.getByRole('button',{name:'代理',exact:true}).click();await groupButton(4).waitFor();
  latency.fill(35);const groupBatch=page.locator('.wbox-delay-action');
  await singleThenBatchBaseline(groupButton(0),groupBatch,'Groups');
  await verifyBatch(groupBatch,'Groups');
  await verifyCancel(groupBatch,page.getByRole('button',{name:'取消测试',exact:true}),'Groups');
  const selection=(await call('groups'))[0].selected;holdAll();await groupBatch.click();
  await until(async()=>(await snapshot()).every(item=>item.spinner),'profile isolation batch');
  await call('selectProfile',{id:profiles[1].Id});
  await until(async()=>(await snapshot()).length===names.length&&(await snapshot()).every(item=>!item.spinner&&!item.text),'different profile pending isolation');
  check((await call('state')).delayTesting,'Switching to another profile does not display or cancel the first profile batch');
  await call('selectProfile',{id:profiles[0].Id});await until(async()=>(await snapshot()).every(item=>item.spinner),'returning profile pending');
  await call('cancelTest');releaseAll();await allDone('profile isolation cancellation');
  check((await call('groups'))[0].selected===selection&&!(await call('state')).running,'Batch and profile navigation preserve the selected proxy and offline connection');
  check(errors.length===0,'No renderer errors');
}catch(error){failure=error.stack;console.error(error);if(page)try{measurements.failureState=await call('state');measurements.failureGroups=await call('groups');measurements.failureUi=await snapshot();}catch{}}
finally{
  releaseAll();if(app){try{await call('cancelTest');await call('stop');}catch{}await app.close();}
  for(const socket of sockets)socket.destroy();for(const server of servers){server.closeAllConnections();await new Promise(done=>server.close(done));}
  assert(data.startsWith(join(root,'.tools','ui-batch-078-')));await rm(data,{recursive:true,force:true});
  await mkdir(join(root,'docs/verification'),{recursive:true});
  await writeFile(join(root,'docs/verification/0.7.8-windows-batch-ui.json'),JSON.stringify({passed:!failure,checks,errors,measurements,failure,build:process.env.WBOX_EXE?'packaged':'development',screenshots:['docs/previews/0.7.8-windows-batch.png','docs/previews/0.7.8-windows-node-menu.png'],isolatedDataRemoved:true},null,2)+'\n');
}
if(failure)process.exitCode=1;

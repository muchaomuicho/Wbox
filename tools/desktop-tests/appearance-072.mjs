import {_electron as electron} from 'playwright';
import {mkdir,writeFile,rm} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import http from 'node:http';
const root=resolve(import.meta.dirname,'../..'),data=join(root,`.tools/appearance-072-runtime-${process.pid}`);
const shots=join(root,'.tools/windows-preview-check-shots');
await mkdir(data,{recursive:true});await mkdir(shots,{recursive:true});
await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',SystemProxy:false,Silent:false,AutoConnect:false,Configs:[]}));
let app,page,appProcess,subscriptionServer;const checks=[],pageErrors=[];
const check=(value,name)=>{assert(value,name);checks.push(name);console.log('PASS',name);};
try {
 app=await electron.launch({executablePath:process.env.WBOX_EXE||join(root,'desktop/node_modules/electron/dist/electron.exe'),args:process.env.WBOX_EXE?[]:[join(root,'desktop/out/main/index.js')],env:{...process.env,WBOX_DATA_DIR:data}});
 appProcess=app.process();
 for(let i=0;i<100;i++){page=app.windows().find(p=>p.url().includes('index.html'));if(page)break;await new Promise(r=>setTimeout(r,100));}assert(page,'main window opened');page.setDefaultTimeout(12000);page.on('pageerror',error=>pageErrors.push(error.message));await page.waitForFunction(()=>!!window.wbox);console.log('Main window ready');
 const call=(method,args={})=>page.evaluate(({method,args})=>window.wbox.call(method,args),{method,args});
 check(await page.evaluate(()=>document.documentElement.dataset.accent)==='blue','A new profile defaults to soft blue');
 await page.evaluate(()=>{localStorage.removeItem('wbox.accent');localStorage.setItem('wbox.theme',JSON.stringify('dark'));});await page.reload();await page.waitForFunction(()=>document.documentElement.dataset.accent==='default');check(true,'An existing profile with no stored accent keeps black-and-white');
 await page.evaluate(()=>localStorage.setItem('wbox.accent',JSON.stringify('blue')));await page.reload();
 const contents={JSON:JSON.stringify({log:{level:'info',timestamp:true},outbounds:[{type:'direct',tag:'direct'},...Array.from({length:80},(_,i)=>({type:'http',tag:`Node-${i}`,server:'example.org',server_port:8080,password:'long-config-value-'.repeat(25)}))]},null,2),YAML:'mixed-port: 7890\nallow-lan: false\nmode: rule\nlog-level: info\n# Synthetic preview configuration\nproxies:\n'+Array.from({length:80},(_,i)=>`  - name: Node-${i}\n    type: http\n    server: example.org\n    port: 8080\n    password: '+long-value-${'x'.repeat(150)}'`).join('\n')+'\nproxy-groups:\n  - name: Select\n    type: select\n    proxies: [DIRECT]\nrules:\n  - MATCH,DIRECT\n'};
 for(const [name,content] of Object.entries(contents)){console.log('Creating',name);await call('createProfile',{name:`Preview ${name}`,content});}console.log('Profiles ready');
 subscriptionServer=http.createServer((request,response)=>{if(request.url==='/traffic')response.setHeader('Subscription-Userinfo','upload=1073741824; download=2147483648; total=107374182400; expire=1893456000');response.setHeader('Content-Type','application/yaml');response.end('mixed-port: 7890\nproxies: []\nrules:\n  - MATCH,DIRECT\n');});await new Promise(resolve=>subscriptionServer.listen(0,'127.0.0.1',resolve));
 for(const [name,path] of [['Traffic plan','traffic'],['Missing traffic','missing']])await call('createProfile',{name,remoteUrl:`http://127.0.0.1:${subscriptionServer.address().port}/${path}`});
 await page.getByRole('button',{name:'配置',exact:true}).click();
 for(const theme of ['light','dark']){
  await page.evaluate(theme=>{localStorage.setItem('wbox.theme',JSON.stringify(theme));},theme);await page.reload();await page.getByRole('button',{name:'配置',exact:true}).click();
  await page.waitForFunction(theme=>document.documentElement.dataset.theme===theme,theme);
  for(const format of ['JSON','YAML']){
   await page.getByRole('button',{name:`配置操作 · Preview ${format}`,exact:true}).click();await page.getByText('预览',{exact:true}).click();
   const dialog=page.locator('dialog.wbox-config-preview');await dialog.locator('.cm-content').waitFor();
   const metrics=await dialog.evaluate(el=>{const cm=el.querySelector('.cm-content'),scroller=el.querySelector('.cm-scroller');const r=el.getBoundingClientRect();return {rect:{x:r.x,y:r.y,width:r.width,height:r.height},viewport:{width:innerWidth,height:innerHeight},readonly:cm.getAttribute('aria-readonly'),editable:cm.getAttribute('contenteditable'),wrap:getComputedStyle(cm).whiteSpace,colors:[...new Set([...cm.querySelectorAll('span')].map(s=>getComputedStyle(s).color))],scrollHeight:scroller.scrollHeight,clientHeight:scroller.clientHeight};});
   check(metrics.colors.length>=3,`${theme} ${format} syntax uses distinct colors`);
   check(metrics.readonly==='true'&&metrics.editable==='false',`${theme} ${format} preview is read-only`);
   check(await dialog.getAttribute('aria-label')===`预览 · Preview ${format}`,`${theme} ${format} dialog has its accessible title`);
   check(metrics.rect.x>=0&&metrics.rect.y>=0&&metrics.rect.x+metrics.rect.width<=metrics.viewport.width&&metrics.rect.y+metrics.rect.height<=metrics.viewport.height,`${theme} ${format} dialog fits the window`);
   check(metrics.scrollHeight>metrics.clientHeight,`${theme} ${format} long configuration scrolls within the preview`);
   check(['pre-wrap','break-spaces'].includes(metrics.wrap),`${theme} ${format} long lines wrap`);
   await dialog.locator('.cm-content').click({position:{x:180,y:30}});await page.keyboard.press('Control+f');await dialog.locator('.cm-search').waitFor();
   check(await dialog.locator('.cm-search').isVisible(),`${theme} ${format} Ctrl+F searches the preview`);
   await page.keyboard.press('Escape');
   await dialog.evaluate(el=>Promise.all(el.getAnimations().map(animation=>animation.finished)));await page.screenshot({path:join(shots,`${theme}-${format.toLowerCase()}.png`),animations:'disabled'});
   const before=await call('readProfile',{id:(await call('state')).configs.find(p=>p.name===`Preview ${format}`).id});
   await dialog.locator('.cm-content').click({position:{x:180,y:30}});await page.keyboard.type('should-not-change');
   check(!(await dialog.locator('.cm-content').innerText()).includes('should-not-change'),`${theme} ${format} typing cannot alter preview text`);
   await dialog.getByRole('button',{name:'关闭',exact:true}).click();
   await page.waitForFunction(label=>document.activeElement?.getAttribute('title')===label,`配置操作 · Preview ${format}`);check(true,`${theme} ${format} closing returns focus to its card action`);
   check(await call('readProfile',{id:(await call('state')).configs.find(p=>p.name===`Preview ${format}`).id})===before,`${theme} ${format} preview cannot modify stored configuration`);
  }
 }
 await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().includes('index.html')).setSize(800,560));
 await page.getByRole('button',{name:'配置操作 · Preview YAML',exact:true}).click();await page.getByText('预览',{exact:true}).click();
 const narrow=page.locator('dialog.wbox-config-preview');await narrow.locator('.cm-content').waitFor();
 const layout=await narrow.evaluate(el=>{const r=el.getBoundingClientRect(),header=el.querySelector('.wbox-config-header').getBoundingClientRect(),close=el.querySelector('button').getBoundingClientRect();return {fits:r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight,closeFits:close.right<=r.right&&close.left>=r.left,headerFits:header.width<=r.width,scroller:el.querySelector('.cm-scroller').clientHeight};});
 check(layout.fits&&layout.closeFits&&layout.headerFits&&layout.scroller>200,'Preview adapts to the 800×560 minimum window');
 await narrow.evaluate(el=>Promise.all(el.getAnimations().map(animation=>animation.finished)));await page.screenshot({path:join(shots,'dark-yaml-narrow.png'),animations:'disabled'});await page.keyboard.press('Escape');await narrow.waitFor({state:'detached'});check(true,'Escape closes the preview');
 await page.getByRole('button',{name:'配置操作 · Preview JSON',exact:true}).click();await page.getByText('编辑配置',{exact:true}).click();
 const edit=page.locator('dialog.wbox-config-dialog');await edit.locator('.cm-content[contenteditable="true"]').waitFor();
 check(await edit.getByRole('button',{name:'保存',exact:true}).isVisible(),'Editor remains editable with visible Save action');
 await edit.evaluate(el=>Promise.all(el.getAnimations().map(animation=>animation.finished)));await page.screenshot({path:join(shots,'dark-json-editor-narrow.png'),animations:'disabled'});await edit.getByRole('button',{name:'取消',exact:true}).click();
 await call('renameProfile',{id:(await call('state')).configs.find(p=>p.name==='Preview JSON').id,name:'A very long configuration name that must fit the compact preview header without hiding its close action'.slice(0,100)});
 await page.getByRole('button',{name:/配置操作 · A very long/}).click();await page.getByText('预览',{exact:true}).click();
 await page.locator('dialog .cm-content').waitFor();
 for(const zoom of [1,1.25,1.5]){
  await app.evaluate(({BrowserWindow},zoom)=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().includes('index.html')).webContents.setZoomFactor(zoom),zoom);
  const fits=await page.locator('dialog').evaluate(el=>{const rect=el.getBoundingClientRect(),button=el.querySelector('button').getBoundingClientRect();return rect.left>=0&&rect.right<=innerWidth&&rect.top>=0&&rect.bottom<=innerHeight&&button.right<=rect.right&&el.querySelector('.cm-scroller').clientHeight>100});check(fits,`Long filename preview fits at ${zoom*100}% zoom`);
 }
 await page.keyboard.press('Escape');await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().includes('index.html')).webContents.setZoomFactor(1));
 await page.getByRole('button',{name:'配置',exact:true}).click();
 for(const theme of ['light','dark']){
  await page.evaluate(theme=>localStorage.setItem('wbox.theme',JSON.stringify(theme)),theme);await page.reload();await page.getByRole('button',{name:'配置',exact:true}).click();
  for(const zoom of [1,1.25,1.5]){
   await app.evaluate(({BrowserWindow},zoom)=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().includes('index.html')).webContents.setZoomFactor(zoom),zoom);
   const readability=await page.locator('.wbox-library-card').filter({has:page.locator('strong',{hasText:'Traffic plan'})}).evaluate(el=>{const traffic=el.querySelector('.wbox-subscription-traffic'),small=traffic.querySelector('small'),meta=el.querySelector('.wbox-library-meta');return {trafficFont:parseFloat(getComputedStyle(small).fontSize),metaFont:parseFloat(getComputedStyle(meta).fontSize),secondary:getComputedStyle(el).getPropertyValue('--text-secondary').trim(),trafficColor:getComputedStyle(traffic).color,overflow:el.scrollWidth>el.clientWidth+1,text:traffic.textContent};});
   check(readability.trafficFont>=14&&readability.metaFont>=14&&!readability.overflow,`${theme} traffic and metadata stay readable at ${zoom*100}% zoom`);
   check(readability.text.includes('剩余')&&readability.text.includes('2030'),`${theme} remaining traffic and expiry remain visible at ${zoom*100}% zoom`);
   const missing=page.getByText('订阅未提供流量信息',{exact:true});check(await missing.evaluate(el=>parseFloat(getComputedStyle(el).fontSize))>=14,`${theme} missing traffic hint uses 14px at ${zoom*100}% zoom`);
  }
  await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().includes('index.html')).webContents.setZoomFactor(1));
  await page.locator('.wbox-library-card').filter({has:page.locator('strong',{hasText:'Traffic plan'})}).evaluate(el=>el.scrollIntoView({block:'center',behavior:'instant'}));await page.screenshot({path:join(shots,`${theme}-profile-traffic.png`),animations:'disabled'});
 }
 await page.getByRole('button',{name:'设置',exact:true}).click();
 for(const [label,accent] of [['雾蓝','blue'],['柔粉','pink'],['黑白','default']]){
  await page.getByRole('button',{name:label,exact:true}).click();await page.waitForFunction(accent=>document.documentElement.dataset.accent===accent,accent);
  check(await page.getByRole('button',{name:label,exact:true}).getAttribute('aria-pressed')==='true',`${label} accent applies through the stored preference`);
  await page.reload();await page.getByRole('button',{name:'设置',exact:true}).click();check(await page.evaluate(()=>document.documentElement.dataset.accent)===accent,`${label} accent survives reload`);
 }
 await page.getByRole('button',{name:'雾蓝',exact:true}).click();await page.getByText('外观',{exact:true}).scrollIntoViewIfNeeded();await page.screenshot({path:join(shots,'dark-settings-blue.png'),animations:'disabled'});
 check(pageErrors.length===0,'No renderer errors during appearance checks');
 const result={build:process.env.WBOX_EXE?'packaged':'development',passed:checks.length,checks,rendererErrors:pageErrors,screenshots:'.tools/windows-preview-check-shots'};await writeFile(join(root,`docs/verification/0.7.2-windows-appearance${process.env.WBOX_EXE?'-packaged':''}.json`),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
} catch(error){console.error('TEST ERROR',error);throw error;} finally {subscriptionServer?.close();if(app){await Promise.race([app.close(),new Promise(r=>setTimeout(r,5000))]);if(appProcess.exitCode===null){appProcess.kill();await once(appProcess,'exit');}}assert(data.startsWith(join(root,'.tools','appearance-072-runtime-')));await rm(data,{recursive:true,force:true});}

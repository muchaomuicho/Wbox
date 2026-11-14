import {_electron as electron} from 'playwright';
import {mkdir,writeFile,rm} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'../..'),data=join(root,`.tools/ui-time-075-${process.pid}`);
const checks=[],errors=[];let app,page,failure=null;
const check=(ok,label)=>{assert.ok(ok,label);checks.push(label);console.log('PASS',label);};
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn,label){const end=Date.now()+20000;while(!await fn()){if(Date.now()>end)throw Error('Timed out '+label);await pause(100);}}
const call=(method,args={})=>page.evaluate(({method,args})=>window.wbox.call(method,args),{method,args});
const now=Date.now(),day=86400000;
const updated=new Date(now-2*3600000).toISOString();
const future=Math.floor((now+18*day)/1000);
const configText='proxies: []\nproxy-groups:\n  - {name: Proxy, type: select, proxies: [DIRECT]}\nrules: ["MATCH,Proxy"]\n';
const usage=expire=>({Upload:1073741824,Download:2147483648,Total:107374182400,Expire:expire});
const options=expire=>({Name:'Example',AutoUpdate:false,UpdateMinutes:1440,LastAttempt:updated,LastSuccess:updated,Usage:usage(expire),Error:''});
const definitions=[['orion','Orion · 日常配置',future],['atlas','Atlas · 备用配置',future+3*86400],['permanent','长期订阅',0],['expired','过期订阅',Math.floor((now-day)/1000)],['missing','缺失时间',null]];
try{
 await mkdir(data,{recursive:true});
 const configs=[];
 for(const [Id,Name,expire] of definitions){const Path=join(data,`${Id}.yaml`);await writeFile(Path,configText);configs.push({Id,Name,Path,Core:'mihomo',Url:`https://example.invalid/${Id}`,Updated:updated,Subscription:options(expire)});}
 const nodeUrl='https://example.invalid/nodes';
 await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',Core:'mihomo',ConfigPath:configs[0].Path,SystemProxy:false,NativeInbounds:false,Silent:false,AutoConnect:false,Configs:configs,Subscriptions:[nodeUrl],SubscriptionSettingsMigrated:true,SubscriptionSettings:{[nodeUrl]:options(future)}}));
 app=await electron.launch({executablePath:process.env.WBOX_EXE||join(root,'desktop/node_modules/electron/dist/electron.exe'),args:process.env.WBOX_EXE?[]:[join(root,'desktop/out/main/index.js')],env:{...process.env,WBOX_DATA_DIR:data}});
 await until(()=>{page=app.windows().find(w=>w.url().includes('index.html'));return page;},'main window');
 page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(e.message));await page.waitForFunction(()=>!!window.wbox);
 await page.evaluate(()=>{localStorage.setItem('wbox.theme',JSON.stringify('light'));localStorage.setItem('wbox.accent',JSON.stringify('blue'));});await page.reload();
 await page.getByRole('button',{name:'配置',exact:true}).click();
 const card=name=>page.locator('.wbox-library-card').filter({has:page.getByText(name,{exact:true})});
 const first=card('Orion · 日常配置'),second=card('Atlas · 备用配置');
 const expiry=which=>which.locator('[data-time-kind="expiry"]'),update=which=>which.locator('[data-time-kind="updated"]');
 await expiry(first).waitFor();
 check(/^\d{4}\/\d{1,2}\/\d{1,2} 到期$/.test(await expiry(first).innerText()),'Expiry defaults to the exact date with 到期');
 check(await update(first).innerText()==='更新于 2 小时前','Update defaults to relative time with 更新于');
 const firstExpiry=await expiry(first).innerText(),secondExpiry=await expiry(second).innerText(),firstUpdated=await update(first).innerText();
 const before=await call('state');
 check(await page.getByText('0.7.5',{exact:true}).count()>0,'Displayed version matches 0.7.5');
 const cursor=await first.evaluate(card=>getComputedStyle(card).cursor);
 const pointer=await expiry(first).evaluate(button=>getComputedStyle(button).cursor);
 check(cursor.includes('svg')&&cursor.includes('16 15'),'Packaged cards load the SVG grab cursor and its hotspot');
 check(pointer.includes('svg')&&pointer.includes('13 4'),'Packaged time buttons load the SVG pointer and its hotspot');
 const decoded=await page.evaluate(async values=>Promise.all(values.map(value=>new Promise(resolve=>{
   const url=value.match(/url\(["']?(.*?)["']?\)/)?.[1];const img=new Image();img.onload=()=>resolve(img.naturalWidth===32);img.onerror=()=>resolve(false);img.src=url||'';
 }))),[cursor,pointer]);
 check(decoded.every(Boolean),'Cursor resources decode in the actual packaged renderer');
 await expiry(first).click();
 check(await expiry(first).innerText()==='18 天后到期','Expiry click displays remaining days');
 check(await expiry(second).innerText()===secondExpiry&&await update(first).innerText()===firstUpdated,'Time modes are independent between cards and kinds');
 await update(first).click();
 check(/^更新于 \d{4}\/\d{1,2}\/\d{1,2} \d{2}:\d{2}:\d{2}$/.test(await update(first).innerText()),'Update click displays the exact date and time');
 await expiry(first).focus();await page.keyboard.press('Enter');
 check(await expiry(first).innerText()===firstExpiry,'Expiry supports native button Enter activation');
 await update(first).focus();await page.keyboard.press('Space');
 check(await update(first).innerText()===firstUpdated,'Update supports native button Space activation');
 const after=await call('state');
 check(after.configPath===before.configPath&&!after.running,'Time clicks and keyboard activation preserve selection and do not connect');
 await expiry(first).click();await call('sortLibrary',{order:'name'});
 check(await expiry(first).innerText()==='18 天后到期'&&await expiry(second).innerText()===secondExpiry,'Mode stays attached to each card across state refresh and sorting');
 check(await card('长期订阅').getByText('长期有效',{exact:true}).count()===1&&await expiry(card('长期订阅')).count()===0,'Permanent expiry has no misleading toggle');
 check(await card('缺失时间').getByText('到期时间未提供',{exact:true}).count()===1&&await expiry(card('缺失时间')).count()===0,'Missing expiry remains readable without a toggle');
 await expiry(card('过期订阅')).click();check(await expiry(card('过期订阅')).innerText()==='已到期','Past expiry has no negative remaining days');
 await expiry(first).click();await page.evaluate(()=>document.activeElement?.blur());
 await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().includes('index.html')).setSize(1080,820));await pause(500);
 check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Date buttons fit the desktop window');
 await page.screenshot({path:join(root,'docs/previews/0.7.5-windows-time.png')});
 await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().includes('index.html')).setSize(800,560));await pause(500);
 check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Date buttons fit the minimum window width');
 await call('settings',{mode:'nodes'});await page.getByRole('button',{name:'节点与订阅',exact:true}).click();
 const node=card('Example');await expiry(node).waitFor();
 check(/^\d{4}\/\d{1,2}\/\d{1,2} 到期$/.test(await expiry(node).innerText())&&await update(node).innerText()==='更新于 2 小时前','Node subscription shares expiry and update defaults');
 await expiry(node).click();await update(node).click();
 check(await expiry(node).innerText()==='18 天后到期'&&/^更新于 \d{4}\//.test(await update(node).innerText()),'Node subscription supports both independent time toggles');
 check(errors.length===0,'No renderer errors');
}catch(error){failure=error.stack;console.error(error);}finally{
 if(app){try{await call('stop');}catch{}await app.close();}
 assert.ok(data.startsWith(join(root,'.tools')+'\\'));
 await rm(data,{recursive:true,force:true});
 await writeFile(join(root,'docs/verification/0.7.5-windows-time.json'),JSON.stringify({passed:!failure,checks,errors,failure,build:process.env.WBOX_EXE?'packaged':'development',screenshots:['docs/previews/0.7.5-windows-time.png'],isolatedDataRemoved:true},null,2)+'\n');
}
if(failure)process.exitCode=1;

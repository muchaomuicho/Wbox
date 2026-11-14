import {_electron as electron} from 'playwright';
import {mkdir,writeFile,rm} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..'),data=join(root,`.tools/ui-selection-074-${process.pid}`);
const checks=[],errors=[];let app,page,failure=null;
const check=(ok,label)=>{assert.ok(ok,label);checks.push(label);console.log('PASS',label);};
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn,label){const end=Date.now()+20000;while(!await fn()){if(Date.now()>end)throw Error('Timed out '+label);await pause(100);}}
const call=(method,args={})=>page.evaluate(({method,args})=>window.wbox.call(method,args),{method,args});
try{
 await mkdir(data,{recursive:true});await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',SystemProxy:false,NativeInbounds:false,Silent:false,AutoConnect:false,Configs:[]}));
 app=await electron.launch({executablePath:process.env.WBOX_EXE||join(root,'desktop/node_modules/electron/dist/electron.exe'),args:process.env.WBOX_EXE?[]:[join(root,'desktop/out/main/index.js')],env:{...process.env,WBOX_DATA_DIR:data}});
 await until(()=>{page=app.windows().find(w=>w.url().includes('index.html'));return page;},'main window');page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(e.message));await page.waitForFunction(()=>!!window.wbox);
 await page.evaluate(()=>{localStorage.setItem('wbox.theme',JSON.stringify('light'));localStorage.setItem('wbox.accent',JSON.stringify('blue'));localStorage.setItem('sing-box-dashboard.dashboard-cards',JSON.stringify({enabled:['uploadTraffic','downloadTraffic','status','connections','systemProxy','clashMode','profile'],order:['uploadTraffic','downloadTraffic','status','connections','systemProxy','clashMode','profile']}));});await page.reload();
 await call('createProfile',{name:'Orion · 日常配置',content:'proxies: []\nproxy-groups:\n  - {name: Proxy, type: select, proxies: [DIRECT]}\nrules: ["MATCH,Proxy"]\n'});
 await call('createProfile',{name:'Atlas · 备用配置',content:'proxies: []\nproxy-groups:\n  - {name: Proxy, type: select, proxies: [DIRECT]}\nrules: ["MATCH,Proxy"]\n'});
 const state=await call('state');await call('selectProfile',{id:state.configs[0].id});await call('start');await page.getByRole('button',{name:'概述',exact:true}).click();await page.getByRole('button',{name:'仪表项目',exact:true}).waitFor();
 check(await page.getByText('系统 HTTP 代理',{exact:true}).count()===0&&await page.getByRole('switch',{name:'系统代理',exact:true}).count()===0,'Overview filters the old system-proxy card');
 await page.getByRole('button',{name:'仪表项目',exact:true}).click();
 check(await page.getByRole('dialog').getByText('系统 HTTP 代理',{exact:true}).count()===0,'Dashboard item management removes system proxy');
 await page.getByRole('button',{name:'完成',exact:true}).click();
 await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().includes('index.html')).setSize(1080,820));await pause(1000);
 await page.evaluate(()=>document.activeElement?.blur());await page.screenshot({path:join(root,'docs/previews/0.7.4-windows-overview.png')});
 check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Overview fits the window width');
 await page.getByRole('button',{name:'设置',exact:true}).click();await page.getByRole('switch',{name:'系统代理',exact:true}).waitFor();
 check(await page.getByRole('switch',{name:'TUN 模式',exact:true}).count()===1,'Settings contains TUN mode');
 check(await page.getByText('配置需包含 TUN 入站，启用后以管理员身份运行 Wbox',{exact:true}).count()===1,'TUN setting describes its existing configuration requirement');
 check(await page.getByText('保留配置中的原生入站',{exact:true}).count()===0,'Settings removes the old inbound wording');
 await call('stop');await call('importInput',{text:'http://127.0.0.1:19283#Tokyo\nhttp://127.0.0.1:19284#London'});await call('settings',{mode:'nodes'});await page.getByRole('button',{name:'节点与订阅',exact:true}).click();await call('selectNode',{index:1});
 await until(async()=>(await call('state')).selectedNode===1,'node choice persists');
 check(await page.getByText('已选择节点',{exact:true}).count()===0&&await page.getByText('已切换节点',{exact:true}).count()===0,'Selecting a node has no success toast');
 check(errors.length===0,'No renderer errors');
}catch(e){failure=e.stack;console.error(e);}finally{
 if(app){try{await call('stop');}catch{}await app.close();}await rm(data,{recursive:true,force:true});
 await writeFile(join(root,'docs/verification/0.7.4-windows-ui.json'),JSON.stringify({passed:!failure,checks,errors,failure,build:process.env.WBOX_EXE?'packaged':'development',screenshots:['docs/previews/0.7.4-windows-overview.png'],isolatedDataRemoved:true},null,2)+'\n');
}
if(failure)process.exitCode=1;

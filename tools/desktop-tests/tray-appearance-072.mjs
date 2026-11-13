import {_electron as electron} from 'playwright';
import {mkdir,writeFile,rm} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..'),data=join(root,'.tools/tray-appearance-072-runtime');
const checks=[];let app;
const check=(condition,label)=>{assert(condition,label);checks.push(label);console.log('PASS',label)};
try{
 await mkdir(data,{recursive:true});
 await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',SystemProxy:false,Silent:false,AutoConnect:false,Configs:[]}));
 const bootstrap=join(data,'bootstrap.mjs');
 await writeFile(bootstrap,`import {Tray,screen} from 'electron';const original=Tray.prototype.getBounds;Tray.prototype.getBounds=function(){const a=screen.getPrimaryDisplay().workArea;globalThis.wboxTrayAnchor={x:a.x+a.width-80,y:a.y+a.height+8,width:24,height:24};return globalThis.wboxTrayAnchor};await import(${JSON.stringify(pathToFileURL(join(root,'desktop/out/main/index.js')).href)});`);
 app=await electron.launch({executablePath:join(root,'desktop/node_modules/electron/dist/electron.exe'),args:[bootstrap],env:{...process.env,WBOX_DATA_DIR:data}});
 let page;for(let i=0;i<100;i++){page=app.windows().find(p=>p.url().includes('index.html'));if(page)break;await new Promise(r=>setTimeout(r,100));}
 assert(page);await page.waitForFunction(()=>!!window.wbox);
 await page.evaluate(async()=>{await window.wbox.call('createProfile',{name:'旅行 · 配置',content:'{"outbounds":[{"type":"direct","tag":"DIRECT"}]}'});window.wbox.application('showTray');});
 let tray;for(let i=0;i<100;i++){tray=app.windows().find(p=>p.url().includes('tray.html'));if(tray)break;await new Promise(r=>setTimeout(r,100));}
 assert(tray);const profilesMenu=tray.locator('[data-tray-submenu-parent="1"]').filter({has:tray.getByText('配置',{exact:true})});await profilesMenu.waitFor();
 const native=await app.evaluate(({BrowserWindow,screen})=>({bounds:BrowserWindow.getAllWindows().find(w=>w.getTitle()==='Wbox Tray').getBounds(),area:screen.getPrimaryDisplay().workArea,anchor:globalThis.wboxTrayAnchor}));
 const first=await tray.locator('[data-tray-content]').evaluate(el=>{const r=el.firstElementChild.getBoundingClientRect();return {left:r.left,right:r.right,bottom:r.bottom,side:document.documentElement.dataset.trayCascadeSide,align:document.documentElement.dataset.trayVerticalAlignment}});
 check(first.side==='left','Tray prefers leftward expansion');
 check(first.align==='bottom','Taskbar anchor keeps the root panel low');
 check(native.bounds.x+first.right<=native.anchor.x-7,'Root menu opens beside the icon on its left');
 check(native.bounds.x>=native.area.x&&native.bounds.y>=native.area.y&&native.bounds.x+native.bounds.width<=native.area.x+native.area.width&&native.bounds.y+native.bounds.height<=native.area.y+native.area.height,'Tray window remains inside the current monitor');
 await profilesMenu.hover();
 await tray.locator('[data-tray-cascade-level="1"]').getByRole('button',{name:/旅行 · 配置/}).waitFor();
 const cascade=await tray.locator('[data-tray-content]').evaluate(el=>{const root=el.firstElementChild.getBoundingClientRect(),sub=el.querySelector('[data-tray-cascade-level="1"]').getBoundingClientRect();return {left:sub.left,right:sub.right,top:sub.top,bottom:sub.bottom,rootLeft:root.left,h:innerHeight};});
 check(cascade.right<=first.left+1,'Configuration submenu expands to the left of the root menu');
 check(cascade.left>=0&&cascade.top>=0&&cascade.bottom<=cascade.h,'Configuration submenu stays visible');
 await tray.screenshot({path:join(root,'docs/previews/0.7.2-windows-tray-left.png'),animations:'disabled'});
 await writeFile(join(root,'docs/verification/0.7.2-windows-tray.json'),JSON.stringify({checks,placement:native},null,2)+'\n');
}catch(error){
 if(app){const window=app.windows().find(p=>p.url().includes('tray.html'));if(window){console.log(await window.locator('body').innerText());await window.screenshot({path:join(root,'.tools/tray072-failure.png')});}}
 throw error;
}finally{
 if(app)await app.close();
 await rm(data,{recursive:true,force:true});
}

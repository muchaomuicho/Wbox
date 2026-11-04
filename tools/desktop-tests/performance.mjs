import {_electron as electron} from 'playwright';
import {mkdir,writeFile} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..'),label=process.argv.includes('--baseline')?'0.6.5':'0.6.6';
const data=join(root,'windows','performance-'+label);await mkdir(data,{recursive:true});
await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',SystemProxy:false,Configs:[],Silent:false}));
const app=await electron.launch({executablePath:process.env.WBOX_EXE||join(root,'windows/desktop-publish/win-unpacked/Wbox.exe'),env:{...process.env,WBOX_DATA_DIR:data}});
const result={version:label};
try{
 const page=await app.firstWindow();await page.getByTitle('开始',{exact:true}).waitFor();
 await page.waitForTimeout(3000);
 const memory=()=>app.evaluate(({app})=>app.getAppMetrics().map(p=>({type:p.type,privateBytes:(p.memory.privateBytes??p.memory.workingSetSize)*1024,cpu:p.cpu.percentCPUUsage})));
 result.visible=await memory();
 let start=Date.now();await page.evaluate(()=>window.wbox.application('showTray'));
 result.coldTrayMs=Date.now()-start;
 const tray=(await app.windows()).find(w=>w!==page);assert(tray);
 await tray.evaluate(()=>window.wbox.application('closeTray'));await new Promise(r=>setTimeout(r,1800));
 start=Date.now();await page.evaluate(()=>window.wbox.application('showTray'));result.repeatTrayMs=Date.now()-start;
 const next=(await app.windows()).find(w=>w!==page);assert(next);result.reusedTray=next===tray;
 await next.evaluate(()=>window.wbox.application('closeTray'));
 await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.getTitle()!=='Wbox Tray').close());
 await new Promise(r=>setTimeout(r,3500));result.hiddenWithTrayCache=await memory();
 // Split waits allow progress reporting while the native process is running.
 await new Promise(r=>setTimeout(r,label==='0.6.5'?1000:65000));result.hiddenAfterRelease=await memory();
 result.windowsAfterRelease=await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().map(w=>({title:w.getTitle(),visible:w.isVisible()})));
 if(label==='0.6.6'){assert(result.reusedTray);assert(result.repeatTrayMs<500);assert.equal(result.windowsAfterRelease.length,0,'Idle tray window must be destroyed');}
 await writeFile(join(root,'docs/verification',label+'-performance.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}finally{await app.close();}

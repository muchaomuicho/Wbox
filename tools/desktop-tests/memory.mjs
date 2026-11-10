import { _electron as electron } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
const root=resolve(import.meta.dirname,'../..');
const label=process.env.WBOX_SOFTWARE_RENDERING==='1'?'software':'hardware';
const data=join(root,'windows','memory-test-'+label);
await mkdir(data,{recursive:true});
await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',SystemProxy:false,Configs:[],Nodes:[],Silent:false}));
const app=await electron.launch({executablePath:join(root,'desktop/node_modules/electron/dist/electron.exe'),args:[join(root,'desktop/out/main/index.js')],env:{...process.env,WBOX_DATA_DIR:data},timeout:30000});
try{
 const page=await app.firstWindow();await page.getByRole('button',{name:'配置与节点',exact:true}).waitFor();
 const sample=()=>app.evaluate(({app})=>app.getAppMetrics().map(p=>({pid:p.pid,type:p.type,...p.memory,cpu:p.cpu.percentCPUUsage})));
 await page.waitForTimeout(4000);const visible=await sample();
 await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().find(w=>w.getTitle()!=='Wbox Tray').close());
 await new Promise(r=>setTimeout(r,32000));const hidden=await sample();
 const result={label,visible,hidden};console.log(JSON.stringify(result));
 await writeFile(join(root,`docs/verification/memory-${label}.json`),JSON.stringify(result,null,2));
}finally{await app.close();}

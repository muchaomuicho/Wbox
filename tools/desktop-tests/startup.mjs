import {_electron as electron} from 'playwright';
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import http from 'node:http';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..'),data=join(root,'windows/startup-ui-runtime');
await mkdir(data,{recursive:true});
const server=http.createServer((_req,res)=>{const timer=setTimeout(()=>res.end('{"version":3,"rules":[]}'),35000);res.on('close',()=>clearTimeout(timer));});
await new Promise(r=>server.listen(0,'127.0.0.1',r));const port=server.address().port;
await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',SystemProxy:false,Configs:[]}));
const app=await electron.launch({executablePath:join(root,'windows/desktop-publish/win-unpacked/Wbox.exe'),env:{...process.env,WBOX_DATA_DIR:data}});
const checks=[];
try {
  const page=await app.firstWindow();await page.getByTitle('开始',{exact:true}).waitFor();
  const config={outbounds:[{type:'direct',tag:'DIRECT'}],route:{final:'DIRECT',rule_set:[{type:'remote',tag:'slow',format:'source',url:`http://127.0.0.1:${port}/rules`,download_detour:'DIRECT'}],rules:[{rule_set:'slow',outbound:'DIRECT'}]}};
  await page.evaluate(content=>window.wbox.call('createProfile',{name:'Slow rules',content}),JSON.stringify(config));
  await page.getByTitle('开始',{exact:true}).click();
  await page.getByTitle('取消启动',{exact:true}).waitFor({timeout:10000});
  await page.screenshot({path:join(root,'docs/previews/0.6.2-windows-starting.png')});
  const began=Date.now();await page.getByTitle('取消启动',{exact:true}).click();
  await page.getByTitle('开始',{exact:true}).waitFor({timeout:12000});
  const status=await page.evaluate(()=>window.wbox.call('service'));
  assert(!status.running&&!status.starting);assert(Date.now()-began<12000);
  checks.push('Packaged Windows UI exposes cancel while remote rules initialize and returns to idle');
  await writeFile(join(root,'docs/verification/0.6.2-desktop-startup.json'),JSON.stringify({checks,cancelMs:Date.now()-began},null,2));
} finally {await app.close();server.closeAllConnections();server.close();}
console.log(checks.join('\n'));

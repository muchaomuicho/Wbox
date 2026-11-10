import {_electron as electron} from 'playwright';
import {mkdir,writeFile,rm} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'../..');
const data=join(root,'windows/silent-startup-test-runtime');
await mkdir(data,{recursive:true});
await writeFile(join(data,'settings.json'),JSON.stringify({SystemProxy:false,Silent:true,AutoStart:false,AutoConnect:false,Configs:[]}));
const checks=[];
const app=await electron.launch({executablePath:join(root,'windows/desktop-publish/win-unpacked/Wbox.exe'),args:['--autostart','--silent'],env:{...process.env,WBOX_DATA_DIR:data}});
try {
  await app.evaluate(async({app})=>{await app.whenReady();});
  await new Promise(resolve=>setTimeout(resolve,4200));
  assert.equal(await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().length),0);
  checks.push('Silent login retains the application without opening a renderer window, including after tray re-registration');
  await app.evaluate(({app})=>app.emit('second-instance',{},[]));
  const page=await app.firstWindow();
  await page.getByRole('button',{name:'代理',exact:true}).waitFor();
  assert.equal((await page.evaluate(()=>window.wbox.call('service'))).running,false);
  checks.push('Manual reopen of a silent login instance opens the UI with the backend ready');
} finally {
  await app.close();
  await writeFile(join(root,'docs/verification/0.6.7-silent-startup.json'),JSON.stringify({checks},null,2));
  await rm(data,{recursive:true,force:true});
}
console.log(checks.join('\n'));

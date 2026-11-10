import {_electron as electron} from 'playwright';
import {mkdir,writeFile,rm} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..'),data=join(root,'windows/version-0610-runtime');
await mkdir(data,{recursive:true});
await writeFile(join(data,'settings.json'),JSON.stringify({SystemProxy:false,Silent:false,AutoConnect:false,Configs:[]}));
let app;
try {
 app=await electron.launch({executablePath:join(root,'windows/desktop-publish/win-unpacked/Wbox.exe'),env:{...process.env,WBOX_DATA_DIR:data}});
 let page;
 const deadline=Date.now()+30000;
 while(!page&&Date.now()<deadline){page=app.windows().find(p=>p.url().includes('index.html'));if(!page)await new Promise(r=>setTimeout(r,100));}
 assert(page,'Main window loaded');
 await page.getByText('0.6.10',{exact:true}).waitFor();
 assert.equal(await app.evaluate(({app})=>app.getVersion()),'0.6.10');
 await page.getByRole('button',{name:'设置',exact:true}).click();
 await page.getByText('0.6.10',{exact:true}).last().waitFor();
 await writeFile(join(root,'docs/verification/0.6.10-windows-version.json'),JSON.stringify({passed:true,version:'0.6.10',checks:['Packaged app version','Sidebar version','Settings version']},null,2));
 console.log('PASS Packaged Windows app, sidebar and settings report 0.6.10');
} finally {
 if(app)await app.close();
 if(data===join(root,'windows/version-0610-runtime'))await rm(data,{recursive:true,force:true});
}

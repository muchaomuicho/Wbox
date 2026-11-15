import {_electron as electron} from 'playwright';
import {mkdir,readFile,writeFile,rm} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'../..'), data=join(root,`.tools/ui-080-${process.pid}`);
const version=JSON.parse(await readFile(join(root,'desktop/package.json'),'utf8')).version;
const shots=join(root,'docs/previews',version), checks=[], errors=[];
let app,page,failure;
const check=(value,label)=>{assert.ok(value,label);checks.push(label);console.log('PASS',label);};
const until=async(predicate)=>{const end=Date.now()+20000;while(!await predicate()){assert(Date.now()<end,'Operation timed out');await page.waitForTimeout(100);}};
const call=(method,args={})=>page.evaluate(({method,args})=>window.wbox.call(method,args),{method,args});
const resize=async(width,height,zoom=1)=>{
  await app.evaluate(({BrowserWindow},{width,height,zoom})=>{
    const window=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().includes('index.html'));
    window.setSize(width,height);window.webContents.setZoomFactor(zoom);
  },{width,height,zoom});
  await page.waitForTimeout(300);
};
const appearance=async(theme,accent='blue')=>{
  await page.evaluate(({theme,accent})=>{localStorage.setItem('wbox.theme',JSON.stringify(theme));localStorage.setItem('wbox.accent',JSON.stringify(accent));},{theme,accent});
  await page.reload();await page.waitForFunction(()=>!!window.wbox);await page.locator('nav').waitFor();
};
const navigate=async label=>{
  await page.locator('nav').getByRole('button',{name:label,exact:true}).click();
  await page.getByRole('heading',{name:label,exact:true}).waitFor();
  await page.locator('.page').evaluate(el=>Promise.all(el.getAnimations().map(animation=>animation.finished)));
};
const screenshot=async(name)=>{await page.evaluate(()=>document.activeElement?.blur());await page.screenshot({path:join(shots,`${name}.png`),animations:'disabled',scale:'css'});};
try {
  await mkdir(data,{recursive:true});await mkdir(shots,{recursive:true});
  const configs=[];
  for(const [index,Name] of ['日常网络 · 东京','备用线路 · 香港','一份较长的配置名称，用来检查窄窗口下的换行和操作按钮'].entries()) {
    const Path=join(data,`profile-${index}.json`);
    await writeFile(Path,JSON.stringify({proxies:[{name:'日本 · Tokyo 01',type:'http',server:'127.0.0.1',port:18080},{name:'香港 · Hong Kong 02',type:'http',server:'127.0.0.1',port:18081}],'proxy-groups':[{name:'代理选择',type:'select',proxies:['DIRECT','日本 · Tokyo 01','香港 · Hong Kong 02']}],rules:['MATCH,DIRECT']}));
    configs.push({Id:`p${index}`,Name,Path,Core:'mihomo',Url:'',Updated:new Date().toISOString()});
  }
  await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',Core:'mihomo',ConfigPath:configs[0].Path,SystemProxy:false,NativeInbounds:false,Silent:false,AutoConnect:false,Configs:configs,SubscriptionSettingsMigrated:true}));
  app=await electron.launch({executablePath:process.env.WBOX_EXE||join(root,'desktop/node_modules/electron/dist/electron.exe'),args:process.env.WBOX_EXE?[]:[join(root,'desktop/out/main/index.js')],env:{...process.env,WBOX_DATA_DIR:data}});
  for(let i=0;i<100&&!page;i++){page=app.windows().find(w=>w.url().includes('index.html'));if(!page)await new Promise(r=>setTimeout(r,100));}
  assert(page,'main window opened');page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(e.message));
  await resize(1180,840);await appearance('light');
  check(await page.getByText(version,{exact:true}).isVisible(),'Release version is visible');
  check(await page.getByText(/ALPHA|界面预览版/).count()===0,'Release has no preview label');
  check(await page.locator('nav').evaluate(el=>getComputedStyle(el).paddingTop==='24px'),'Sidebar brand retains comfortable top spacing');
  await page.getByRole('button',{name:'启动',exact:true}).waitFor();
  check((await page.getByRole('button',{name:'启动',exact:true}).innerText()).trim()==='','Start control uses an icon without a text label');
  check(await page.getByRole('button',{name:'启动',exact:true}).evaluate(el=>{const header=el.closest('header').getBoundingClientRect();return el.getBoundingClientRect().left-header.left<=40;}),'Start icon is on the left of the titlebar');
  check(await page.getByText('服务未启动',{exact:true}).count()===0,'Overview has no separate service status panel');
  await screenshot('overview-idle-light');
  await call('start');await page.getByTitle('停止',{exact:true}).waitFor();
  await page.getByText('上传',{exact:true}).waitFor();await screenshot('overview-light');
  for(const theme of ['light','dark']) {
    await appearance(theme);
    await navigate('概述');
    check(await page.evaluate(()=>!document.querySelector('nav').getBoundingClientRect().x),'Desktop navigation remains on the left');
    await screenshot(`overview-${theme}`);
    await navigate('代理');await screenshot(`proxies-${theme}`);
    await navigate('配置');await screenshot(`profiles-${theme}`);
    const card=page.locator('.wbox-config-card').nth(1);await card.click({button:'right'});
    await page.getByRole('menu').getByRole('button',{name:'预览',exact:true}).click();
    await page.getByRole('dialog').locator('.cm-content').waitFor();await screenshot(`preview-${theme}`);
    await page.keyboard.press('Escape');
    await page.waitForFunction(()=>document.activeElement?.getAttribute('data-library-id')==='p1');
    check(await card.evaluate(el=>document.activeElement===el),`${theme}: Escape restores focus to configuration card`);
    await navigate('设置');await screenshot(`settings-${theme}`);
    check(await page.getByRole('heading',{name:'设置',exact:true}).isVisible(),`${theme}: desktop page has a visible heading`);
  }
  for(const accent of ['blue','pink','default']) {
    for(const theme of ['light','dark']) {
      await appearance(theme,accent);await navigate('配置');
      check(await page.locator('.wbox-config-card.current').evaluate(el=>getComputedStyle(el).backgroundColor!==getComputedStyle(document.querySelector('.wbox-config-card:not(.current)')).backgroundColor),`${theme}/${accent}: current configuration has a distinct fill`);
      const neutral=await page.evaluate(()=>{
        const style=getComputedStyle(document.documentElement),channels=hex=>{const h=hex.trim().slice(1);return h.length===3?[...h].map(c=>parseInt(c+c,16)):h.match(/../g).map(c=>parseInt(c,16));};
        return ['--bg','--card','--text','--text-secondary','--border'].every(token=>{const c=channels(style.getPropertyValue(token));return Math.max(...c)-Math.min(...c)<=4;});
      });
      check(neutral,`${theme}/${accent}: surfaces and body text stay close to neutral`);
      if(accent==='pink'){await navigate('概述');await screenshot(`overview-pink-${theme}`);}
    }
  }
  await appearance('light');
  for(const [width,height,zoom] of [[800,560,1],[1100,760,1.25],[1100,760,1.5],[800,560,1.5]]) {
    await resize(width,height,zoom);
    for(const label of ['配置','设置','日志','概述']) {
      await navigate(label);
      check(await page.locator('header').getByText(label,{exact:true}).count()===0,`${label}: no repeated small title in the titlebar`);
      check(await page.locator('.page-header').evaluate(el=>{const gap=el.getBoundingClientRect().top-document.querySelector('header').getBoundingClientRect().bottom;return gap>=0&&gap<=18;}),`${label}: page heading sits close to the titlebar`);
      check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`${label}: no page overflow at ${width}×${height}, ${zoom*100}%`);
      check(await page.locator('nav').evaluate(el=>{const r=el.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth;}),`${label}: navigation remains reachable at ${zoom*100}%`);
      check(await page.locator('header').evaluate(el=>[...el.querySelectorAll('button')].filter(b=>b.offsetParent!==null).every(b=>{const r=b.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth-parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--window-controls-width')||'138');})),`${label}: toolbar actions avoid Windows caption controls at ${zoom*100}%`);
    }
    await navigate('配置');await screenshot(`profiles-${width}-${zoom}`);
  }
  await resize(1180,840);await call('stop');await call('settings',{mode:'nodes'});
  await call('importInput',{text:'http://127.0.0.1:18080#Tokyo\nhttp://127.0.0.1:18081#Hong%20Kong'});
  await navigate('节点与订阅');await screenshot('nodes-light');
  const row=page.locator('.wbox-node-table tbody tr').nth(1);await row.click();
  check((await call('state')).selectedNode===1&&!(await call('state')).running,'Single click selects a node without connecting');
  await row.focus();await page.keyboard.press('Enter');await page.getByTitle('停止',{exact:true}).waitFor();
  await until(async()=>(await call('state')).running);
  check((await call('state')).running,'Enter connects the selected node');
  check(errors.length===0,'No renderer errors');
} catch(error) { failure=String(error.stack||error);console.error(failure);if(page){console.error(await call('logs').catch(()=>[]));await screenshot('failure').catch(()=>{});} }
finally {
  if(app){try{await call('stop');}catch{}await app.close();}
  assert(data.startsWith(join(root,'.tools')+'\\'));await rm(data,{recursive:true,force:true});
  await writeFile(join(root,`docs/verification/${version}-windows-ui.json`),JSON.stringify({passed:!failure,checks,errors,failure,build:process.env.WBOX_EXE?'packaged':'development',screenshots:`docs/previews/${version}`,isolatedDataRemoved:true},null,2)+'\n');
}
if(failure)process.exitCode=1;

import {_electron as electron} from 'playwright';
import {mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..'),data=join(root,'.tools/desktop-0611-runtime');
await mkdir(data,{recursive:true});
const checks=[],hits=[0,0],errors=[];let failure=null,page,app,config,header='upload=1024; download=2048; total=107374182400; expire=1893456000',status=200;
const check=(value,name)=>{assert(value,name);checks.push(name);console.log('PASS',name);};
const wait=async(fn,timeout=30000)=>{const end=Date.now()+timeout;while(Date.now()<end){if(await fn())return;await new Promise(r=>setTimeout(r,100));}throw Error('Timed out');};
const listen=server=>new Promise(r=>server.listen(0,'127.0.0.1',r));
const cert=await readFile(join(root,'.tools/regressions-069-cert.pem'),'utf8'),key=await readFile(join(root,'.tools/regressions-069-key.pem'),'utf8');
let gate=false;
const origin=https.createServer({cert,key},async(req,res)=>{while(gate)await new Promise(r=>setTimeout(r,100));await new Promise(r=>setTimeout(r,50));res.writeHead(204);res.end();});await listen(origin);
const plainOrigins=await Promise.all([0,1].map(async i=>{const s=http.createServer(async(req,res)=>{await new Promise(r=>setTimeout(r,60));res.end(`WBOX-${i}`);});await listen(s);return s;}));
const proxies=await Promise.all([0,1].map(async i=>{const server=http.createServer((req,res)=>{hits[i]++;res.end(`WBOX-${i}`);});server.on('connect',(req,client,head)=>{hits[i]++;const upstream=net.connect(req.url.startsWith('93.184.216.34:')?plainOrigins[i].address().port:origin.address().port,'127.0.0.1',()=>{client.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length)upstream.write(head);client.pipe(upstream).pipe(client);});upstream.on('error',()=>client.destroy());client.on('error',()=>upstream.destroy());client.on('close',()=>upstream.destroy());});await listen(server);return server;}));
const source=http.createServer((req,res)=>{res.statusCode=status;if(header)res.setHeader('Subscription-Userinfo',header);res.end(JSON.stringify(config));});await listen(source);
const base=`http://127.0.0.1:${source.address().port}`;
config={certificate:{certificate:[cert]},outbounds:[{type:'selector',tag:'Main',outbounds:['A','B','Nested']},{type:'selector',tag:'Nested',outbounds:['A','B']},...['A','B'].map((tag,i)=>({type:'http',tag,server:'127.0.0.1',server_port:proxies[i].address().port}))]};
try{
 await writeFile(join(data,'settings.json'),JSON.stringify({SystemProxy:false,Silent:false,AutoConnect:false,Configs:[]}));
 app=await electron.launch({executablePath:process.env.WBOX_EXE||join(root,'desktop/node_modules/electron/dist/electron.exe'),args:process.env.WBOX_EXE?[]:[join(root,'desktop/out/main/index.js')],env:{...process.env,WBOX_DATA_DIR:data}});
 await wait(()=>{page=app.windows().find(p=>p.url().includes('index.html'));return !!page;});page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(e.message));
 const call=(method,args={})=>page.evaluate(({method,args})=>window.wbox.call(method,args),{method,args});
 await call('createProfile',{name:'Traffic plan',remoteUrl:base+'/one'});let state=await call('state'),profile=state.configs[0];
 check(profile.subscription.usage.upload===1024&&profile.subscription.usage.total===107374182400,'Remote import preserves subscription traffic and expiry headers');
 await page.getByRole('button',{name:'配置',exact:true}).click();await page.getByRole('progressbar',{name:'订阅已用流量'}).waitFor();
 check((await page.locator('body').innerText()).includes('100 GB'),'Configuration card renders quota and progress');
 await page.locator('.wbox-row').filter({hasText:'Traffic plan'}).getByTitle('编辑订阅').click();
 check(await page.getByRole('dialog').getByRole('progressbar').count()===1,'Subscription editor also displays quota');await page.getByRole('button',{name:'取消',exact:true}).click();
 header='upload=4096; download=8192; total=107374182400; expire=0';await call('updateProfile',{id:profile.id});
 check((await call('state')).configs[0].subscription.usage.download===8192,'Manual refresh replaces quota counters');
 status=401;await call('updateProfile',{id:profile.id}).catch(()=>{});
 check((await call('state')).configs[0].subscription.usage.download===8192,'Failed refresh preserves previous traffic');status=200;
 header='';await call('updateProfile',{id:profile.id});check((await call('state')).configs[0].subscription.usage===null,'Successful response without a header clears stale traffic');
 header='upload=1024;download=2048;total=107374182400;expire=1893456000';await call('updateProfile',{id:profile.id});
 await page.screenshot({path:join(root,'docs/previews/0.6.11-windows-subscriptions.png')});
 await call('settings',{delayUrl:`https://localhost:${origin.address().port}/`});await page.getByRole('button',{name:'代理',exact:true}).click();
 const delay=name=>page.getByRole('button',{name:`测试节点 ${name} 的延迟`,exact:true}).first();
 gate=true;await delay('B').click();await wait(()=>hits[1]>0);
 check(hits[0]===0,'Clicking B lightning tests only B');
 check(await delay('B').locator('.spinner').count()>0||await delay('B').isDisabled(),'Single test shows a pending spinner');
 check(await delay('B').evaluate(el=>getComputedStyle(el).backgroundColor)==='rgba(0, 0, 0, 0)','Hovered pending latency control has a transparent background');
 await page.screenshot({path:join(root,'docs/previews/0.6.11-windows-spinner.png')});
 check((await call('groups'))[0].selected==='A','Latency click does not change selected node');gate=false;
 await wait(async()=>!(await call('state')).delayTesting);await wait(async()=>/\d+ ms/.test(await delay('B').innerText()));
 check(await delay('B').evaluate(el=>getComputedStyle(el).fontSize)==='11px','Delay uses the upstream configuration workspace small typography');
 let before=[...hits];await delay('B').click();await wait(async()=>!(await call('state')).delayTesting);check(hits[1]>before[1]&&hits[0]===before[0],'Measured delay remains clickable for a single-node retry');
 before=[...hits];await delay('Nested').click();await wait(async()=>!(await call('state')).delayTesting);check(hits[0]>before[0]&&hits[1]===before[1],'Nested group latency tests only its selected leaf');
 await call('start');before=[...hits];await delay('B').click();await wait(async()=>!(await call('state')).delayTesting);check((await call('state')).running&&hits[1]>before[1]&&hits[0]===before[0],'Connected single test preserves current connection');await call('stop');
 await page.screenshot({path:join(root,'docs/previews/0.6.11-windows-lightning.png')});
 await call('settings',{mode:'nodes',core:'mihomo',delayUrl:'http://93.184.216.34/check'});
 await call('importInput',{text:proxies.map((p,i)=>`http://127.0.0.1:${p.address().port}#Node${i}`).join('\n')});
 await page.getByRole('button',{name:'节点与订阅',exact:true}).click();
 check(await page.getByRole('button',{name:'代理',exact:true}).count()===0,'Node workspace has its own node list instead of a synthetic Selector page');
 before=[...hits];await delay('Node1').click();await wait(async()=>!(await call('state')).delayTesting);check(hits[1]>before[1]&&hits[0]===before[0]&&(await call('state')).selectedNode===0,'Node-library lightning measures one node without selecting it');
 for(const core of ['mihomo','sing-box']){
  await call('settings',{core});
  for(const index of [1,0]){
   await page.getByRole('row').filter({has:page.getByRole('cell',{name:`Node${index}`,exact:true})}).dblclick();
   await wait(async()=>{const s=await call('state');return s.running&&s.selectedNode===index;},45000);
   const logs=await call('logs'),started=logs.map(l=>l.message).findLast(l=>l.includes('本地代理 127.0.0.1:')),port=Number(started.match(/127\.0\.0\.1:(\d+)/)[1]);
   const response=await new Promise((res,rej)=>{http.get({host:'127.0.0.1',port,headers:{Host:'93.184.216.34'},path:'http://93.184.216.34/forward'},r=>{let body='';r.on('data',v=>body+=v);r.on('end',()=>res(body));}).on('error',rej);});
   if(response!==`WBOX-${index}`)console.log('FORWARD DIAGNOSTIC',JSON.stringify({response,index,port,hits,logs:(await call('logs')).slice(-12)}));
   check(response===`WBOX-${index}`,`${core} activation forwards through Node${index} only`);
  }
  await call('stop');
 }
 await page.screenshot({path:join(root,'docs/previews/0.6.11-windows-nodes.png')});
 await call('importNodes',{url:base+'/nodes'});state=await call('state');check(state.subscriptionSettings[base+'/nodes'].usage.total===107374182400,'Node subscriptions also retain traffic headers');
 check(errors.length===0,'No renderer errors');
}catch(e){failure=String(e);if(page&&!page.isClosed())await page.screenshot({path:join(root,'docs/previews/0.6.11-windows-failure.png')});throw e;}
finally{gate=false;if(app)await app.close();for(const server of [source,origin,...plainOrigins,...proxies]){server.closeAllConnections();server.close();}await writeFile(join(root,'docs/verification/0.6.11-desktop.json'),JSON.stringify({passed:failure===null,failure,checks,errors,packaged:!!process.env.WBOX_EXE},null,2));await rm(data,{recursive:true,force:true});}

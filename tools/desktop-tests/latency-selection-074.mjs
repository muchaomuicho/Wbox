import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile,rm,readdir} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {createInterface} from 'node:readline';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'../..');
const data=join(root,`.tools/latency-selection-074-${process.pid}`);
const executable=process.env.WBOX_BACKEND||join(root,'windows/Wbox.Backend/bin/Release/net8.0-windows/Wbox.Backend.exe');
const checks=[],hits=[0,0],responses=new Set(),sockets=new Set();
let holding=false,backend,failure=null,nextId=0;
const pending=new Map();
const check=(ok,label)=>{assert.ok(ok,label);checks.push(label);console.log('PASS',label);};
const pause=ms=>new Promise(done=>setTimeout(done,ms));
async function until(predicate,label,timeout=18000){const end=Date.now()+timeout;while(!await predicate()){if(Date.now()>end)throw Error('Timed out: '+label);await pause(40);}}
const listen=server=>new Promise(done=>server.listen(0,'127.0.0.1',done));
const release=()=>{holding=false;for(const finish of responses)finish();responses.clear();};
const cert=await readFile(join(root,'tools/fixtures/latency-localhost-cert.pem'),'utf8');
const key=await readFile(join(root,'tools/fixtures/latency-localhost-key.pem'),'utf8');
const respond=response=>{const finish=()=>{if(!response.destroyed){response.end('LATENCY');}};if(holding)responses.add(finish);else finish();};
const tls=https.createServer({cert,key},(_request,response)=>respond(response));
await listen(tls);
const origin=http.createServer((_request,response)=>respond(response));
await listen(origin);
const proxies=await Promise.all([0,1].map(async index=>{
  const server=http.createServer((_request,response)=>{hits[index]++;respond(response);});
  server.on('connect',(request,client,head)=>{
    hits[index]++;
    const connect=()=>{
      if(client.destroyed)return;
      const upstream=net.connect(request.url.endsWith(':80')?origin.address().port:tls.address().port,'127.0.0.1',()=>{client.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length)upstream.write(head);client.pipe(upstream).pipe(client);});
      upstream.on('error',()=>client.destroy());client.on('error',()=>upstream.destroy());client.on('close',()=>upstream.destroy());
    };
    // Gate before TLS: node templates deliberately do not trust this isolated
    // certificate, while config fixtures supply it explicitly.
    if(holding){responses.add(connect);client.on('close',()=>responses.delete(connect));}else connect();
  });
  server.on('connection',socket=>{sockets.add(socket);socket.on('error',()=>{});socket.on('close',()=>sockets.delete(socket));});
  await listen(server);return server;
}));
const call=(method,args={})=>new Promise((done,reject)=>{const id=++nextId;pending.set(id,{done,reject:message=>reject(Error(method+': '+message.message))});backend.stdin.write(JSON.stringify({id,method,args})+'\n');});
const profile=(engine,reverse=false)=>engine==='mihomo'?JSON.stringify({proxies:[0,1].map(i=>({name:['A','B'][i],type:'http',server:'127.0.0.1',port:proxies[reverse?1-i:i].address().port})),'proxy-groups':[{name:'Main',type:'select',proxies:['A','B']}],rules:['MATCH,Main']}):JSON.stringify({certificate:{certificate:[cert]},outbounds:[{type:'selector',tag:'Main',outbounds:['A','B']},...[0,1].map(i=>({type:'http',tag:['A','B'][i],server:'127.0.0.1',server_port:proxies[reverse?1-i:i].address().port}))],route:{final:'Main'}});
async function idle(){await until(async()=>!(await call('state')).delayTesting,'latency idle');}
async function busy(label){await pause(120);check((await call('state')).delayTesting,label);}
async function begin(method,args={}){holding=true;const before=hits.reduce((a,b)=>a+b,0);await call(method,args);await until(()=>hits.reduce((a,b)=>a+b,0)>before&&responses.size>0,'controlled slow latency request');}
const nodeResult=(engine,node)=>engine==='mihomo'?/ms$/.test(node.delayText):/ms$|^timeout$/.test(node.delayText);
async function forward(index){
  const logs=await call('logs');const message=logs.map(row=>row.message).findLast(value=>value.includes('本地代理 127.0.0.1:'));const port=Number(message.match(/127\.0\.0\.1:(\d+)/)[1]);
  const before=hits[index];await new Promise((done,reject)=>{const request=http.get({host:'127.0.0.1',port,headers:{Host:'93.184.216.34'},path:'http://93.184.216.34/forward'},response=>{response.resume();response.on('end',done);});request.on('error',reject);request.setTimeout(3000,()=>request.destroy(Error('forward timed out')));});
  if(hits[index]===before)console.log('FORWARD DIAGNOSTIC',JSON.stringify({hits,state:await call('state'),groups:await call('trayGroups')}));
  check(hits[index]>before,'Current connection forwards through the newly selected proxy '+index);
}
try {
  await mkdir(data,{recursive:true});
  await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'nodes',SystemProxy:false,Silent:false,AutoConnect:false,Configs:[]}));
  backend=spawn(executable,[],{cwd:root,windowsHide:true,env:{...process.env,WBOX_DATA_DIR:data},stdio:['pipe','pipe','pipe']});
  backend.stderr.on('data',data=>process.stderr.write(data));
  createInterface({input:backend.stdout}).on('line',line=>{const message=JSON.parse(line);if(message.id!=null&&pending.has(message.id)){const promise=pending.get(message.id);pending.delete(message.id);message.error?promise.reject(Error(message.error)):promise.done(message.result);}});
  await call('state');
  await call('importInput',{text:proxies.map((proxy,i)=>`http://127.0.0.1:${proxy.address().port}#Node${i}`).join('\n')});
  for(const engine of ['mihomo','sing-box']) {
    release();await call('stop');
    await call('settings',{mode:'nodes',core:engine,delayUrl:engine==='mihomo'?'http://93.184.216.34/latency':`https://93.184.216.34:${tls.address().port}/latency`});
    await call('selectNode',{index:0});
    await begin('delayNodes');
    await call('selectNode',{index:1});await busy(engine+' offline node selection keeps latency running');
    await call('start');await busy(engine+' starting the selected node keeps isolated latency running');
    release();await idle();
    let state=await call('state');check(state.selectedNode===1&&state.nodes.every(node=>nodeResult(engine,node)),engine+' node results survive preference replacement and keep the new selection');
    await forward(1);

    await begin('testOne',{index:0});await call('selectNode',{index:0});await busy(engine+' live node selection keeps a single-node test running');release();await idle();await forward(0);
    check(nodeResult(engine,(await call('state')).nodes[0]),engine+' single-node result is committed after the main core restarts');
    await begin('testOne',{index:0});const choosing=call('selectNode',{index:1});release();await choosing;await idle();
    check((await call('state')).selectedNode===1&&nodeResult(engine,(await call('state')).nodes[0]),engine+' results completed during connection reload survive the preference commit');await forward(1);
    await begin('trayTestGroup',{tag:'Microsoft'});await call('traySelect',{group:'Microsoft',node:'节点选择'});await busy(engine+' tray policy choice keeps the running-group test active');release();await idle();
    check((await call('trayGroups')).find(group=>group.name==='Microsoft').selected==='节点选择',engine+' group test never restores an old tray choice');
    await begin('delayNodes');const stopped=Date.now();await call('stop');const stopState=await call('state'),stopMs=Date.now()-stopped;console.log('Stop timing',engine,stopMs);check(stopMs<3000&&!stopState.running&&!stopState.delayTesting,engine+' explicit stop promptly cancels latency and stays disconnected');release();

    await call('settings',{mode:'config',delayUrl:engine==='mihomo'?'http://93.184.216.34/latency':`https://localhost:${tls.address().port}/latency`});
    await call('createProfile',{name:engine+' First',content:profile(engine)});await call('createProfile',{name:engine+' Second',content:profile(engine,true)});
    state=await call('state');const first=state.configs.find(config=>config.name===engine+' First'),second=state.configs.find(config=>config.name===engine+' Second');
    await call('selectProfile',{id:first.id});
    await begin('testGroup',{tag:'Main'});await call('select',{group:'Main',node:'B'});await busy(engine+' offline group selection keeps the test active');release();await idle();
    check((await call('groups')).find(group=>group.name==='Main').selected==='B',engine+' offline test result does not overwrite the selected group');
    await begin('testGroup',{tag:'Main'});await call('selectProfile',{id:second.id});await busy(engine+' overview profile selection keeps the previous profile test active');release();await idle();
    check((await call('groups')).find(group=>group.name==='Main').details.every(node=>node.delay==null),engine+' identical node names in another profile do not receive stale results');
    await call('selectProfile',{id:first.id});check((await call('groups')).find(group=>group.name==='Main').details.every(node=>node.delay>0),engine+' returning to the tested profile restores its own results');

    await call('start');
    await begin('testGroup',{tag:'Main'});await call('select',{group:'Main',node:'A'});await busy(engine+' live group choice keeps the isolated test running');release();await idle();
    check((await call('groups')).find(group=>group.name==='Main').selected==='A',engine+' live test preserves the user-selected group');
    await begin('trayTestGroup',{tag:'Main'});await call('traySelectProfile',{id:second.id});await busy(engine+' tray profile choice keeps the earlier profile test running');release();await idle();
    check((await call('state')).configPath===second.path&&(await call('service')).running,engine+' profile choice takes effect while latency continues');await forward(1);

    await begin('testGroup',{tag:'Main'});await call('cancelTest');await idle();release();check((await call('service')).running,engine+' explicit latency cancellation leaves the connection running');
    await begin('testGroup',{tag:'Main'});await call('writeProfile',{id:second.id,content:profile(engine)});await idle();release();check((await call('state')).configPath===second.path,engine+' editing a tested profile invalidates its snapshot');
    await begin('testGroup',{tag:'Main'});await call('settings',{delayUrl:engine==='mihomo'?'http://93.184.216.34/new-url':`https://localhost:${tls.address().port}/new-url`});await idle();release();check(true,engine+' changing the test URL cancels obsolete results');
    await begin('testGroup',{tag:'Main'});await call('removeProfile',{id:second.id});await idle();release();
    check(!(await call('state')).configs.some(config=>config.id===second.id),engine+' deleting the tested source cancels its obsolete snapshot');
    await call('stop');
    await call('settings',{mode:'nodes',core:engine,delayUrl:engine==='mihomo'?'http://93.184.216.34/latency':`https://93.184.216.34:${tls.address().port}/latency`});
    await begin('testOne',{index:0});let edit=await call('readNode',{index:0});const original=edit.node;await call('writeNode',{index:0,revision:edit.revision,node:{...edit.node,name:edit.node.name+' Edited'}});await idle();release();
    check((await call('state')).nodes[0].delayText==='未测试',engine+' edited nodes cannot receive results from the previous revision');
    edit=await call('readNode',{index:0});await call('writeNode',{index:0,revision:edit.revision,node:original});
    await call('cloneNode',{index:0});await begin('testOne',{index:2});await call('removeNode',{index:2});await idle();release();
    check((await call('state')).nodes.length===2,engine+' deleting a tested node cancels its obsolete result');
    await begin('testOne',{index:0});await call('settings',{core:engine==='mihomo'?'sing-box':'mihomo'});await idle();release();
    check((await call('state')).core!==engine,engine+' changing node engines invalidates the running measurement');
  }
  const latencyDir=join(data,'latency');const entries=await readdir(latencyDir).catch(()=>[]);
  if(entries.length)console.log('Transient leftovers',JSON.stringify(await Promise.all(entries.map(async name=>({name,files:await readdir(join(latencyDir,name),{recursive:true})})))));
  check(entries.length===0,'All transient latency directories are removed after completion and cancellation');
} catch(error){failure=String(error);throw error;}
finally {
  release();for(const socket of sockets)socket.destroy();for(const server of [...proxies,tls,origin]){server.closeAllConnections();server.close();}
  if(backend){backend.stdin.end();await new Promise(done=>{const timeout=setTimeout(()=>{backend.kill();done();},5000);backend.once('exit',()=>{clearTimeout(timeout);done();});});}
  await mkdir(join(root,'docs/verification'),{recursive:true});
  await writeFile(join(root,`docs/verification/0.7.4-windows-latency-selection${process.env.WBOX_BACKEND?'-packaged':''}.json`),JSON.stringify({passed:failure==null,checks,failure,engines:['mihomo','sing-box'],build:process.env.WBOX_BACKEND?'packaged':'development'},null,2)+'\n');
  assert(data.startsWith(join(root,'.tools','latency-selection-074-')));await rm(data,{recursive:true,force:true});
}

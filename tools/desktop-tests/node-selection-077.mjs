import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile,rm,readdir,stat} from 'node:fs/promises';
import {resolve,join,relative} from 'node:path';
import {createInterface} from 'node:readline';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'../..');
const data=join(root,`.tools/node-selection-077-${process.pid}`);
const executable=resolve(process.env.WBOX_BACKEND_EXE||join(root,'windows/backend-publish/Wbox.Backend.exe'));
const checks=[],errors=[],hits=[0,0],held=new Set(),sockets=new Set(),pending=new Map(),servers=[];
const startedAt=new Date().toISOString();
let backend,nextId=0,holding=false,failure=null,cleaned=false;
const pause=ms=>new Promise(done=>setTimeout(done,ms));
const check=(ok,label)=>{assert.ok(ok,label);checks.push(label);console.log('PASS',label);};
async function until(predicate,label,timeout=20000){const end=Date.now()+timeout;while(!await predicate()){if(Date.now()>end)throw Error('Timed out: '+label);await pause(40);}}
async function listen(server){servers.push(server);server.on('connection',socket=>{sockets.add(socket);socket.on('error',()=>{});socket.on('close',()=>sockets.delete(socket));});await new Promise((done,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',done);});return server;}
const release=()=>{holding=false;for(const finish of held)finish();held.clear();};
function respond(response,body,latency){const finish=()=>{held.delete(finish);if(!response.destroyed)response.end(body);};if(holding&&latency){held.add(finish);response.once('close',()=>held.delete(finish));}else finish();}
const call=(method,args={})=>new Promise((done,reject)=>{
  const id=++nextId,timer=setTimeout(()=>{pending.delete(id);reject(Error('Timed out request: '+method));},45000);
  pending.set(id,{done:result=>{clearTimeout(timer);done(result);},reject:error=>{clearTimeout(timer);reject(Error(method+': '+error.message));}});
  backend.stdin.write(JSON.stringify({id,method,args})+'\n');
});
async function launch(){
  backend=spawn(executable,[],{cwd:root,windowsHide:true,env:{...process.env,WBOX_DATA_DIR:data},stdio:['pipe','pipe','pipe']});
  backend.stderr.on('data',chunk=>errors.push(chunk.toString()));
  const rejectAll=error=>{for(const request of pending.values())request.reject(error);pending.clear();};
  backend.on('error',rejectAll);
  backend.on('close',code=>rejectAll(Error('Backend closed: '+code)));
  createInterface({input:backend.stdout}).on('line',line=>{
    let message;try{message=JSON.parse(line);}catch{errors.push('Invalid backend output: '+line);return;}
    const request=pending.get(message.id);if(!request)return;pending.delete(message.id);
    message.error?request.reject(Error(message.error)):request.done(message.result);
  });
  await call('state');
}
async function shutdown(){
  if(!backend)return;const current=backend;backend=null;
  if(current.exitCode!=null)return;
  await new Promise((done,reject)=>{
    const timer=setTimeout(()=>{current.kill();reject(Error('Backend did not exit normally'));},8000);
    current.once('close',code=>{clearTimeout(timer);code===0?done():reject(Error('Backend exit code: '+code));});
    current.stdin.end();
  });
}
async function selectedOffline(index,label){
  const state=await call('state'),saved=JSON.parse(await readFile(join(data,'settings.json'),'utf8'));
  check(state.selectedNode===index&&saved.SelectedNode===index&&!state.running&&!state.starting,label);
}
async function idle(){await until(async()=>!(await call('state')).delayTesting,'latency completion',45000);}
async function beginLatency(){holding=true;const previous=hits.reduce((a,b)=>a+b,0);await call('delayNodes');await until(()=>hits.reduce((a,b)=>a+b,0)>previous&&held.size>0,'held latency request');}
async function forward(index,label){
  const message=(await call('logs')).map(row=>row.message).findLast(value=>value.includes('本地代理 127.0.0.1:'));
  assert.ok(message,'Running proxy port is logged');const port=Number(message.match(/127\.0\.0\.1:(\d+)/)[1]);
  const body=await new Promise((done,reject)=>{
    const request=http.get({host:'127.0.0.1',port,headers:{Host:'93.184.216.34'},path:'http://93.184.216.34/forward'},response=>{let value='';response.on('data',chunk=>value+=chunk);response.on('end',()=>done(value));});
    request.on('error',reject);request.setTimeout(5000,()=>request.destroy(Error('Forwarding timed out')));
  });
  check(body===`WBOX-${index}`,label);
}
const startupCount=async()=>(await call('logs')).filter(row=>row.message.includes('已启动（')).length;
const nodeResults=state=>state.nodes.length===2&&state.nodes.every(node=>/\d+ ms$|^timeout$/.test(node.delayText));

try{
  const cert=await readFile(join(root,'tools/fixtures/latency-localhost-cert.pem'),'utf8');
  const key=await readFile(join(root,'tools/fixtures/latency-localhost-key.pem'),'utf8');
  const tls=await listen(https.createServer({cert,key},(_request,response)=>respond(response,'LATENCY',true)));
  const origins=await Promise.all([0,1].map(index=>listen(http.createServer((request,response)=>respond(response,`WBOX-${index}`,request.url.includes('/latency'))))));
  const proxies=await Promise.all([0,1].map(async index=>{
    const server=http.createServer((request,response)=>{hits[index]++;respond(response,`WBOX-${index}`,request.url.includes('/latency'));});
    server.on('connect',(request,client,head)=>{
      hits[index]++;const latency=!request.url.endsWith(':80');
      const connect=()=>{
        held.delete(connect);if(client.destroyed)return;
        const upstream=net.connect(latency?tls.address().port:origins[index].address().port,'127.0.0.1',()=>{client.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length)upstream.write(head);client.pipe(upstream).pipe(client);});
        sockets.add(upstream);upstream.on('close',()=>sockets.delete(upstream));
        upstream.on('error',()=>client.destroy());client.on('error',()=>upstream.destroy());client.on('close',()=>upstream.destroy());
      };
      // Gate CONNECT before TLS, so no local or OS trust configuration is needed.
      if(holding&&latency){held.add(connect);client.on('close',()=>held.delete(connect));}else connect();
    });
    return listen(server);
  }));
  await mkdir(data,{recursive:true});
  await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'nodes',ConfigPath:'',SystemProxy:false,NativeInbounds:false,Silent:false,AutoConnect:false,Configs:[]}));
  await launch();
  await call('importInput',{text:proxies.map((proxy,index)=>`http://127.0.0.1:${proxy.address().port}#Node${index}`).join('\n')});
  for(const engine of ['mihomo','sing-box']){
    await call('stop');await call('settings',{mode:'nodes',core:engine,delayUrl:engine==='mihomo'?'http://93.184.216.34/latency':`https://93.184.216.34:${tls.address().port}/latency`});
    await call('selectNode',{index:0});
    const tags=(await call('state')).nodes.map(node=>node.tag);
    await call('selectNode',{index:1});await selectedOffline(1,engine+' selectNode saves an offline choice without connecting');
    await shutdown();await launch();await selectedOffline(1,engine+' offline index selection persists across backend restart');
    await call('select',{node:tags[0]});await selectedOffline(0,engine+' select saves an offline tag choice without connecting');
    check(await startupCount()===0,engine+' offline selection never logs a main-core startup');
    await shutdown();await launch();await selectedOffline(0,engine+' offline tag selection persists across backend restart');

    await beginLatency();await call('selectNode',{index:1});await call('select',{node:tags[0]});
    check((await call('state')).delayTesting,engine+' both offline selection paths preserve the active latency test');
    await selectedOffline(0,engine+' latency probes do not turn selection into a main connection');
    release();await idle();check(nodeResults(await call('state')),engine+' offline results survive both preference replacements');
    check(await startupCount()===0,engine+' isolated probes never start the main connection');

    await call('start');await forward(0,engine+' explicit start forwards through the saved offline choice');
    await call('selectNode',{index:1});await forward(1,engine+' live index selection forwards through the newly selected node');
    await call('select',{node:tags[0]});await forward(0,engine+' live tag selection forwards through the newly selected node');
    const before=(await call('service')).startedAt,logsBefore=await startupCount();
    await call('selectNode',{index:0});await call('select',{node:tags[0]});
    check((await call('service')).startedAt===before&&await startupCount()===logsBefore,engine+' selecting the current node does not restart the connection');

    await beginLatency();await call('selectNode',{index:1});
    check((await call('state')).delayTesting,engine+' live index selection preserves latency while reconnecting');
    await forward(1,engine+' live traffic uses the new node while latency is still running');
    await call('select',{node:tags[0]});
    check((await call('state')).delayTesting,engine+' live tag selection preserves the same latency run');
    release();await idle();check(nodeResults(await call('state')),engine+' online results survive both connection changes');
    await forward(0,engine+' completed latency does not restore an older selected node');

    for(const method of ['selectNode','select']){
      const choosing=call(method,method==='selectNode'?{index:1}:{node:tags[1]});
      const stopping=call('stop');await Promise.all([choosing,stopping]);
      const stoppedStarts=await startupCount();await pause(250);
      const state=await call('state');
      check(!state.running&&!state.starting&&!state.delayTesting&&await startupCount()===stoppedStarts,engine+' '+method+' racing with stop cannot revive the connection');
      await call(method,method==='selectNode'?{index:1}:{node:tags[1]});
      await selectedOffline(1,engine+' '+method+' after stop remains a saved offline choice');
      await call('start');await forward(1,engine+' an explicit start after '+method+' stop race still connects');
      await call('selectNode',{index:0});
    }
    await beginLatency();const stopping=call('selectNode',{index:1});await call('stop');await stopping;release();await idle();
    check(!(await call('state')).running&&!(await call('state')).starting,engine+' stop during selection and latency leaves the main connection stopped');
  }
  const leftovers=await readdir(join(data,'latency')).catch(()=>[]);
  check(leftovers.length===0,'All isolated latency directories are removed');
  check(errors.length===0,'Backend produced no protocol errors or stderr output');
}catch(error){failure=String(error);console.error(failure);process.exitCode=1;}
finally{
  release();
  try{await shutdown();}catch(error){failure??=String(error);process.exitCode=1;}
  for(const socket of sockets)socket.destroy();
  for(const server of servers){server.closeAllConnections();server.close();}
  try{
    assert.equal(relative(join(root,'.tools'),data),`node-selection-077-${process.pid}`);
    await rm(data,{recursive:true,force:true});cleaned=await stat(data).then(()=>false,()=>true);
  }catch(error){failure??=String(error);process.exitCode=1;}
  await mkdir(join(root,'docs/verification'),{recursive:true});
  await writeFile(join(root,'docs/verification/0.7.7-windows-node-selection.json'),JSON.stringify({passed:failure==null,startedAt,finishedAt:new Date().toISOString(),executable,engines:['mihomo','sing-box'],checks,errors,failure,syntheticDataRemoved:cleaned},null,2)+'\n');
}

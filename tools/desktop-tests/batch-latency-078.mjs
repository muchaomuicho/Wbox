import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile,rm,stat} from 'node:fs/promises';
import {resolve,join,relative} from 'node:path';
import {createInterface} from 'node:readline';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'../..');
const data=join(root,`.tools/batch-latency-078-${process.pid}`);
const executable=resolve(process.env.WBOX_BACKEND_EXE||join(root,'windows/Wbox.Backend/bin/Release/net8.0-windows/Wbox.Backend.exe'));
const checks=[],errors=[],observations={},pending=new Map(),servers=[],sockets=new Set();
const held=[new Set(),new Set()],holding=[false,false],responseDelay=[40,60],rejectProxy=[false,false],hits=[0,0];
let backend,nextId=0,failure=null,cleaned=false,providerExtra=false;
const began=Date.now(),pause=ms=>new Promise(done=>setTimeout(done,ms));
const check=(ok,label)=>{assert.ok(ok,label);checks.push(label);console.log('PASS',label);};
async function until(predicate,label,timeout=25000){const end=Date.now()+timeout;while(!await predicate()){if(Date.now()>end)throw Error('Timed out: '+label);await pause(30);}}
async function listen(server){servers.push(server);server.on('connection',socket=>{sockets.add(socket);socket.on('error',()=>{});socket.on('close',()=>sockets.delete(socket));});await new Promise((done,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',done);});return server;}
function release(index){holding[index]=false;for(const finish of held[index])finish();held[index].clear();}
function releaseAll(){release(0);release(1);}
function respond(index,response){const finish=()=>{held[index].delete(finish);setTimeout(()=>{if(!response.destroyed)response.end('LATENCY');},responseDelay[index]);};if(holding[index]){held[index].add(finish);response.once('close',()=>held[index].delete(finish));}else finish();}
const call=(method,args={})=>new Promise((done,reject)=>{
  const id=++nextId,timer=setTimeout(()=>{pending.delete(id);reject(Error('Timed out: '+method));},45000);
  pending.set(id,{done:result=>{clearTimeout(timer);done(result);},reject:error=>{clearTimeout(timer);reject(Error(method+': '+error.message));}});
  backend.stdin.write(JSON.stringify({id,method,args})+'\n');
});
async function launch(){
  backend=spawn(executable,[],{cwd:root,windowsHide:true,env:{...process.env,WBOX_DATA_DIR:data},stdio:['pipe','pipe','pipe']});
  backend.stderr.on('data',chunk=>errors.push(chunk.toString()));
  const rejectAll=error=>{for(const request of pending.values())request.reject(error);pending.clear();};
  backend.on('error',rejectAll);backend.on('close',code=>rejectAll(Error('Backend closed: '+code)));
  createInterface({input:backend.stdout}).on('line',line=>{let message;try{message=JSON.parse(line);}catch{errors.push(line);return;}const request=pending.get(message.id);if(!request)return;pending.delete(message.id);message.error?request.reject(Error(message.error)):request.done(message.result);});
  await call('state');
}
async function shutdown(){
  if(!backend)return;const current=backend;backend=null;if(current.exitCode!=null)return;
  await new Promise((done,reject)=>{const timer=setTimeout(()=>{current.kill();reject(Error('Backend did not exit'));},8000);current.once('close',code=>{clearTimeout(timer);code===0?done():reject(Error('Backend exit: '+code));});current.stdin.end();});
}
const details=async()=>Object.fromEntries((await call('groups')).find(group=>group.name==='Main').details.map(node=>[node.name,node]));
const allIdle=async()=>(await call('groups')).every(group=>group.details.every(node=>node.testing===false));
async function idle(){await until(async()=>!(await call('state')).delayTesting,'latency completion');}
async function heldBoth(){await until(()=>held.every(items=>items.size>0),'both controlled requests');}
async function warm(){releaseAll();await call('testGroup',{tag:'Main'});await idle();const rows=await details();assert(rows.A.delay>0&&rows.B.delay>0);return rows;}

try{
  const cert=await readFile(join(root,'tools/fixtures/latency-localhost-cert.pem'),'utf8');
  const key=await readFile(join(root,'tools/fixtures/latency-localhost-key.pem'),'utf8');
  const tls=await listen(https.createServer({cert,key},(_request,response)=>{response.end('LATENCY');}));
  const origins=await Promise.all([0,1].map(index=>listen(http.createServer((_request,response)=>respond(index,response)))));
  const proxies=await Promise.all([0,1].map(async index=>{
    const server=http.createServer((_request,response)=>{hits[index]++;if(rejectProxy[index]){response.writeHead(502);response.end();}else respond(index,response);});
    server.on('connect',(request,client,head)=>{
      hits[index]++;if(rejectProxy[index]){client.end('HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n');return;}
      const connect=()=>{held[index].delete(connect);if(client.destroyed)return;setTimeout(()=>{
        if(client.destroyed)return;
        const upstream=net.connect(request.url.endsWith(':80')?origins[index].address().port:tls.address().port,'127.0.0.1',()=>{client.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length)upstream.write(head);client.pipe(upstream).pipe(client);});
        sockets.add(upstream);upstream.on('close',()=>sockets.delete(upstream));upstream.on('error',()=>client.destroy());client.on('error',()=>upstream.destroy());client.on('close',()=>upstream.destroy());
      },responseDelay[index]);};
      if(holding[index]){held[index].add(connect);client.on('close',()=>held[index].delete(connect));}else connect();
    });return listen(server);
  }));
  const provider=await listen(http.createServer((_request,response)=>response.end(JSON.stringify({proxies:(providerExtra?['A','B','C']:['A','B']).map((name,index)=>({name,type:'http',server:'127.0.0.1',port:proxies[index%2].address().port}))}))));
  const profile=engine=>engine==='mihomo'?JSON.stringify({proxies:proxies.map((proxy,index)=>({name:['A','B'][index],type:'http',server:'127.0.0.1',port:proxy.address().port})),'proxy-groups':[{name:'Main',type:'select',proxies:['A','B']}],rules:['MATCH,Main']}):JSON.stringify({certificate:{certificate:[cert]},outbounds:[{type:'selector',tag:'Main',outbounds:['A','B']},...proxies.map((proxy,index)=>({type:'http',tag:['A','B'][index],server:'127.0.0.1',server_port:proxy.address().port}))],route:{final:'Main'}});
  await mkdir(data,{recursive:true});await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',ConfigPath:'',SystemProxy:false,NativeInbounds:false,AutoConnect:false,Configs:[]}));await launch();
  for(const engine of ['mihomo','sing-box']){
    await call('settings',{mode:'config',delayUrl:engine==='mihomo'?'http://93.184.216.34/latency':`https://localhost:${tls.address().port}/latency`});
    for(const suffix of ['One','Two'])await call('createProfile',{name:engine+' '+suffix,content:profile(engine)});
    const state=await call('state'),one=state.configs.find(item=>item.name===engine+' One'),two=state.configs.find(item=>item.name===engine+' Two');
    await call('selectProfile',{id:one.id});let previous=await warm();
    check(await allIdle(),engine+' completed measurements have no pending flags');
    holding.fill(true);const beforeHits=hits.slice();await call('testGroup',{tag:'Main'});
    let rows=await details();observations[engine+' immediate_hits']=hits.map((value,index)=>value-beforeHits[index]);
    check(rows.A.testing&&rows.B.testing&&rows.A.delay===previous.A.delay&&rows.B.delay===previous.B.delay,engine+' group request marks all old results pending immediately');
    await heldBoth();responseDelay[0]=180;release(0);
    await until(async()=>!(await details()).A.testing,'first individual result');rows=await details();
    check(rows.A.delay>0&&!rows.A.testing&&rows.B.testing&&rows.B.delay===previous.B.delay,engine+' one result replaces its pending state while the other still waits');
    release(1);await idle();check(await allIdle(),engine+' group completion clears all pending flags');previous=await details();

    holding[1]=true;await call('testOne',{tag:'B'});rows=await details();
    check(!rows.A.testing&&rows.B.testing&&rows.A.delay===previous.A.delay,engine+' single-node testing leaves the other cached result visible');
    await until(()=>held[1].size>0,'single pending request');release(1);await idle();
    check((await details()).B.delay>0&&await allIdle(),engine+' single-node result clears its flag');

    previous=await details();holding.fill(true);await call('testGroup',{tag:'Main'});await heldBoth();await call('cancelTest');
    check(await allIdle(),engine+' explicit cancellation immediately clears every pending flag');await idle();releaseAll();rows=await details();
    check(rows.A.delay===previous.A.delay&&rows.B.delay===previous.B.delay,engine+' cancellation retains earlier completed measurements');
    holding.fill(true);await call('testGroup',{tag:'Main'});await call('cancelTest');
    check(await allIdle(),engine+' cancellation during core startup clears prepublished targets');await idle();releaseAll();
    check(await allIdle(),engine+' a canceled startup cannot republish pending targets later');

    holding.fill(true);await call('testGroup',{tag:'Main'});await heldBoth();await call('selectProfile',{id:two.id});
    rows=await details();check(!rows.A.testing&&!rows.B.testing&&rows.A.delay==null&&rows.B.delay==null&&(await call('state')).delayTesting,engine+' switching profiles isolates pending and cached results without canceling the snapshot');
    await call('selectProfile',{id:one.id});check((await details()).A.testing&&(await details()).B.testing,engine+' returning to the tested profile restores its own pending targets');
    await call('select',{group:'Main',node:'B'});check((await details()).A.testing&&(await details()).B.testing,engine+' choosing another node keeps the same pending batch');
    releaseAll();await idle();check((await call('groups')).find(group=>group.name==='Main').selected==='B',engine+' completed results preserve the new selection');

    await call('start');const connected=(await call('service')).startedAt;
    holding.fill(true);await call('testGroup',{tag:'Main'});await heldBoth();
    check((await call('service')).running&&(await call('service')).startedAt===connected,engine+' pending tests do not restart the main connection');
    releaseAll();await idle();check((await call('service')).startedAt===connected&&await allIdle(),engine+' completed tests keep the same main connection');
    rejectProxy[1]=true;await call('testOne',{tag:'B'});check((await details()).B.testing,engine+' failed retries initially replace the old number with pending');await idle();rejectProxy[1]=false;
    rows=await details();check(rows.B.delay===0&&!rows.B.testing&&!rows.A.testing,engine+' a failed target clears pending and publishes its failure');await call('stop');

    if(engine==='mihomo'){
      const content=JSON.stringify({'proxy-providers':{Remote:{type:'http',url:`http://127.0.0.1:${provider.address().port}/nodes`,interval:3600}},'proxy-groups':[{name:'Main',type:'select',use:['Remote']}],rules:['MATCH,Main']});
      await call('createProfile',{name:'Provider catalog',content});previous=await warm();providerExtra=true;holding.fill(true);await call('testGroup',{tag:'Main'});
      rows=await details();check(rows.A.testing&&rows.B.testing,engine+' cached provider leaves become pending before the new catalog loads');
      await until(async()=>{const current=await details();return current.C?.testing&&current.A.testing&&current.B.testing;},'actual provider catalog expands pending');
      check(true,engine+' actual provider catalog adds newly discovered targets to pending');releaseAll();await idle();
      rows=await details();check(rows.C.delay>0&&await allIdle(),engine+' discovered provider targets finish without stale pending flags');
    }
  }
  // The node workspace already clears all displayed numbers before queueing.
  await call('settings',{mode:'nodes',core:'mihomo',delayUrl:'http://93.184.216.34/latency'});
  await call('importInput',{text:proxies.map((proxy,index)=>`http://127.0.0.1:${proxy.address().port}#Node${index}`).join('\n')});
  await call('delayNodes');await idle();holding.fill(true);await call('delayNodes');
  check((await call('state')).nodes.every(node=>node.delayText==='测试中'),'Node workspace immediately marks all queued old results as testing');
  await call('cancelTest');await idle();releaseAll();
  check((await call('state')).nodes.every(node=>node.delayText!=='测试中'),'Node cancellation leaves no stale testing text');
  check(errors.length===0,'No backend protocol or stderr errors');
}catch(error){failure=String(error);console.error(failure);process.exitCode=1;}
finally{
  releaseAll();try{await shutdown();}catch(error){failure??=String(error);process.exitCode=1;}
  for(const socket of sockets)socket.destroy();for(const server of servers){server.closeAllConnections();server.close();}
  try{assert.equal(relative(join(root,'.tools'),data),`batch-latency-078-${process.pid}`);await rm(data,{recursive:true,force:true});cleaned=await stat(data).then(()=>false,()=>true);}catch(error){failure??=String(error);process.exitCode=1;}
  await writeFile(join(root,'docs/verification/0.7.8-windows-batch-latency.json'),JSON.stringify({passed:failure==null,executable,checks,observations,errors,failure,durationSeconds:(Date.now()-began)/1000,syntheticDataRemoved:cleaned},null,2)+'\n');
}

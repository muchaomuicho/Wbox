import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile,rm,readdir} from 'node:fs/promises';
import {resolve,join,dirname} from 'node:path';
import {createInterface} from 'node:readline';
import {createHash} from 'node:crypto';
import http from 'node:http';
import net from 'node:net';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'../..');
const data=join(root,`.tools/latency-provider-074-${process.pid}`);
const executable=process.env.WBOX_BACKEND||join(root,'windows/Wbox.Backend/bin/Release/net8.0-windows/Wbox.Backend.exe');
const checks=[],hits=[0,0],sockets=new Set(),pending=new Map();
let sourceIndex=0,sourceRequests=0,nextId=0,backend,failure=null,evidence=null,providerApi=null;
const pause=ms=>new Promise(done=>setTimeout(done,ms));
const check=(condition,label)=>{assert.ok(condition,label);checks.push(label);console.log('PASS',label);};
const listen=server=>new Promise(done=>server.listen(0,'127.0.0.1',done));
const track=server=>server.on('connection',socket=>{sockets.add(socket);socket.on('error',()=>{});socket.once('close',()=>sockets.delete(socket));});
async function until(predicate,label,timeout=25000){const end=Date.now()+timeout;while(!await predicate()){if(Date.now()>end)throw Error('Timed out: '+label);await pause(40);}}
function call(method,args={}){
  return new Promise((done,reject)=>{
    const id=++nextId,timer=setTimeout(()=>{pending.delete(id);reject(Error('Backend call timed out: '+method));},30000);
    pending.set(id,{done:value=>{clearTimeout(timer);done(value);},reject:error=>{clearTimeout(timer);reject(error);}});
    backend.stdin.write(JSON.stringify({id,method,args})+'\n');
  });
}
const origins=await Promise.all([0,1].map(async index=>{
  const server=http.createServer(async(_request,response)=>{await pause(index?260:80);if(!response.destroyed)response.end('WBOX-'+index);});
  track(server);await listen(server);return server;
}));
const proxies=await Promise.all([0,1].map(async index=>{
  const server=http.createServer(async(_request,response)=>{hits[index]++;await pause(index?260:80);if(!response.destroyed)response.end('WBOX-'+index);});
  server.on('connect',(_request,client,head)=>{
    hits[index]++;const upstream=net.connect(origins[index].address().port,'127.0.0.1',()=>{client.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length)upstream.write(head);client.pipe(upstream).pipe(client);});
    upstream.on('error',()=>client.destroy());client.on('error',()=>upstream.destroy());client.on('close',()=>upstream.destroy());
  });
  track(server);await listen(server);return server;
}));
const source=http.createServer((request,response)=>{
  sourceRequests++;response.setHeader('Content-Type','application/json');
  const index=request.url.endsWith('/other')?1:sourceIndex;
  response.end(JSON.stringify({proxies:[{name:'A',type:'http',server:'127.0.0.1',port:proxies[index].address().port}]}));
});
track(source);await listen(source);
const providerUrl=`http://127.0.0.1:${source.address().port}/provider`;
async function forward(){
  const logs=await call('logs'),message=logs.map(row=>row.message).findLast(value=>value.includes('本地代理 127.0.0.1:'));
  const port=Number(message?.match(/127\.0\.0\.1:(\d+)/)?.[1]);assert.ok(port>0,'Main proxy is available');
  return new Promise((done,reject)=>{
    const request=http.get({host:'127.0.0.1',port,headers:{Host:'93.184.216.34'},path:'http://93.184.216.34/forward'},response=>{
      let body='';response.on('data',chunk=>body+=chunk);response.on('end',()=>done(body));response.on('error',reject);
    });
    request.on('error',reject);request.setTimeout(4000,()=>request.destroy(Error('Forward timed out')));
  });
}
try{
  await mkdir(data,{recursive:true});
  await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',Core:'mihomo',SystemProxy:false,AutoConnect:false,Silent:false,Configs:[]}));
  backend=spawn(executable,[],{cwd:root,windowsHide:true,env:{...process.env,WBOX_DATA_DIR:data},stdio:['pipe','pipe','pipe']});
  backend.stderr.on('data',chunk=>process.stderr.write(chunk));
  backend.on('error',error=>{for(const item of pending.values())item.reject(error);pending.clear();});
  createInterface({input:backend.stdout}).on('line',line=>{
    const message=JSON.parse(line),promise=pending.get(message.id);
    if(promise){pending.delete(message.id);message.error?promise.reject(Error(message.error)):promise.done(message.result);}
  });
  await call('state');
  await call('createProfile',{name:'Provider snapshot',content:JSON.stringify({
    'proxy-providers':{P:{type:'http',url:providerUrl,proxy:'DIRECT',interval:86400,'health-check':{enable:false}}},
    'proxy-groups':[{name:'Main',type:'select',use:['P']}],rules:['MATCH,Main']
  })});
  await call('settings',{delayUrl:'http://93.184.216.34/latency'});
  await call('start');
  await until(async()=>(await call('groups')).find(group=>group.name==='Main')?.nodes.includes('A'),'Provider A is loaded');
  check(await forward()==='WBOX-0','Main core loads provider A through the original endpoint');
  const profile=(await call('state')).configPath;
  const cachePath=join(dirname(profile),'proxies',createHash('md5').update(providerUrl).digest('hex'));
  const cached=JSON.parse(await readFile(cachePath,'utf8'));
  check(cached.proxies[0].port===proxies[0].address().port,'Omitted provider path resolves to proxies/MD5(url) in the main working directory');
  const runtimeFile=(await readdir(dirname(profile))).find(name=>name.startsWith('.wbox-runtime-'));
  const runtime=JSON.parse(await readFile(join(dirname(profile),runtimeFile),'utf8'));
  const headers={Authorization:'Bearer '+runtime.secret};
  const query='?timeout=5000&url='+encodeURIComponent('http://93.184.216.34/latency');
  const wrongApi=await fetch('http://'+runtime['external-controller']+'/proxies/A/delay'+query,{headers});
  const correctApi=await fetch('http://'+runtime['external-controller']+'/providers/proxies/P/A/healthcheck'+query,{headers});
  const directResult=await correctApi.json();
  providerApi={standardProxyStatus:wrongApi.status,providerProxyStatus:correctApi.status,providerDelay:directResult.delay};
  check(correctApi.ok&&directResult.delay>0,'The native provider healthcheck API can measure A through the original endpoint');
  sourceIndex=1;
  const baseline={hits:hits.slice(),sourceRequests};
  await call('testGroup',{tag:'Main'});
  await until(async()=>!(await call('state')).delayTesting,'Provider latency is finished');
  const result=(await call('groups')).find(group=>group.name==='Main')?.details.find(node=>node.name==='A');
  const measuredHits=hits.map((count,index)=>count-baseline.hits[index]);
  const afterTestSourceRequests=sourceRequests;
  const currentForward=await forward();
  evidence={providerUrl,cachePath,providerApi,baseline,measuredHits,sourceRequestsDuringTest:afterTestSourceRequests-baseline.sourceRequests,result,currentForward,groupTestError:(await call('state')).groupTestError};
  console.log('EVIDENCE',JSON.stringify(evidence));
  check(currentForward==='WBOX-0','Main connection still uses the original A after the remote source changes');
  check(result?.delay>0,'A has a visible successful latency result');
  check(measuredHits[0]>0&&measuredHits[1]===0,'Provider latency tests the original runtime endpoint instead of a changed endpoint with the same name');
  check(afterTestSourceRequests===baseline.sourceRequests,'A fixed provider snapshot does not fetch the changed remote source');
  await call('stop');
  sourceIndex=0;
  await call('createProfile',{name:'Ambiguous provider names',content:JSON.stringify({
    'proxy-providers':Object.fromEntries([['P',providerUrl],['Q',providerUrl+'/other']].map(([name,url])=>[name,{type:'http',url,proxy:'DIRECT',interval:86400,'health-check':{enable:false}}])),
    'proxy-groups':[{name:'Main',type:'select',use:['P','Q']}],rules:['MATCH,Main']
  })});
  await call('start');
  const ambiguousProfile=(await call('state')).configPath;
  await until(async()=>{
    try{return (await Promise.all([providerUrl,providerUrl+'/other'].map(url=>readFile(join(dirname(ambiguousProfile),'proxies',createHash('md5').update(url).digest('hex')))))).every(content=>JSON.parse(content).proxies[0].name==='A');}
    catch{return false;}
  },'Both same-name provider caches are ready');
  await call('groups');const ambiguousBaseline=hits.slice(),ambiguousSources=sourceRequests;
  await call('testGroup',{tag:'Main'});
  await until(async()=>!(await call('state')).delayTesting,'Ambiguous provider latency is finished');
  const ambiguousState=await call('state'),ambiguousNodes=(await call('groups')).find(group=>group.name==='Main')?.details;
  const ambiguousHits=hits.map((count,index)=>count-ambiguousBaseline[index]);
  evidence.ambiguous={groupTestError:ambiguousState.groupTestError,measuredHits:ambiguousHits,sourceRequestsDuringTest:sourceRequests-ambiguousSources,nodes:ambiguousNodes};
  check(ambiguousState.groupTestError.includes('同名节点'),'Same-name nodes from different providers are explicitly rejected');
  check(ambiguousHits.every(count=>count===0)&&ambiguousNodes?.every(node=>node.delay==null),'An ambiguous node is never measured or assigned a result from another provider');
  check((await call('service')).running,'Rejecting ambiguous provider latency preserves the main connection');
  await call('stop');
  await call('createProfile',{name:'Top-level provider name collision',content:JSON.stringify({
    proxies:[{name:'A',type:'http',server:'127.0.0.1',port:proxies[1].address().port}],
    'proxy-providers':{P:{type:'http',url:providerUrl,proxy:'DIRECT',interval:86400,'health-check':{enable:false}}},
    'proxy-groups':[{name:'Main',type:'select',use:['P']}],rules:['MATCH,Main']
  })});
  await call('start');
  await until(async()=>(await call('groups')).find(group=>group.name==='Main')?.selected==='A','The provider-backed collision group is ready');
  check(await forward()==='WBOX-0','The provider-backed group forwards through its own A rather than the top-level A');
  const collisionBaseline=hits.slice();await call('testGroup',{tag:'Main'});
  await until(async()=>!(await call('state')).delayTesting,'Top-level provider collision latency is finished');
  const collisionState=await call('state'),collisionNodes=(await call('groups')).find(group=>group.name==='Main')?.details;
  const collisionHits=hits.map((count,index)=>count-collisionBaseline[index]);
  evidence.staticCollision={groupTestError:collisionState.groupTestError,measuredHits:collisionHits,nodes:collisionNodes};
  check(collisionHits[1]===0&&(collisionHits[0]>0||collisionState.groupTestError.includes('同名节点')),'Top-level and provider name collisions never measure the wrong endpoint');
  check(await forward()==='WBOX-0','A provider-name collision leaves the selected runtime endpoint intact');
  await call('stop');
}catch(error){failure=String(error);throw error;}
finally{
  if(backend){
    backend.stdin.end();
    await new Promise(done=>{if(backend.exitCode!=null)return done();const timer=setTimeout(()=>{backend.kill();done();},7000);backend.once('exit',()=>{clearTimeout(timer);done();});});
  }
  for(const socket of sockets)socket.destroy();for(const server of [...proxies,...origins,source]){server.closeAllConnections();server.close();}
  await mkdir(join(root,'docs/verification'),{recursive:true});
  await writeFile(join(root,`docs/verification/0.7.4-windows-latency-provider${process.env.WBOX_BACKEND?'-packaged':''}.json`),JSON.stringify({passed:failure==null,checks,failure,evidence,build:process.env.WBOX_BACKEND?'packaged':'development'},null,2)+'\n');
  assert.ok(data.startsWith(join(root,'.tools','latency-provider-074-')));await rm(data,{recursive:true,force:true});
}

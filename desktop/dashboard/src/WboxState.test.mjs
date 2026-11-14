import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {runInNewContext} from 'node:vm';
import {test} from 'node:test';

const require=createRequire(new URL('../../package.json',import.meta.url));
const {transformWithEsbuild}=require('vite');
const source=readFileSync(new URL('./WboxState.ts',import.meta.url),'utf8');
const compiled=await transformWithEsbuild(source,'WboxState.ts',{target:'es2022',format:'cjs'});
const unexpected=()=>{throw new Error('Pure store tests must not access React or the desktop bridge');};
const module={exports:{}};
runInNewContext(compiled.code,{exports:module.exports,module,require(name){
  if(name==='react')return {useSyncExternalStore:unexpected};
  if(name==='./app/errorStore')return {showError:unexpected};
  if(name==='../../wbox/transport')return {call:unexpected};
  throw new Error('Unexpected import: '+name);
}});
const {createWboxStateStore}=module.exports;
const settle=()=>new Promise(resolve=>setImmediate(resolve));
function fixture(){
  const requests=[],changes=new Set(),errors=[];
  let attached=0,detached=0;
  const store=createWboxStateStore(()=>new Promise((resolve,reject)=>requests.push({resolve,reject})),listener=>{
    attached++;changes.add(listener);return ()=>{detached++;changes.delete(listener);};
  },error=>errors.push(error));
  return {store,requests,errors,emit(){for(const listener of changes)listener();},counts:()=>({attached,detached,listeners:changes.size})};
}

test('three consumers share initial RPC and one backend listener',async()=>{
  const f=fixture(),seen=[[],[],[]];
  const stop=seen.map(values=>f.store.subscribe(()=>values.push(f.store.getSnapshot())));
  assert.equal(f.requests.length,1);assert.equal(f.counts().listeners,1);
  const first={selectedNode:1};f.requests[0].resolve(first);await settle();
  for(const values of seen)assert.deepEqual(values,[first]);
  stop[0]();assert.equal(f.counts().listeners,1);
  const next={selectedNode:2};f.emit();f.requests[1].resolve(next);await settle();
  assert.deepEqual(seen[0],[first]);assert.deepEqual(seen[1],[first,next]);assert.deepEqual(seen[2],[first,next]);
  stop[1]();stop[2]();assert.deepEqual(f.counts(),{attached:1,detached:1,listeners:0});
  assert.equal(f.store.getSnapshot(),null);f.emit();assert.equal(f.requests.length,2);
});

test('50 notifications coalesce while serial responses remain visible',async()=>{
  const f=fixture(),seen=[];
  const stop=Array.from({length:3},()=>f.store.subscribe(()=>seen.push(f.store.getSnapshot())));
  for(let i=0;i<50;i++)f.emit();assert.equal(f.requests.length,1);
  const first={selectedNode:0};f.requests[0].resolve(first);await settle();
  assert.equal(f.requests.length,2);assert.equal(f.store.getSnapshot(),first);assert.equal(seen.length,3);
  const final={selectedNode:50};f.requests[1].resolve(final);await settle();
  assert.equal(f.store.getSnapshot(),final);assert.equal(seen.length,6);
  const previousRPCs=3*(1+50);
  console.log(`RPC burst: ${previousRPCs} before, ${f.requests.length} after; ${((1-f.requests.length/previousRPCs)*100).toFixed(1)}% fewer`);
  stop.forEach(off=>off());
});

test('events during a follow-up read retain the final state',async()=>{
  const f=fixture(),seen=[];const off=f.store.subscribe(()=>seen.push(f.store.getSnapshot()));
  f.emit();f.requests[0].resolve({selectedNode:0});await settle();
  f.emit();f.emit();f.requests[1].resolve({selectedNode:1});await settle();
  assert.equal(f.requests.length,3);assert.deepEqual(seen,[{selectedNode:0},{selectedNode:1}]);
  const final={selectedNode:2};f.requests[2].resolve(final);await settle();
  assert.deepEqual(seen,[{selectedNode:0},{selectedNode:1},final]);off();
});

test('continuous events show every completed read without waiting for the burst to end',async()=>{
  const f=fixture(),seen=[];const off=f.store.subscribe(()=>seen.push(f.store.getSnapshot().completed));
  for(let completed=1;completed<=5;completed++){
    for(let event=0;event<10;event++)f.emit();
    assert.equal(f.requests.length,completed);
    f.requests[completed-1].resolve({completed});await settle();
    assert.equal(f.store.getSnapshot().completed,completed);
    assert.deepEqual(seen,Array.from({length:completed},(_,index)=>index+1));
    assert.equal(f.requests.length,completed+1);
  }
  f.requests[5].resolve({completed:6});await settle();
  assert.deepEqual(seen,[1,2,3,4,5,6]);assert.equal(f.requests.length,6);off();
});

test('errors preserve the last snapshot and later notifications recover',async()=>{
  const f=fixture(),seen=[];const off=f.store.subscribe(()=>seen.push(f.store.getSnapshot()));
  const first={selectedNode:1};f.requests[0].resolve(first);await settle();
  f.emit();const error=new Error('read failed');f.requests[1].reject(error);await settle();
  assert.deepEqual(f.errors,[error]);assert.equal(f.store.getSnapshot(),first);
  f.emit();f.emit();f.requests[2].reject(error);await settle();
  assert.equal(f.requests.length,4);
  const final={selectedNode:3};f.requests[3].resolve(final);await settle();
  assert.deepEqual(seen,[first,final]);off();
});

test('unsubscribe and resubscribe isolate stale responses and errors',async()=>{
  const f=fixture();const old=f.store.subscribe(()=>assert.fail('unmounted listener called'));
  old();const seen=[];const current=f.store.subscribe(()=>seen.push(f.store.getSnapshot()));
  assert.equal(f.requests.length,2);assert.deepEqual(f.counts(),{attached:2,detached:1,listeners:1});
  const fresh={selectedNode:2};f.requests[1].resolve(fresh);await settle();
  f.requests[0].resolve({selectedNode:0});await settle();
  assert.deepEqual(seen,[fresh]);assert.equal(f.store.getSnapshot(),fresh);
  f.emit();current();f.requests[2].reject(new Error('discarded failure'));await settle();
  assert.equal(f.errors.length,0);assert.equal(f.store.getSnapshot(),null);
  assert.deepEqual(f.counts(),{attached:2,detached:2,listeners:0});
});

test('stale completion cannot release a new subscription pending request',async()=>{
  const f=fixture();const old=f.store.subscribe(()=>{});old();const off=f.store.subscribe(()=>{});
  f.requests[0].resolve({selectedNode:0});await settle();
  f.emit();assert.equal(f.requests.length,2);
  f.requests[1].resolve({selectedNode:1});await settle();assert.equal(f.requests.length,3);
  f.requests[2].resolve({selectedNode:2});await settle();assert.equal(f.store.getSnapshot().selectedNode,2);off();
});

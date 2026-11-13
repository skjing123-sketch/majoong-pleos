import test from 'node:test';
import assert from 'node:assert/strict';
import {CASE_RESULT_MAX_AGE_MS,caseResultsFresh,fetchJsonWithDeadline} from '../web/case-data.js';

const now=Date.parse('2026-11-13T12:00:00+09:00');
const live=age=>({mode:'live',casesReady:true,meta:{fetchedAt:new Date(now-age).toISOString()}});
const clock=()=>{
  let task=null,cleared=false;
  return {schedule(fn){task=fn;return 1;},cancel(){cleared=true;},expire(){assert.ok(task);task();},get cleared(){return cleared;}};
};
const response=(data,ok=true)=>({ok,json:async()=>data});

test('freshness uses provider fetch time, including the exact five-minute boundary',()=>{
  assert.equal(caseResultsFresh(live(0),now),true);
  assert.equal(caseResultsFresh(live(CASE_RESULT_MAX_AGE_MS-1),now),true);
  assert.equal(caseResultsFresh(live(CASE_RESULT_MAX_AGE_MS),now),false);
});
test('missing, invalid and far-future live fetch times do not authorize photos',()=>{
  for(const fetchedAt of [undefined,null,'not-a-date',new Date(now+30_001).toISOString()]){
    assert.equal(caseResultsFresh({mode:'live',casesReady:true,meta:{fetchedAt}},now),false);
  }
  assert.equal(caseResultsFresh(live(-30_000),now),true);
});
test('unready data stays unavailable; explicit fictional demo does not need provider time',()=>{
  assert.equal(caseResultsFresh({...live(0),casesReady:false},now),false);
  assert.equal(caseResultsFresh({mode:'demo',casesReady:true},now),true);
});
test('response deadline clears on a successful JSON body',async()=>{
  const timer=clock();
  const data=await fetchJsonWithDeadline('/api/cases',{...timer,fetchImpl:async()=>response({cases:[]})});
  assert.deepEqual(data,{cases:[]});assert.equal(timer.cleared,true);
});
test('deadline bounds a transport which never settles and ignores abort',async()=>{
  const timer=clock();let requestSignal;
  const request=fetchJsonWithDeadline('/api/cases',{...timer,fetchImpl:(_,options)=>{requestSignal=options.signal;return new Promise(()=>{});}});
  await Promise.resolve();timer.expire();
  await assert.rejects(request,{name:'TimeoutError'});
  assert.equal(requestSignal.aborted,true);assert.equal(timer.cleared,true);
});
test('a stalled JSON body also hits the deadline',async()=>{
  const timer=clock();
  const request=fetchJsonWithDeadline('/api/cases',{...timer,fetchImpl:async()=>({ok:true,json:()=>new Promise(()=>{})})});
  await Promise.resolve();await Promise.resolve();timer.expire();
  await assert.rejects(request,{name:'TimeoutError'});
});
test('abort-aware transport cannot turn deadline into silent user cancellation',async()=>{
  const timer=clock();
  const request=fetchJsonWithDeadline('/api/cases',{...timer,fetchImpl:(_,options)=>new Promise((_,reject)=>{
    options.signal.addEventListener('abort',()=>reject(Object.assign(new Error(),{name:'AbortError'})));
  })});
  await Promise.resolve();timer.expire();await assert.rejects(request,{name:'TimeoutError'});
});
test('departure/region cancellation aborts the transport and releases its deadline',async()=>{
  const timer=clock(),parent=new AbortController();let requestSignal;
  const request=fetchJsonWithDeadline('/api/cases',{...timer,signal:parent.signal,fetchImpl:(_,options)=>{requestSignal=options.signal;return new Promise(()=>{});}});
  await Promise.resolve();parent.abort();await assert.rejects(request,{name:'AbortError'});
  assert.equal(requestSignal.aborted,true);assert.equal(timer.cleared,true);
});
test('an already-cancelled request never starts a fetch',async()=>{
  const parent=new AbortController();parent.abort();let calls=0;
  await assert.rejects(fetchJsonWithDeadline('/api/cases',{signal:parent.signal,fetchImpl:async()=>{calls++;}}),{name:'AbortError'});
  assert.equal(calls,0);
});
test('a departure before the fetch microtask prevents starting even an abort-ignoring transport',async()=>{
  const parent=new AbortController();let calls=0;
  const request=fetchJsonWithDeadline('/api/cases',{signal:parent.signal,fetchImpl:async()=>{calls++;return response({});}});
  parent.abort();await assert.rejects(request,{name:'AbortError'});assert.equal(calls,0);
});
test('provider failures keep a useful bounded message and clear the deadline',async()=>{
  const timer=clock();
  await assert.rejects(fetchJsonWithDeadline('/api/cases',{...timer,fetchImpl:async()=>response({error:{message:'공공데이터에 연결하지 못했습니다.'}},false)}),/공공데이터/);
  assert.equal(timer.cleared,true);
});
test('malformed JSON and transport errors use clear messages without raw technical detail',async()=>{
  const invalidBody=()=>Promise.reject(new SyntaxError('SECRET_BODY_FRAGMENT'));
  await assert.rejects(fetchJsonWithDeadline('/api/cases',{fetchImpl:async()=>({ok:true,json:invalidBody})}),error=>/공고 응답/.test(error.message)&&!error.message.includes('SECRET'));
  await assert.rejects(fetchJsonWithDeadline('/api/cases',{fetchImpl:()=>Promise.reject(new TypeError('SECRET_UPSTREAM_URL'))}),error=>/네트워크 연결/.test(error.message)&&!error.message.includes('SECRET'));
});

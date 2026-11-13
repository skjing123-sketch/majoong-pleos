import test from 'node:test';
import assert from 'node:assert/strict';
import {initialState,receiveArrival,applyVehicle,applyDestinationAddress,mayDisplay,shouldShowArrivalPopup,reconcileNativeArrival,invalidateNativeTrip,isNativeTripInvalidation} from '../web/model-contest.js';

const NOW=1_000_000;
const edge=(reason)=>({type:'trip-invalidated',source:'pleos-navi',reason});
function parkedArrival(){
  let s={...initialState(),platform:'pleos',mode:'live',requestId:4};
  s=applyVehicle(s,{displayAllowed:true,gear:'P',speedMetersPerSecond:0});
  s=receiveArrival(s,'pleos:12',{sequence:12,timestamp:NOW,now:NOW});
  s=applyDestinationAddress(s,'경기도 성남시 수정구');
  return {...s,cases:[{id:'old',species:'dog'}],meta:{fetchedAt:NOW},casesReady:true,seen:new Set(['old'])};
}
for(const reason of ['route_started']){
 test(`${reason} clears an unconsumed parked arrival and its photos`,()=>{
  const old=parkedArrival();assert.equal(shouldShowArrivalPopup(old,NOW+1),true);
  const next=invalidateNativeTrip(old,edge(reason));
  assert.equal(next.arrived,false);assert.equal(next.arrivalKey,null);assert.equal(next.arrivalSeq,0);
  assert.equal(next.arrivalPopupPending,false);assert.equal(next.displayAllowed,false);
  assert.equal(next.destinationRegionResolved,false);assert.equal(next.casesReady,false);
  assert.deepEqual(next.cases,[]);assert.equal(next.meta,null);assert.equal(next.seen,old.seen);assert.equal(next.seen.has('old'),true);
  assert.equal(mayDisplay(next),false);assert.equal(shouldShowArrivalPopup(next,NOW+1),false);
  assert.equal(next.requestId,old.requestId); // request cancellation is owned by the app handler.
 });
}
test('ordinary destination info and service rebind do not invalidate a current arrival',()=>{
 const old=parkedArrival();
 for(const info of [{type:'destination',source:'pleos-navi'},{type:'snapshot',events:{}},{type:'lifecycle',phase:'resumed'}]){
  assert.equal(isNativeTripInvalidation(info),false);assert.equal(invalidateNativeTrip(old,info),old);
 }
 const restored=reconcileNativeArrival(old,{arrivalSeq:12,timestamp:NOW,address:'경기도 성남시'},{now:NOW+100});
 assert.equal(restored.arrived,true);assert.equal(restored.arrivalSeq,12);
});
test('unrecognized source and reason cannot reset the trip',()=>{
 const old=parkedArrival();
 for(const e of [{...edge('route_started'),source:'other'},edge('snapshot'),edge('destination_changed'),{type:'trip-invalidated'}]){
  assert.equal(invalidateNativeTrip(old,e),old);
 }
});
test('browser and demo state are unaffected by native trip changes',()=>{
 for(const platform of ['browser','demo']){
  const old={...parkedArrival(),platform};assert.equal(invalidateNativeTrip(old,edge('route_started')),old);
 }
});
test('a snapshot without an arrival cannot resurrect invalidated pictures',()=>{
 const next=reconcileNativeArrival(invalidateNativeTrip(parkedArrival(),edge('route_started')),null,{now:NOW+100});
 assert.equal(next.arrived,false);assert.equal(next.arrivalPopupPending,false);assert.deepEqual(next.cases,[]);
});
test('a subsequent real arrival still needs its new parking and region confirmation',()=>{
 let next=invalidateNativeTrip(parkedArrival(),edge('route_started'));
 next=receiveArrival(next,'pleos:13',{sequence:13,timestamp:NOW+500,now:NOW+500});
 assert.equal(mayDisplay(next),false);assert.equal(next.arrivalSeq,13);
 next=applyVehicle(next,{displayAllowed:true,gear:'P',speedMetersPerSecond:0});
 assert.equal(mayDisplay(next),true);assert.equal(next.destinationRegionResolved,false);
 next=applyDestinationAddress(next,'서울특별시 강남구');
 assert.equal(next.region,'서울특별시 강남구');assert.equal(next.destinationRegionResolved,true);
 assert.equal(next.arrivalPopupPending,true);assert.equal(next.casesReady,false);
});
test('a route change while waiting to park also removes the pending old arrival',()=>{
 const old=applyVehicle(parkedArrival(),{displayAllowed:false,gear:'UNKNOWN',speedMetersPerSecond:null});
 const next=invalidateNativeTrip(old,edge('route_started'));
 assert.equal(next.arrived,false);assert.equal(next.arrivalPopupPending,false);
 assert.equal(mayDisplay(applyVehicle(next,{displayAllowed:true,gear:'P',speedMetersPerSecond:0})),false);
});

test('destination-change callback alone cannot erase an arrival after automatic route clearing',()=>{
 const old=parkedArrival();
 assert.equal(invalidateNativeTrip(old,edge('destination_changed')),old);
 assert.equal(old.arrived,true);
});

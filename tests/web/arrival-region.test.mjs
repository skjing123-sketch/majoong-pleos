import test from 'node:test';
import assert from 'node:assert/strict';
import {
  initialState,receiveArrival,applyVehicle,applyActivity,clearArrival,resetNativeSession,
  reconcileNativeArrival,applyDestinationAddress,confirmManualRegion,mayDisplay,
  mayQueryCases,shouldShowArrivalPopup,visibleCases,replaceCaseResults,ARRIVAL_POPUP_TTL_MS,
} from '../../web/model-contest.js';

const now=1_000_000;
const native=()=>({...initialState(),platform:'pleos',mode:'live'});
const arrive=(state=native(),sequence=1)=>receiveArrival(state,`pleos:${sequence}`,{sequence,timestamp:now,now});
const park=state=>applyVehicle(state,{displayAllowed:true,gear:'P',speedMetersPerSecond:0});
const loaded=state=>replaceCaseResults(state,[{id:'old-region-case',species:'dog'}],{});

test('an addressless real arrival keeps arrival and parking but never queries the default region',()=>{
  const state=applyDestinationAddress(park(arrive()),undefined);
  assert.equal(state.arrived,true);
  assert.equal(state.arrivalSeq,1);
  assert.equal(state.displayAllowed,true);
  assert.equal(mayDisplay(state),true);
  assert.equal(state.region,'성남시'); // Preference only, not a destination assertion.
  assert.equal(state.destinationRegionResolved,false);
  assert.equal(mayQueryCases(state),false);
  assert.equal(shouldShowArrivalPopup(loaded(state),now),false);
  assert.deepEqual(visibleCases(loaded(state)),[]);
});

test('missing or unparsable SDK addresses cannot reuse prior region results or a nationwide preference',()=>{
  for(const address of [undefined,null,'','  ','카페 마중',{}]){
    let state=confirmManualRegion(park(arrive()),'');
    state=loaded(state);
    state=arrive(state,2);
    state=applyDestinationAddress(state,address);
    assert.equal(state.region,'');
    assert.equal(mayQueryCases(state),false);
    assert.equal(state.casesReady,false);
    assert.deepEqual(state.cases,[]);
    assert.equal(state.destinationRegionSource,'unknown');
  }
});

test('a parsed SDK region authorizes only the parked current arrival',()=>{
  let state=applyDestinationAddress(arrive(),'서울시 강남구 남부순환로 2621');
  assert.equal(state.region,'서울특별시 강남구');
  assert.equal(state.destinationRegionSource,'sdk');
  assert.equal(state.destinationRegionResolved,true);
  assert.equal(mayQueryCases(state),false);
  state=park(state);
  assert.equal(mayQueryCases(state),true);
  assert.equal(shouldShowArrivalPopup(loaded(state),now),true);
  assert.equal(mayQueryCases(state,false),false);
});

test('two-character district names do not silently widen the destination to the whole province',()=>{
  for(const [address,region] of [
    ['서울특별시 중구 세종대로','서울특별시 중구'],
    ['부산 남구 용소로','부산광역시 남구'],
    ['대구 동구 동부로','대구광역시 동구'],
    ['인천 서구 서곶로','인천광역시 서구'],
  ]){
    const state=applyDestinationAddress(park(arrive()),address);
    assert.equal(state.region,region);
    assert.equal(state.destinationRegionResolved,true);
  }
});

test('a new arrival invalidates the last region even before address parsing runs',()=>{
  let state=loaded(applyDestinationAddress(park(arrive()),'경기도 성남시'));
  state=arrive(state,2);
  assert.equal(state.destinationRegionResolved,false);
  assert.equal(mayQueryCases(state),false);
  assert.deepEqual(visibleCases(state),[]);
  state=applyDestinationAddress(state,'경기도 성남시');
  assert.deepEqual(state.cases,[]);
  assert.equal(state.casesReady,false); // Same city still requires a fresh arrival query.
});

test('explicit manual choice resolves an unknown arrival without inventing arrival or parking',()=>{
  let state=confirmManualRegion(arrive(),'부산광역시');
  assert.equal(state.destinationRegionSource,'manual');
  assert.equal(state.arrivalSeq,1);
  assert.equal(state.displayAllowed,false);
  assert.equal(mayQueryCases(state),false);
  assert.equal(mayQueryCases(park(state)),true);
  const beforeArrival=confirmManualRegion(native(),'부산광역시');
  assert.equal(beforeArrival.arrived,false);
  assert.equal(beforeArrival.destinationRegionResolved,false);
});

test('only an explicit empty manual selection permits nationwide; invalid input does not',()=>{
  const selected=confirmManualRegion(park(arrive()),'');
  assert.equal(selected.region,'');
  assert.equal(selected.destinationRegionSource,'manual');
  assert.equal(mayQueryCases(selected),true);
  for(const region of [undefined,null,{},'   ']){
    assert.equal(mayQueryCases(confirmManualRegion(selected,region)),false);
  }
});

test('manual choice survives duplicate foreground arrival/address updates but not the next arrival',()=>{
  const selected=confirmManualRegion(park(arrive()),'부산광역시');
  let state=receiveArrival(selected,'pleos:1',{sequence:1,timestamp:now,now});
  state=applyDestinationAddress(state,'서울시 강남구');
  assert.equal(state.region,'부산광역시');
  assert.equal(state.destinationRegionSource,'manual');
  state=arrive(state,2);
  assert.equal(mayQueryCases(state),false);
  state=applyDestinationAddress(state,'서울시 강남구');
  assert.equal(state.region,'서울특별시 강남구');
  assert.equal(state.destinationRegionSource,'sdk');
});

test('resume cannot reuse manual confirmation and a snapshot without arrival stays blocked',()=>{
  let state=confirmManualRegion(park(arrive()),'');
  state=applyActivity(state,false);
  state=resetNativeSession(state);
  state=applyActivity(state,true);
  state=park(state);
  assert.equal(mayQueryCases(state),false);
  state=reconcileNativeArrival(state,null,{now});
  assert.equal(state.arrived,false);
  assert.equal(state.destinationRegionResolved,false);
});

test('restored canonical arrival requires address re-resolution, fresh parking and fresh results',()=>{
  let state=applyDestinationAddress(park(arrive()),'경기도 성남시');
  state=resetNativeSession(applyActivity(loaded(state),false));
  state=applyActivity(state,true);
  const arrival={arrivalSeq:1,timestamp:now,address:'서울시 강남구'};
  state=reconcileNativeArrival(state,arrival,{now:now+1000});
  assert.equal(mayQueryCases(state),false);
  state=applyDestinationAddress(state,arrival.address);
  assert.equal(state.region,'서울특별시 강남구');
  assert.equal(mayQueryCases(state),false);
  state=park(state);
  assert.equal(mayQueryCases(state),true);
  assert.equal(shouldShowArrivalPopup(state,now+1000),false);
  assert.equal(shouldShowArrivalPopup(loaded(state),now+1000),true);
});

test('restored addressless arrival requires another manual choice, even when sequence is reused',()=>{
  let state=confirmManualRegion(park(arrive()),'성남시');
  state=resetNativeSession(applyActivity(state,false));
  state=applyActivity(state,true);
  state=reconcileNativeArrival(state,{arrivalSeq:1,timestamp:now+1000},{now:now+1000});
  state=park(applyDestinationAddress(state,null));
  assert.equal(state.arrived,true);
  assert.equal(mayQueryCases(state),false);
  assert.equal(mayQueryCases(confirmManualRegion(state,'서울특별시')),true);
});

test('departure and explicit clear revoke the region; selection never extends the nudge TTL',()=>{
  const state=confirmManualRegion(park(arrive()),'성남시');
  const departed=applyVehicle(state,{displayAllowed:false,gear:'NOT_P',speedMetersPerSecond:1});
  assert.equal(departed.destinationRegionResolved,false);
  assert.equal(clearArrival(state).destinationRegionResolved,false);
  assert.equal(shouldShowArrivalPopup(loaded(confirmManualRegion(state,'서울특별시')),now+ARRIVAL_POPUP_TTL_MS),false);
});

test('browser and portable demo keep the existing selected-region flow',()=>{
  for(const platform of ['browser','demo']){
    const state=park(receiveArrival({...initialState(),platform},'demo:1',{timestamp:now,now}));
    assert.equal(mayQueryCases(state),true);
    assert.equal(shouldShowArrivalPopup(loaded(state),now),true);
    assert.equal(applyDestinationAddress(state,null),state);
    assert.equal(confirmManualRegion(state,'부산광역시').region,'부산광역시');
  }
});

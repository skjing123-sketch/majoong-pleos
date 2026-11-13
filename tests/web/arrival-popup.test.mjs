import test from 'node:test';
import assert from 'node:assert/strict';
import {initialState,receiveArrival,consumeArrivalPopup,clearArrival,resetNativeSession,reconcileNativeArrival,arrivalPopupExpired,ARRIVAL_POPUP_TTL_MS,shouldShowArrivalPopup,mayDisplay,mayQueryCases,applyActivity,applyVehicle} from '../../web/model-contest.js';

const loaded = state => ({...state,loading:false,error:null,casesReady:true,cases:[{id:'fictional-case'}]});
const parked = state => applyVehicle(state,{displayAllowed:true,gear:'P',speedMetersPerSecond:0});
const arrived = (state=initialState(),sequence=1,handled=false) => receiveArrival(state,`pleos:${sequence}`,{sequence,handled});

test('popup waits for both vehicle permission and a completed successful nonempty query',()=>{
  let state=arrived();
  assert.equal(shouldShowArrivalPopup(loaded(state)),false);
  state=parked(state);
  assert.equal(shouldShowArrivalPopup(state),false);
  assert.equal(shouldShowArrivalPopup({...loaded(state),loading:true}),false);
  assert.equal(shouldShowArrivalPopup({...loaded(state),error:'offline'}),false);
  assert.equal(shouldShowArrivalPopup({...loaded(state),cases:[]}),false);
  assert.equal(shouldShowArrivalPopup(loaded(state)),true);
});
test('parking or old case data without a new arrival does not open a popup',()=>{
  assert.equal(shouldShowArrivalPopup(loaded(parked(initialState()))),false);
  assert.equal(shouldShowArrivalPopup(arrived(loaded(parked(initialState())))),false);
});
test('displaying or dismissing a popup consumes the arrival across refetches',()=>{
  let state=loaded(parked(arrived()));
  assert.equal(shouldShowArrivalPopup(state),true);
  state=consumeArrivalPopup(state);
  assert.equal(shouldShowArrivalPopup(loaded({...state,loading:true,cases:[],casesReady:false})),false);
  assert.equal(receiveArrival(state,'pleos:1',{sequence:1}),state);
});
test('a distinct actual arrival can open again only after its own query completes',()=>{
  let state=consumeArrivalPopup(loaded(parked(arrived())));
  state=arrived(state,2);
  assert.equal(state.arrivalSeq,2);
  assert.equal(shouldShowArrivalPopup(state),false);
  assert.equal(shouldShowArrivalPopup(loaded(state)),true);
});
test('native handled replay on a fresh WebView never reopens the popup',()=>{
  const replay=arrived(initialState(),7,true);
  assert.equal(replay.arrived,true);
  assert.equal(shouldShowArrivalPopup(loaded(parked(replay))),false);
});
test('unknown and stale vehicle input never permit a popup',()=>{
  const state=loaded(parked(arrived()));
  assert.equal(shouldShowArrivalPopup(applyVehicle(state,{})),false);
  assert.equal(shouldShowArrivalPopup(applyVehicle(state,{displayAllowed:false,reason:'vehicle_state_stale'})),false);
});
test('an already shown popup stays consumed after a temporary gate failure',()=>{
  let state=consumeArrivalPopup(loaded(parked(arrived())));
  state=applyVehicle(state,{displayAllowed:false,reason:'vehicle_state_stale'});
  assert.equal(shouldShowArrivalPopup(loaded(parked(state))),false);
});
test('background retains an unshown popup and resume needs new permission and a new successful query',()=>{
  let state=applyActivity(arrived(),false);
  state=loaded(parked(state));
  assert.equal(state.arrivalPopupPending,true);
  assert.equal(shouldShowArrivalPopup(state),false);
  state=applyActivity(state,true);
  assert.equal(state.displayAllowed,false);
  assert.equal(state.casesReady,false);
  assert.equal(shouldShowArrivalPopup(parked(state)),false);
  assert.equal(shouldShowArrivalPopup(loaded(parked(state))),true);
});
test('arrival received while navigation is foreground waits for app return and parking',()=>{
  let state=arrived(applyActivity(initialState(),false));
  assert.equal(state.arrived,true);
  assert.equal(state.arrivalPopupPending,true);
  assert.equal(shouldShowArrivalPopup(loaded(parked(state))),false);
  state=applyActivity(state,true);
  assert.equal(shouldShowArrivalPopup(loaded(state)),false);
  assert.equal(shouldShowArrivalPopup(loaded(parked(state))),true);
});
test('departure before query completes expires a pending popup',()=>{
  let state=parked(arrived());
  state=applyVehicle(state,{displayAllowed:false,gear:'NOT_P',speedMetersPerSecond:1});
  assert.equal(state.arrived,false);
  assert.equal(state.arrivalPopupPending,false);
  assert.equal(shouldShowArrivalPopup(loaded(parked(state))),false);
});
test('browser simulation keys cannot impersonate a native arrival sequence',()=>{
  const state=receiveArrival(initialState(),'demo:1');
  assert.equal(state.arrivalSeq,0);
  assert.equal(shouldShowArrivalPopup(loaded(parked(state))),true);
});
test('stopping monitoring clears pending arrival and rejects its later replay',()=>{
  const stopped=clearArrival(parked(arrived()));
  assert.equal(stopped.arrived,false);
  assert.equal(stopped.arrivalPopupPending,false);
  assert.equal(stopped.phase,'ready');
  assert.equal(receiveArrival(stopped,'pleos:1',{sequence:1}),stopped);
  assert.equal(shouldShowArrivalPopup(loaded(parked(stopped))),false);
  assert.equal(shouldShowArrivalPopup(loaded(parked(arrived(stopped,2)))),true);
});
test('a shown popup remains consumed across background and return',()=>{
  let state=consumeArrivalPopup(loaded(parked(arrived())));
  state=applyActivity(state,false);
  state=applyActivity(state,true);
  assert.equal(shouldShowArrivalPopup(loaded(parked(state))),false);
});
test('parking then departure in background discards an unshown pending popup',()=>{
  let state=arrived(applyActivity(initialState(),false));
  state=parked(state);
  state=applyVehicle(state,{displayAllowed:false,gear:'NOT_P',speedMetersPerSecond:1});
  assert.equal(state.arrivalPopupPending,false);
  assert.equal(shouldShowArrivalPopup(loaded(parked(applyActivity(state,true)))),false);
});
test('hidden documents and inactive activities cannot start case queries',()=>{
  const state=loaded(parked(arrived()));
  assert.equal(mayQueryCases(state,true),true);
  assert.equal(mayQueryCases(state,false),false);
  assert.equal(mayQueryCases({...state,active:false},true),false);
});
test('native invalidation removes a pending popup even when WebView missed vehicle events',()=>{
  const state=arrived(applyActivity(initialState(),false));
  const restored=reconcileNativeArrival(applyActivity(state,true),null);
  assert.equal(restored.arrived,false);
  assert.equal(restored.arrivalPopupPending,false);
  assert.equal(shouldShowArrivalPopup(loaded(parked(restored))),false);
});
test('native pending replay preserves original arrival time and handled contract',()=>{
  const timestamp=1_000_000, now=timestamp+60_000;
  let state=reconcileNativeArrival(initialState(),{arrivalSeq:7,timestamp},{now});
  assert.equal(state.arrivalAt,timestamp);
  assert.equal(shouldShowArrivalPopup(loaded(parked(state)),now),true);
  state=reconcileNativeArrival(state,{arrivalSeq:7,timestamp},{now:now+60_000,handledSequence:7});
  assert.equal(state.arrivalAt,timestamp);
  assert.equal(state.arrivalPopupPending,false);
  const handled=reconcileNativeArrival(initialState(),{arrivalSeq:7,timestamp,popupHandled:true},{now});
  assert.equal(handled.arrivalPopupPending,false);
});
test('pending popup expires at five minutes without invalidating the case list',()=>{
  const timestamp=1_000_000;
  let state=loaded(parked(receiveArrival(initialState(),'pleos:1',{sequence:1,timestamp,now:timestamp})));
  assert.equal(shouldShowArrivalPopup(state,timestamp+ARRIVAL_POPUP_TTL_MS-1),true);
  assert.equal(arrivalPopupExpired(state,timestamp+ARRIVAL_POPUP_TTL_MS),true);
  assert.equal(shouldShowArrivalPopup(state,timestamp+ARRIVAL_POPUP_TTL_MS),false);
  state=consumeArrivalPopup(state);
  assert.equal(mayDisplay(state),true);
  assert.equal(state.arrived,true);
});
test('replay and resume cannot reset a valid SDK arrival timestamp',()=>{
  const timestamp=1_000_000, now=timestamp+ARRIVAL_POPUP_TTL_MS;
  let state=receiveArrival(initialState(),'pleos:1',{sequence:1,timestamp,now:timestamp});
  state=receiveArrival(state,'pleos:1',{sequence:1,timestamp:now,now});
  assert.equal(state.arrivalAt,timestamp);
  state=applyActivity(applyActivity(state,false),true);
  assert.equal(state.arrivalAt,timestamp);
  assert.equal(shouldShowArrivalPopup(loaded(parked(state)),now),false);
  const reloaded=reconcileNativeArrival(initialState(),{arrivalSeq:1,timestamp},{now});
  assert.equal(shouldShowArrivalPopup(loaded(parked(reloaded)),now),false);
});
test('missing or invalid SDK timestamps fall back to reception time',()=>{
  const now=1_000_000;
  for(const timestamp of [undefined,null,'invalid',NaN,Infinity,-1,0,now+1]){
    const state=receiveArrival(initialState(),'pleos:1',{sequence:1,timestamp,now});
    assert.equal(state.arrivalAt,now);
    assert.equal(arrivalPopupExpired(state,now),false);
  }
});
test('a service snapshot without arrival cannot revive an old background arrival',()=>{
  let state=resetNativeSession(applyActivity(loaded(parked(arrived())),false));
  state=applyActivity(state,true);state=parked(state);
  state=reconcileNativeArrival(state,null);
  assert.equal(state.arrivalKey,null);assert.equal(state.arrived,false);assert.equal(shouldShowArrivalPopup(loaded(parked(state))),false);
});
test('canonical pending service replay after reset preserves original timestamp and needs a new query',()=>{
  const timestamp=1_000_000,now=timestamp+60_000;
  let state=resetNativeSession(receiveArrival(initialState(),'pleos:7',{sequence:7,timestamp,now:timestamp}));
  state=reconcileNativeArrival(parked(state),{arrivalSeq:7,timestamp},{now});
  assert.equal(state.arrivalAt,timestamp);assert.equal(shouldShowArrivalPopup(state,now),false);
  assert.equal(shouldShowArrivalPopup(loaded(state),now),true);
});
test('native handled sequence still suppresses a service replay after web session reset',()=>{
  let state=resetNativeSession(consumeArrivalPopup(loaded(parked(arrived(initialState(),7)))));
  state=reconcileNativeArrival(state,{arrivalSeq:7,timestamp:Date.now()},{handledSequence:7});
  assert.equal(state.arrived,true);assert.equal(shouldShowArrivalPopup(loaded(parked(state))),false);
});
test('a restarted native service may introduce sequence one with canonical handled zero',()=>{
  let state=resetNativeSession(consumeArrivalPopup(loaded(parked(arrived(initialState(),7)))));
  state=reconcileNativeArrival(state,{arrivalSeq:1,timestamp:Date.now()},{handledSequence:0});
  assert.equal(state.arrivalSeq,1);assert.equal(shouldShowArrivalPopup(loaded(parked(state))),true);
});

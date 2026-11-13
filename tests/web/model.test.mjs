import test from 'node:test';
import assert from 'node:assert/strict';
import {initialState,mayDisplay,applyVehicle,regionFromAddress,speciesOf,visibleCases,replaceCaseResults,safePhone,nativeConnectionIssue} from '../../web/model-contest.js';
test('arrival alone never exposes cases',()=>{const s={...initialState(),arrived:true,cases:[{id:'a'}]};assert.equal(mayDisplay(s),false);assert.deepEqual(visibleCases(s),[]);});
test('parking without arrival never exposes cases',()=>{assert.equal(mayDisplay({...initialState(),displayAllowed:true}),false);});
test('park and arrival together expose cases',()=>{const s=applyVehicle({...initialState(),arrived:true,phase:'arrived'},{displayAllowed:true});assert.equal(mayDisplay(s),true);assert.equal(s.phase,'parked');});
test('moving after parking revokes arrival and display',()=>{const s=applyVehicle({...initialState(),phase:'parked',arrived:true,displayAllowed:true},{displayAllowed:false,gear:'NOT_P'});assert.equal(s.arrived,false);assert.equal(mayDisplay(s),false);});
test('waiting for parking preserves a just received arrival',()=>{const s=applyVehicle({...initialState(),phase:'arrived',arrived:true},{displayAllowed:false});assert.equal(s.arrived,true);assert.equal(s.phase,'arrived');});
test('unknown vehicle permission fails closed',()=>{const s=applyVehicle({...initialState(),phase:'parked',arrived:true,displayAllowed:true},{});assert.equal(mayDisplay(s),false);});
test('an explicit departure overrides a contradictory display permission',()=>{
  const parked={...initialState(),phase:'parked',arrived:true,arrivalUnlocked:true,displayAllowed:true};
  for(const signal of [{displayAllowed:true,gear:'NOT_P',speedMetersPerSecond:0},{displayAllowed:true,gear:'P',speedMetersPerSecond:2}]){
    const next=applyVehicle(parked,signal);
    assert.equal(mayDisplay(next),false);
    assert.equal(next.arrived,false);
  }
});
test('temporarily stale signal hides content but preserves arrival',()=>{let s=applyVehicle({...initialState(),arrived:true},{displayAllowed:true});s=applyVehicle(s,{displayAllowed:false,reason:'vehicle_state_stale'});assert.equal(s.arrived,true);assert.equal(mayDisplay(s),false);s=applyVehicle(s,{displayAllowed:false,gear:'NOT_P'});assert.equal(s.arrived,false);});
test('truthy string does not grant vehicle display permission',()=>{assert.equal(applyVehicle(initialState(),{displayAllowed:'true'}).displayAllowed,false);});
test('species filters preserve the backend unknown classification after a catalog failure',()=>{
  const unknown={id:'unknown',species:'other',breed:'진돗개'};
  const state={...initialState(),arrived:true,displayAllowed:true,species:'dog',cases:[unknown]};
  assert.equal(speciesOf(unknown),'other');
  assert.deepEqual(visibleCases(state),[]);
  assert.equal(speciesOf({species:'other',breed:'개구리'}),'other');
  assert.equal(speciesOf({species:'dog',breed:'[고양이] 혼합된 입력'}),'dog');
});
test('legacy records without canonical species require an explicit species label',()=>{
  assert.equal(speciesOf({breed:'[고양이] 한국 고양이'}),'cat');
  assert.equal(speciesOf({breed:'[개] 믹스견'}),'dog');
  assert.equal(speciesOf({breed:'개구리'}),'other');
});
test('a dial link requires enough actual digits rather than formatting characters',()=>{
  for(const value of ['+       ','1------','12 (34)','1234567890123456','-------'])assert.equal(safePhone(value),null);
  assert.equal(safePhone('+82 (10) 0000-0000'),'+821000000000');
});
test('a refreshed list removes disappeared viewed notices without claiming resolution',()=>{
  const old={...initialState(),arrived:true,displayAllowed:true,view:'seen',seen:new Set(['gone','retained']),cases:[{id:'gone'},{id:'retained'}]};
  const retained={id:'retained',species:'dog',status:'unknown'};
  const refreshed=replaceCaseResults(old,[retained,{id:'new',species:'cat',status:'unknown'}],{});
  assert.deepEqual([...refreshed.seen],['retained']);
  assert.deepEqual(visibleCases(refreshed),[retained]);
  assert.equal(refreshed.cases[0].status,'unknown');
  const empty=replaceCaseResults(refreshed,[],{});
  assert.equal(empty.seen.size,0);
  assert.deepEqual(visibleCases(empty),[]);
});
test('background lifecycle remains locked even with a later parked tick',()=>{const s=applyVehicle({...initialState(),active:false,arrived:true},{displayAllowed:true});assert.equal(mayDisplay(s),false);assert.deepEqual(visibleCases(s),[]);});
test('arrival parking and departure all in background expire the arrival',()=>{let s={...initialState(),active:false,arrived:true};s=applyVehicle(s,{displayAllowed:true,gear:'P'});assert.equal(s.arrivalUnlocked,true);assert.equal(mayDisplay(s),false);s=applyVehicle(s,{displayAllowed:false,gear:'NOT_P'});s={...s,active:true};s=applyVehicle(s,{displayAllowed:true,gear:'P'});assert.equal(s.arrived,false);assert.equal(mayDisplay(s),false);});
test('departure while background prevents stale arrival reopening after resume',()=>{let s=applyVehicle({...initialState(),arrived:true},{displayAllowed:true});s={...s,active:false};s=applyVehicle(s,{displayAllowed:false,gear:'NOT_P'});s=applyVehicle(s,{displayAllowed:true,gear:'P'});s={...s,active:true};s=applyVehicle(s,{displayAllowed:true,gear:'P'});assert.equal(s.arrived,false);assert.equal(mayDisplay(s),false);});
test('SDK destination addresses preserve province to distinguish duplicate districts',()=>{assert.equal(regionFromAddress('경기도 성남시 분당구 판교역로'), '경기도 성남시');assert.equal(regionFromAddress('서울특별시 강서구'),'서울특별시 강서구');assert.equal(regionFromAddress('부산 강서구'),'부산광역시 강서구');assert.equal(regionFromAddress('부산광역시'),'부산광역시');assert.equal(regionFromAddress('카페 마중'),null);});
test('SDK sample Seoul-si address resolves to its district',()=>{assert.equal(regionFromAddress('서울시 강남구 남부순환로 2621'),'서울특별시 강남구');assert.equal(regionFromAddress('  서울시   강서구 가상로 1  '),'서울특별시 강서구');});
test('province aliases are not repeated as municipalities',()=>{assert.equal(regionFromAddress('서울시'),'서울특별시');assert.equal(regionFromAddress('서울시 가상로 1'),'서울특별시');assert.equal(regionFromAddress('세종'),'세종특별자치시');});
test('existing province abbreviations and unprefixed municipalities remain supported',()=>{for(const [address,expected] of [['경기 성남시 분당구','경기도 성남시'],['인천 연수구','인천광역시 연수구'],['강원 춘천시','강원특별자치도 춘천시'],['전북 전주시','전북특별자치도 전주시'],['제주 제주시','제주특별자치도 제주시'],['성남시 분당구','성남시']])assert.equal(regionFromAddress(address),expected);});
test('species and viewed filters compose',()=>{const s={...initialState(),arrived:true,displayAllowed:true,species:'cat',view:'seen',seen:new Set(['a']),cases:[{id:'a',species:'cat'},{id:'b',species:'cat'},{id:'c',species:'dog'}]};assert.deepEqual(visibleCases(s).map(x=>x.id),['a']);});
test('masked and nontelephone contact cannot become dial link',()=>{assert.equal(safePhone('010-****-1234'),null);assert.equal(safePhone('javascript:alert(1)'),null);assert.equal(safePhone('010-0000-0000'),'01000000000');assert.equal(safePhone(undefined),null);});
test('native permission and service failures provide actionable copy without exception details',()=>{
  for(const code of ['monitoring_permissions_required','monitoring_notification_unavailable','monitoring_start_failed:SyntheticException','monitoring_service_failed:SyntheticException','monitoring_service_disconnected']){
    const message=nativeConnectionIssue({message:code});assert.match(message,/다시 연결/);assert.equal(message.includes('SyntheticException'),false);
  }
  for(const message of ['connecting','navigation_connected','vehicle_connected','stopped',undefined])assert.equal(nativeConnectionIssue({message}),null);
});

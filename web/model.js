export const ARRIVAL_POPUP_TTL_MS = 5*60*1000;
export const initialState = () => ({active:true,phase:'ready',arrived:false,arrivalUnlocked:false,arrivalKey:null,arrivalSeq:0,arrivalAt:0,arrivalPopupPending:false,casesReady:false,displayAllowed:false,mode:'demo',region:'성남시',destinationRegionResolved:false,destinationRegionSource:'unknown',species:'all',view:'all',cases:[],meta:null,loading:false,error:null,platform:'browser',nativeStatus:null,seen:new Set(),requestId:0,forceError:false});
export const mayDisplay = state => state.active !== false && state.arrived === true && state.displayAllowed === true;
export const hasResolvedDestinationRegion = state => state.platform!=='pleos'||state.destinationRegionResolved===true;
export const mayQueryCases = (state, visible=true) => visible===true&&mayDisplay(state)&&hasResolvedDestinationRegion(state);
export function receiveArrival(state, key, {handled=false,sequence=0,timestamp,now=Date.now()}={}) {
  if(typeof key!=='string'||!key||key===state.arrivalKey)return state;
  const arrivalAt=typeof timestamp==='number'&&Number.isFinite(timestamp)&&timestamp>0&&timestamp<=now?timestamp:now;
  return {...state,arrived:true,arrivalUnlocked:state.active!==false&&state.displayAllowed===true,arrivalKey:key,arrivalSeq:sequence,
    arrivalAt,arrivalPopupPending:!handled,casesReady:false,destinationRegionResolved:false,destinationRegionSource:'unknown',
    phase:state.active!==false&&state.displayAllowed===true?'parked':'arrived'};
}
export const consumeArrivalPopup = state => ({...state,arrivalPopupPending:false});
export const clearArrival = state => ({...state,arrived:false,arrivalUnlocked:false,arrivalPopupPending:false,casesReady:false,displayAllowed:false,destinationRegionResolved:false,destinationRegionSource:'unknown',phase:'ready'});
// Only a complete native service snapshot may restore an arrival after this reset.
export const resetNativeSession = state => ({...clearArrival(state),arrivalKey:null,arrivalSeq:0,arrivalAt:0});
export function reconcileNativeArrival(state, arrival, {handledSequence=0,now=Date.now()}={}) {
  const sequence=arrival?.arrivalSeq;
  if(!Number.isSafeInteger(sequence)||sequence<=0)return clearArrival(state);
  const handled=arrival.popupHandled===true||sequence<=handledSequence;
  if(state.arrivalKey===`pleos:${sequence}`)return handled?consumeArrivalPopup(state):state;
  return receiveArrival(state,`pleos:${sequence}`,{sequence,handled,timestamp:arrival.timestamp,now});
}
export const arrivalPopupExpired = (state, now=Date.now()) => !Number.isFinite(state.arrivalAt)||state.arrivalAt<=0||now<state.arrivalAt||now-state.arrivalAt>=ARRIVAL_POPUP_TTL_MS;
export const shouldShowArrivalPopup = (state, now=Date.now()) => mayQueryCases(state)&&state.arrivalPopupPending===true&&!arrivalPopupExpired(state,now)&&state.casesReady===true&&!state.loading&&!state.error&&state.cases.length>0;
export function applyActivity(state, active) {
  // A native resume must re-confirm its region from the authoritative arrival snapshot.
  return {...state,active:active===true,displayAllowed:false,casesReady:false,
    ...(state.platform==='pleos'?{destinationRegionResolved:false,destinationRegionSource:'unknown'}:{})};
}
export function applyVehicle(state, vehicle) {
  const moving = vehicle?.gear==='NOT_P' || (typeof vehicle?.speedMetersPerSecond==='number' && Number.isFinite(vehicle.speedMetersPerSecond) && Math.abs(vehicle.speedMetersPerSecond)>0.05);
  // An explicit departure wins even if an inconsistent bridge payload grants display.
  const parkedConfirmed = vehicle?.displayAllowed === true && !moving;
  const displayAllowed = state.active !== false && parkedConfirmed;
  const departed = !displayAllowed && moving && (state.arrivalUnlocked || state.phase==='parked');
  const arrived = departed ? false : state.arrived;
  const arrivalUnlocked = departed ? false : state.arrivalUnlocked || (arrived && parkedConfirmed);
  return {...state,arrived,arrivalUnlocked,displayAllowed,arrivalPopupPending:departed?false:state.arrivalPopupPending,
    ...(departed?{destinationRegionResolved:false,destinationRegionSource:'unknown'}:{}),
    phase: displayAllowed ? (arrived ? 'parked':'ready') : (arrived ? 'arrived':'driving')};
}
export function regionFromAddress(address) {
  if(typeof address !== 'string')return null;
  const parts=address.trim().split(/\s+/);
  const aliases={'서울':'서울특별시','서울시':'서울특별시','부산':'부산광역시','경기':'경기도','인천':'인천광역시','대구':'대구광역시','대전':'대전광역시','광주':'광주광역시','울산':'울산광역시','세종':'세종특별자치시','강원':'강원특별자치도','충북':'충청북도','충남':'충청남도','전북':'전북특별자치도','전남':'전라남도','경북':'경상북도','경남':'경상남도','제주':'제주특별자치도'};
  const province=aliases[parts[0]] || (/(특별시|광역시|특별자치시|도)$/.test(parts[0])?parts[0]:null);
  const municipality=parts.slice(province?1:0).find(x=>/^[가-힣]+(?:시|군|구)$/.test(x)&&!/(특별시|광역시|특별자치시)$/.test(x));
  return province ? [province,municipality].filter(Boolean).join(' ') : municipality || null;
}
function unresolvedDestinationRegion(state) {
  // Keep region only as a settings preference; it must not authorize a query or old photos.
  return {...state,destinationRegionResolved:false,destinationRegionSource:'unknown',cases:[],meta:null,casesReady:false,seen:new Set()};
}
export function applyDestinationAddress(state, address) {
  if(state.platform!=='pleos')return state;
  if(!state.arrived||!Number.isSafeInteger(state.arrivalSeq)||state.arrivalSeq<=0)return unresolvedDestinationRegion(state);
  // A duplicate update cannot overwrite an explicit choice in this foreground arrival session.
  if(state.destinationRegionResolved&&state.destinationRegionSource==='manual')return state;
  const region=regionFromAddress(address);
  if(!region)return unresolvedDestinationRegion(state);
  return {...state,region,destinationRegionResolved:true,destinationRegionSource:'sdk',
    ...(region!==state.region||!state.destinationRegionResolved?{cases:[],meta:null,casesReady:false,seen:new Set()}: {})};
}
export function confirmManualRegion(state, region) {
  if(typeof region!=='string')return unresolvedDestinationRegion(state);
  const selected=region.trim();
  if(region!==''&&selected==='')return unresolvedDestinationRegion(state);
  // An empty string means an explicitly selected nationwide query, never an unknown region.
  const resolved=state.platform!=='pleos'||(state.arrived&&Number.isSafeInteger(state.arrivalSeq)&&state.arrivalSeq>0);
  return {...state,region:selected,destinationRegionResolved:resolved,destinationRegionSource:resolved?'manual':'unknown',
    cases:[],meta:null,casesReady:false,seen:new Set()};
}
export function speciesOf(item) {
  // In particular, preserve "other" when the official catalog cannot classify it.
  if(['dog','cat','other'].includes(item.species))return item.species;
  const breed=typeof item.breed==='string'?item.breed:'';
  if(/^\s*\[고양이\]/.test(breed))return 'cat';
  if(/^\s*\[개\]/.test(breed))return 'dog';
  return 'other';
}
export function replaceCaseResults(state, cases, meta) {
  const ids=new Set(cases.map(item=>item.id));
  return {...state,cases,meta,loading:false,error:null,casesReady:true,
    seen:new Set([...state.seen].filter(id=>ids.has(id)))};
}
export function visibleCases(state) {
  if(!mayDisplay(state)||!hasResolvedDestinationRegion(state))return [];
  return state.cases.filter(item=>(state.species==='all'||speciesOf(item)===state.species)&&(state.view!=='seen'||state.seen.has(item.id)));
}
export function safePhone(value) {
  if(typeof value!=='string'||!/^\+?[\d ()-]{7,25}$/.test(value))return null;
  const number=value.replace(/[^+\d]/g,'');
  return /^\+?\d{7,15}$/.test(number)?number:null;
}
export function nativeConnectionIssue(status) {
  const code=typeof status?.message==='string'?status.message.split(':')[0]:'';
  return {
    monitoring_permissions_required:'차량 정보와 알림 권한을 허용한 뒤 다시 연결해 주세요.',
    monitoring_notification_unavailable:'마중의 알림 설정을 확인한 뒤 다시 연결해 주세요.',
    monitoring_start_failed:'도착 확인을 켜지 못했습니다. 잠시 후 다시 연결해 주세요.',
    monitoring_service_failed:'도착 확인을 준비하지 못했습니다. 다시 연결해 주세요.',
    monitoring_service_disconnected:'차량과의 연결이 끊겼습니다. 다시 연결해 주세요.'
  }[code]||null;
}

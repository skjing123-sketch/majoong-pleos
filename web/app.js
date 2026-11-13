import {initialState,mayDisplay,mayQueryCases,hasResolvedDestinationRegion,applyDestinationAddress,confirmManualRegion,applyVehicle,applyActivity,receiveArrival,consumeArrivalPopup,clearArrival,resetNativeSession,reconcileNativeArrival,arrivalPopupExpired,shouldShowArrivalPopup,speciesOf,visibleCases,replaceCaseResults,safePhone,nativeConnectionIssue} from './model.js';
import {createArrivalBanner,createArrivalThumbnail,createArrivalPresentation,firstArrivalPhoto} from './arrival-banner.js';

const $=id=>document.getElementById(id);
let state=initialState(), requestController=null, toastTimer;
let demoArrivalSeq=0,nativePopupHandledSeq=0;
const offline=new URLSearchParams(location.search).get('offline')==='1';
// Explicit launch choice for native/device testing; normal launches remain demo.
if(new URLSearchParams(location.search).get('dataMode')==='live')state.mode='live';
const regionLabels={'성남시':'경기도 성남시','서울특별시':'서울특별시','경기도':'경기도 전체','부산광역시':'부산광역시','':'전국 · 데이터 확인용'};
const bridge=typeof window.PleosNative==='object' ? window.PleosNative : null;
let uiReportReady=false,uiReportScheduled=false,lastUiReport='';
const arrivalThumbnail=createArrivalThumbnail({element:$('arrivalPreview')});
const arrivalBanner=createArrivalBanner({element:$('arrivalDialog'),
  canShow:()=>mayDisplay(state)&&document.visibilityState!=='hidden'&&state.casesReady&&!state.loading&&!state.error,
  onClose:()=>arrivalThumbnail.clear(),
  onChange:scheduleUiReport});
const arrivalPresentationKey=()=>`${state.arrivalKey}:${state.requestId}`;
const arrivalPresentation=createArrivalPresentation({thumbnail:arrivalThumbnail,banner:arrivalBanner,
  canShow:key=>key===arrivalPresentationKey()&&document.visibilityState!=='hidden'&&shouldShowArrivalPopup(state)&&!document.querySelector('dialog[open]'),
  onShown:consumePopup,onChange:scheduleUiReport});
// Debug observation only: never expose addresses, contacts, URLs, or case text.
function reportUiState(){
  try{
    if(typeof bridge?.reportUiState!=='function')return;
    const grid=$('caseGrid'),dialog=$('detailDialog');
    const rendered=node=>!!node&&!node.hidden&&node.getClientRects().length>0&&getComputedStyle(node).visibility==='visible';
    const cards=rendered(grid)?[...grid.querySelectorAll('.case-card')].filter(rendered).slice(0,50):[];
    const photoLoadedCount=cards.reduce((count,node)=>count+[...node.querySelectorAll('img')].filter(img=>img.complete&&img.naturalWidth>0).length,0);
    const payload={ready:uiReportReady,phase:['ready','driving','arrived','parked'].includes(state.phase)?state.phase:'unknown',caseCount:cards.length,detailOpen:dialog?.open===true,arrivalPopupOpen:arrivalBanner.isOpen(),arrivalPhotoLoaded:arrivalBanner.isOpen()&&state.mode==='live'&&arrivalThumbnail.isPhotoLoaded(),dataMode:['demo','live'].includes(state.mode)?state.mode:'unknown',offline,loading:state.loading===true,hasError:!!state.error,photoLoadedCount:Math.min(50,photoLoadedCount)};
    const serialized=JSON.stringify(payload);if(serialized===lastUiReport)return;
    bridge.reportUiState(serialized);lastUiReport=serialized;
  }catch{/* Diagnostics must never interrupt the application. */}
}
function scheduleUiReport(){
  try{
    if(typeof bridge?.reportUiState!=='function'||uiReportScheduled)return;
    uiReportScheduled=true;requestAnimationFrame(()=>{uiReportScheduled=false;reportUiState();});
  }catch{uiReportScheduled=false;}
}
const text=(tag,content,className)=>{const node=document.createElement(tag);node.textContent=content??'';if(className)node.className=className;return node;};
function icon(name){const svg=document.createElementNS('http://www.w3.org/2000/svg','svg'),use=document.createElementNS('http://www.w3.org/2000/svg','use');svg.classList.add('icon');svg.setAttribute('aria-hidden','true');use.setAttribute('href',`#icon-${name}`);svg.append(use);return svg;}
function notify(message){clearTimeout(toastTimer);$('toast').textContent=message;$('toast').hidden=false;toastTimer=setTimeout(()=>$('toast').hidden=true,3800);}
function consumePopup(){
  state=consumeArrivalPopup(state);
  if(state.platform==='pleos'&&Number.isSafeInteger(state.arrivalSeq)&&state.arrivalSeq>0){
    nativePopupHandledSeq=Math.max(nativePopupHandledSeq,state.arrivalSeq);
    try{bridge?.markArrivalPopupHandled?.(state.arrivalSeq);}catch{/* Local deduplication still applies. */}
  }
}
function closeArrivalPopup(suppressPending=false){
  if(arrivalBanner.isOpen()||suppressPending)consumePopup();
  arrivalPresentation.clear();scheduleUiReport();
}
function expirePendingPopup(){if(state.arrivalPopupPending&&arrivalPopupExpired(state))consumePopup();}
function restorePendingArrival(){
  try{
    const snapshot=JSON.parse(bridge.getState());
    if(Number.isSafeInteger(snapshot.arrivalPopupHandledSeq))nativePopupHandledSeq=Math.max(nativePopupHandledSeq,snapshot.arrivalPopupHandledSeq);
    const arrival=snapshot.status?.monitoring===false?null:snapshot.arrival;
    state=reconcileNativeArrival(state,arrival,{handledSequence:nativePopupHandledSeq});
    if(state.arrived)state=applyDestinationAddress(state,arrival?.address);
  }catch{state=clearArrival(state);}
  expirePendingPopup();
}
function syncArrivalPopup(){
  expirePendingPopup();
  if(!mayDisplay(state)||document.visibilityState==='hidden'||state.loading||state.error||!state.casesReady){closeArrivalPopup();return;}
  if(arrivalBanner.isOpen()||!shouldShowArrivalPopup(state)||document.querySelector('dialog[open]'))return;
  $('arrivalDialogTitle').textContent=`${state.mode==='demo'?'가상 공고':'이 지역 공고'} ${state.cases.length}건`;
  arrivalPresentation.show({key:arrivalPresentationKey(),source:firstArrivalPhoto(state.cases.map(photoFor)),demo:state.mode==='demo'});
  scheduleUiReport();
}
function cancelRequest(){requestController?.abort();requestController=null;state.requestId++;state.loading=false;}
function discardVisibleData(){closeArrivalPopup();cancelRequest();state.cases=[];state.meta=null;state.error=null;state.casesReady=false;$('caseGrid').replaceChildren();$('detailDialog').close();$('detailContent').replaceChildren();}
function setSimulation(action){
  if(state.platform==='pleos')return;
  if(action==='drive'){
    closeArrivalPopup(true);discardVisibleData();state={...clearArrival(state),phase:'driving'};$('testDock').open=true;
    if(bridge?.startMonitoring)try{bridge.startMonitoring();}catch{}
  }else if(action==='arrive'){
    if(state.phase==='ready'){notify('먼저 이동 시작을 눌러 주세요.');return;}
    if(state.arrived)return;
    state=receiveArrival({...state,displayAllowed:false},`demo:${++demoArrivalSeq}`);
  }else if(action==='park'){
    if(!state.arrived){notify('목적지 도착을 먼저 눌러 주세요.');return;}
    state.displayAllowed=true;state.phase='parked';
  }else if(action==='reset'){
    discardVisibleData();state={...initialState(),mode:state.mode,region:state.region,platform:state.platform,nativeStatus:state.nativeStatus,requestId:state.requestId};
  }
  render();if(mayDisplay(state))loadCases();
}
function photoFor(item){
  if(state.mode==='demo')return `/assets/${speciesOf(item)==='cat'?'cat':'dog'}.svg`;
  return typeof item.photoUrl==='string'&&/^\/api\/photo\?/.test(item.photoUrl)?item.photoUrl:null;
}
function imageElement(item,loading='lazy'){
  const url=photoFor(item);if(!url)return text('div','사진이 제공되지 않았어요','case-photo-placeholder');
  const img=new Image();img.src=url;img.alt=state.mode==='demo'?'가상 공고의 예시 일러스트':`${item.breed||'반려동물'} 공고 사진`;img.loading=loading;img.decoding='async';img.referrerPolicy='no-referrer';
  img.addEventListener('error',()=>img.replaceWith(text('div','사진을 불러오지 못했어요','case-photo-placeholder')),{once:true});img.addEventListener('load',scheduleUiReport,{once:true});img.addEventListener('error',scheduleUiReport,{once:true});return img;
}
function card(item){
  const button=document.createElement('button');button.className='case-card';button.setAttribute('aria-label',`${item.demoName?item.demoName+' · ':''}${item.breed||'반려동물'} 공고 상세`);
  const photo=text('div','','case-image');photo.append(imageElement(item));if(state.mode==='demo')photo.append(text('span','가상 공고','photo-label'));
  if(state.seen.has(item.id)){const seen=text('span','','viewed-label');seen.append(icon('check'),text('span','본 공고'));photo.append(seen);}
  const body=text('div','','case-body'),heading=text('div','','case-title-row');
  heading.append(text('h3',`${item.demoName?item.demoName+' · ':''}${item.breed||'품종 미상'}`),text('span',item.sex||'성별 미상','case-meta'));
  const area=text('div','','case-location');area.append(icon('pin'),text('span',item.area||'지역 정보 없음'));
  body.append(heading,area,text('p',item.features||'특징 정보가 제공되지 않았습니다.','features'));
  const bottom=text('div','','case-bottom'),more=text('span','상세 보기');more.append(icon('arrow'));bottom.append(text('span',`접수 ${item.reportedDate||item.lostDate||'날짜 미상'}`),more);body.append(bottom);button.append(photo,body);button.addEventListener('click',()=>openDetail(item));return button;
}
function renderHeader(){
  const regionNeeded=state.platform==='pleos'&&state.arrived&&!hasResolvedDestinationRegion(state);
  $('destinationName').textContent=regionNeeded?'도착 지역 선택 필요':regionLabels[state.region]||state.region||'전국';
  $('regionSummary').textContent=regionNeeded?'도착 주소를 확인하지 못했습니다. 지역을 직접 선택해 주세요.':state.mode==='demo'?'가상 공고로 살펴보는 도착지 안내':'이 지역에서 접수된 분실 반려동물 공고';
  $('dataBadge').textContent=state.mode==='live'?'공공데이터':offline?'오프라인 체험':'체험';$('dataBadge').classList.toggle('live',state.mode==='live');
  $('seenCount').textContent=state.seen.size;$('homeNav').classList.toggle('active',state.view==='all');$('seenNav').classList.toggle('active',state.view==='seen');
  const phases={ready:['—','도착 대기','주차 후 이용할 수 있어요'],driving:['D','이동 중','공고는 주차 후 표시됩니다'],arrived:['—','도착 · 주차 대기','P단 주차를 확인하고 있어요'],parked:['P','주차 확인','사진과 특징을 확인해 주세요']};
  const p=phases[state.phase]||phases.ready;$('gearLabel').textContent=p[0];$('vehicleLabel').textContent=p[1];$('signalLabel').textContent=p[2];
  if(state.platform==='pleos'){
    const connected=state.nativeStatus?.connected===true;
    $('connectionLabel').textContent=nativeConnectionIssue(state.nativeStatus)?'연결 확인 필요':connected?'차량 연결됨':'차량 연결 대기';$('connectionDot').style.background=connected?'#668a83':'#b89a73';
    if(state.phase==='ready'){$('vehicleLabel').textContent='도착 대기';$('signalLabel').textContent='내비게이션 도착 신호 대기';}
    if(!state.displayAllowed){$('gearLabel').textContent='—';$('signalLabel').textContent='주차 상태 확인 중';}
  }
  else $('connectionLabel').textContent='차량 상태 시뮬레이션';
  $('testState').textContent=p[1];
  for(const [id,phase] of [['driveBtn','driving'],['arriveBtn','arrived'],['parkBtn','parked']])$(id).classList.toggle('active',state.phase===phase);
  $('refreshBtn').disabled=!mayQueryCases(state,document.visibilityState!=='hidden')||state.loading;
  document.querySelectorAll('[data-species]').forEach(button=>button.classList.toggle('selected',button.dataset.species===state.species));
}
function showPanel(symbol,title,description,actionLabel,action,error=false){
  const panel=$('statePanel'),symbolNode=text('div','','state-symbol'+(state.loading?' loading-mark':''));symbolNode.append(icon(symbol));panel.hidden=false;panel.classList.toggle('error',error);panel.replaceChildren(symbolNode,text('h3',title),text('p',description));
  if(action){const b=text('button',actionLabel,'primary-button');b.addEventListener('click',action);panel.append(b);}
}
function render(){
  expirePendingPopup();
  scheduleUiReport();
  if(!mayQueryCases(state,document.visibilityState!=='hidden')||state.loading||state.error||!state.casesReady)closeArrivalPopup();
  renderHeader();$('caseGrid').hidden=true;$('caseGrid').replaceChildren();
  $('listTitle').firstChild.textContent=state.view==='seen'?'본 공고 ':'분실 공고 ';
  $('resultCount').textContent='—';$('coverage').textContent='같은 행정구역의 공고입니다. 거리와 반경 정보는 제공되지 않습니다.';$('updatedAt').textContent='최근 30일 접수';
  if(!mayDisplay(state)){
    const issue=state.platform==='pleos'?nativeConnectionIssue(state.nativeStatus):null;
    if(issue)showPanel('info','도착 확인을 시작하지 못했어요',issue,'다시 연결',startNative,true);
    else if(state.phase==='ready')showPanel('pin','도착한 곳에서 확인해 주세요','주차 후, 이 지역의 분실 반려동물 사진과 특징을 볼 수 있어요.',state.platform==='pleos'?'차량 연결':'시연 시작',()=>state.platform==='pleos'?startNative():setSimulation('drive'));
    else if(state.phase==='arrived')showPanel('parking','도착했어요. 주차 후 확인해 주세요.','P단 주차가 확인되면 이 지역의 공고를 불러옵니다.',state.platform==='pleos'?null:'P단 주차',state.platform==='pleos'?null:()=>setSimulation('park'));
    else showPanel('car','공고는 주차 후에 보여드릴게요','이동 중에는 사진과 연락처를 표시하지 않습니다.',state.platform==='pleos'?null:'목적지 도착',state.platform==='pleos'?null:()=>setSimulation('arrive'));
    return;
  }
  if(!hasResolvedDestinationRegion(state)){
    showPanel('pin','도착 지역을 선택해 주세요','도착 주소를 확인하지 못했습니다. 공고를 볼 지역을 직접 선택해 주세요.','지역 선택',openSettings);
    return;
  }
  if(state.loading){showPanel('refresh','공고를 불러오고 있어요','잠시만 기다려 주세요.');return;}
  if(state.error){showPanel('info','공고를 불러오지 못했어요',state.error,'다시 시도',()=>{state.forceError=false;loadCases();},true);return;}
  const items=visibleCases(state);$('resultCount').textContent=items.length;
  if(state.meta){$('coverage').textContent=state.meta.coverageNote||$('coverage').textContent;const at=new Date(state.meta.fetchedAt);$('updatedAt').textContent=Number.isNaN(at.getTime())?'방금 확인':`${at.toLocaleTimeString('ko-KR',{hour:'2-digit',minute:'2-digit',hour12:false})} 확인 · 최근 30일 접수`;}
  if(!items.length){showPanel('search',state.view==='seen'?'아직 본 공고가 없어요':'조회된 공고가 없어요',state.view==='seen'?'공고를 열어 보면 이곳에서 다시 볼 수 있어요.':'선택한 지역과 조회 범위 안에 일치하는 결과가 없습니다.','지역 변경',openSettings);syncArrivalPopup();return;}
  $('statePanel').hidden=true;const frag=document.createDocumentFragment();for(const item of items)frag.append(card(item));$('caseGrid').append(frag);$('caseGrid').hidden=false;syncArrivalPopup();
}
async function loadCases(){
  if(!mayQueryCases(state,document.visibilityState!=='hidden'))return;cancelRequest();const requestId=state.requestId,mode=state.mode,region=state.region;
  state.loading=true;state.error=null;state.cases=[];state.casesReady=false;render();requestController=new AbortController();
  try{
    if(state.forceError)throw new Error('통신 오류를 재현했습니다. 다시 시도를 누르면 정상 조회로 돌아갑니다.');
    let data;
    if(offline&&mode==='demo'){
      const r=await fetch('/offline-demo.json',{signal:requestController.signal});if(!r.ok)throw new Error('오프라인 예시 데이터를 읽을 수 없습니다.');data=await r.json();data.cases=data.cases.filter(x=>!region||x.area.includes(region));data.meta.fetchedAt=new Date().toISOString();
    }else{
      if(offline)throw new Error('오프라인 체험에서는 실제 API를 사용할 수 없습니다. 로컬 서버를 실행한 뒤 앱을 다시 열어 주세요.');
      const params=new URLSearchParams({mode,region});const r=await fetch(`/api/cases?${params}`,{signal:requestController.signal,cache:'no-store'});data=await r.json();if(!r.ok)throw new Error(data.error?.message||'서버 응답을 확인할 수 없습니다.');
    }
    if(requestId!==state.requestId||!mayQueryCases(state,document.visibilityState!=='hidden'))return;
    if(data.dataMode!==mode||!Array.isArray(data.cases))throw new Error('데이터 모드가 일치하지 않아 공고를 표시하지 않았습니다.');
    state=replaceCaseResults(state,data.cases,data.meta);render();
  }catch(error){if(error.name==='AbortError'||requestId!==state.requestId)return;state.loading=false;state.error=error.message||'연결을 확인해 주세요.';render();}
}
function openDetail(item){
  if(!mayDisplay(state)||!hasResolvedDestinationRegion(state))return;closeArrivalPopup();state.seen.add(item.id);renderHeader();const layout=text('div','','detail-layout'),photo=text('div','','detail-photo');photo.append(imageElement(item,'eager'));
  const copy=text('div','','detail-copy');copy.append(text('span',state.mode==='demo'?'가상 공고 · 완료 상태 미제공':'발견 완료 상태 미제공','unknown-badge'),text('h2',`${item.demoName?item.demoName+' · ':''}${item.breed||'반려동물'}`),text('p',item.features||'특징 정보가 없습니다.','description'));
  const facts=document.createElement('dl');facts.className='detail-facts';for(const [label,value] of [['접수일',item.reportedDate||item.lostDate],['분실 지역',item.area],['성별·나이',[item.sex,item.age].filter(Boolean).join(' · ')],['털색',item.color]]){facts.append(text('dt',label),text('dd',value||'정보 없음'));}copy.append(facts);
  const contact=text('div','','contact-area'),phone=safePhone(item.contact?.number);
  if(state.mode==='demo'){contact.append(text('p','가상 공고입니다. 실제 동물·사진·연락처가 아니며 전화가 연결되지 않습니다.'));const b=text('button','제보 연결 체험','primary-button');b.addEventListener('click',()=>{b.textContent='체험 완료 · 실제 발신하지 않았습니다.';contact.append(text('p','실제 공고에서는 신고자 연락처를 확인한 뒤 전화 앱을 열 수 있습니다.'));b.disabled=true;});contact.append(b);}
  else if(phone&&item.contact?.available&&!item.contact?.masked){const b=text('button','신고자 연락처 보기','primary-button');b.addEventListener('click',()=>{if(!mayDisplay(state))return;contact.replaceChildren(text('strong',item.contact.number,'phone-value'));const a=text('a','전화 앱으로 연결','primary-button');a.href=`tel:${phone}`;a.addEventListener('click',event=>{if(!mayDisplay(state))event.preventDefault();});contact.append(a,text('p','신고자가 공개한 연락처입니다. 전화 앱만 열며 자동 발신하지 않습니다.'));});contact.append(b,text('p','닮은 동물을 발견했다면 위치와 특징을 신고자에게 알려주세요.'));}
  else contact.append(text('p',item.contact?.masked?'연락처가 가려져 있어 연결할 수 없습니다. 공식 게시판을 확인해 주세요.':'연결할 수 있는 연락처가 제공되지 않았습니다.'));
  const source=text('a','공식 공고 게시판','subtle-button');source.append(icon('external'));source.href='https://www.animal.go.kr/front/awtis/loss/lossList.do?menuNo=1000100000';source.target='_blank';source.rel='noopener noreferrer';source.style.marginTop='14px';contact.append(source);copy.append(contact);
  if(state.platform!=='pleos'){const drive=text('button','차량 시뮬레이터 · 주행 재개','subtle-button');drive.style.marginTop='15px';drive.addEventListener('click',()=>setSimulation('drive'));copy.append(drive);}
  layout.append(photo,copy);$('detailContent').replaceChildren(layout);$('detailDialog').showModal();scheduleUiReport();
}
const REGION_UNSELECTED='__choose_destination_region__';
function prepareRegionSelect(){
  const select=$('regionSelect'),placeholder=[...select.options].find(o=>o.value===REGION_UNSELECTED);
  if(state.platform==='pleos'&&state.arrived&&!hasResolvedDestinationRegion(state)){
    if(!placeholder){const option=new Option('지역을 선택해 주세요',REGION_UNSELECTED);option.disabled=true;select.add(option,0);}
    select.value=REGION_UNSELECTED;
  }else{
    placeholder?.remove();
    if(![...select.options].some(o=>o.value===state.region)){const o=new Option(state.region,state.region);select.add(o);}
    select.value=state.region;
  }
}
function openSettings(){
  closeArrivalPopup();prepareRegionSelect();$('modeSelect').value=state.mode;$('errorBtn').hidden=state.platform==='pleos';$('settingsDialog').showModal();checkHealth();
}
async function checkHealth(){try{if(offline)throw new Error();const r=await fetch('/api/health',{cache:'no-store'}),data=await r.json();$('apiHealth').textContent=data.keyConfigured?'인증키 연결됨 · 실제 공공데이터를 조회할 수 있습니다.':'인증키 미설정 · 가상 공고는 바로 체험할 수 있습니다. 실제 조회는 실행 안내의 인증키 연결이 필요합니다.';}catch{$('apiHealth').textContent=offline?'오프라인 체험 · 가상 공고만 사용 가능합니다.':'로컬 API 서버 연결을 확인해 주세요.';}}
function startNative(){try{bridge?.startMonitoring();notify('내비게이션에서 목적지를 설정해 주세요.');}catch{notify('차량 연결을 다시 확인해 주세요.');}}
function nativeEvent(event){
  const data=event.detail;if(!data||typeof data!=='object')return;
  if(data.type==='session-reset'&&state.platform==='pleos'){
    discardVisibleData();
    if(Number.isSafeInteger(data.arrivalPopupHandledSeq)&&data.arrivalPopupHandledSeq>=0)nativePopupHandledSeq=data.arrivalPopupHandledSeq;
    state=resetNativeSession(state);render();
  }else if(data.type==='arrival'){
    if(state.platform!=='pleos')return;
    const sequence=data.arrivalSeq;if(!Number.isSafeInteger(sequence)||sequence<=0||state.arrivalKey===`pleos:${sequence}`)return;
    discardVisibleData();state=receiveArrival(state,`pleos:${sequence}`,{sequence,timestamp:data.timestamp,handled:data.popupHandled===true||sequence<=nativePopupHandledSeq});
    if(!state.arrivalPopupPending)consumePopup();
    state=applyDestinationAddress(state,data.address);
    if($('settingsDialog').open&&!hasResolvedDestinationRegion(state))prepareRegionSelect();
    render();if(mayDisplay(state))loadCases();
  }else if(data.type==='vehicle'){
    if(state.platform!=='pleos')return;
    if(state.active===false){state=applyVehicle(state,data);closeArrivalPopup();return;}
    const previous=mayDisplay(state),oldAllowed=state.displayAllowed,oldArrived=state.arrived;state=applyVehicle(state,data);
    if(oldAllowed===state.displayAllowed&&oldArrived===state.arrived)return;
    if(!mayDisplay(state))discardVisibleData();render();if(!previous&&mayDisplay(state))loadCases();
  }else if(data.type==='status'){
    state.nativeStatus=data;
    if(state.platform==='pleos'&&data.monitoring===false){closeArrivalPopup(true);discardVisibleData();state=clearArrival(state);render();}
    else renderHeader();
  }else if(data.type==='lifecycle'&&state.platform==='pleos'){
    if(data.active===false||['paused','stopped','destroyed','background'].includes(data.state||data.phase)){
      closeArrivalPopup();state=applyActivity(state,false);state=applyVehicle(state,{displayAllowed:false});discardVisibleData();render();
    }else if((data.state||data.phase)==='resumed'){
      state=applyActivity(state,true);discardVisibleData();restorePendingArrival();render();
    }
  }
}
window.addEventListener('pleos:event',nativeEvent);
if(bridge){try{const native=JSON.parse(bridge.getState());state.platform=native.platform||'demo';state.nativeStatus=native.status;if(state.platform==='pleos'){nativePopupHandledSeq=Number.isSafeInteger(native.arrivalPopupHandledSeq)?native.arrivalPopupHandledSeq:0;document.body.classList.add('native-app');state=applyVehicle(state,native.vehicle);}}catch{state.platform='pleos';document.body.classList.add('native-app');state.displayAllowed=false;}}
for(const [id,action] of [['driveBtn','drive'],['arriveBtn','arrive'],['parkBtn','park'],['resetBtn','reset']])$(id).addEventListener('click',()=>setSimulation(action));
$('settingsBtn').addEventListener('click',openSettings);$('regionBtn').addEventListener('click',openSettings);$('aboutBtn').addEventListener('click',()=>{$('settingsDialog').close();closeArrivalPopup();$('aboutDialog').showModal();});
document.querySelectorAll('.close-dialog').forEach(b=>b.addEventListener('click',()=>b.closest('dialog').close()));
document.querySelectorAll('dialog').forEach(d=>d.addEventListener('click',event=>{if(event.target===d){const r=d.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)d.close();}}));
$('arrivalLaterBtn').addEventListener('click',()=>closeArrivalPopup(true));
$('arrivalViewBtn').addEventListener('click',()=>{closeArrivalPopup(true);if(!mayDisplay(state))return;state.view='all';state.species='all';render();$('listTitle').scrollIntoView({block:'start',behavior:'instant'});$('caseGrid').querySelector('.case-card')?.focus({preventScroll:true});});
const bannerElement=$('arrivalDialog');
const resumeBanner=()=>{if(!bannerElement.contains(document.activeElement)&&!bannerElement.matches(':hover'))arrivalBanner.resume();};
bannerElement.addEventListener('pointerenter',()=>arrivalBanner.pause());
bannerElement.addEventListener('pointerleave',resumeBanner);
bannerElement.addEventListener('focusin',()=>arrivalBanner.pause());
bannerElement.addEventListener('focusout',()=>queueMicrotask(resumeBanner));
document.querySelectorAll('dialog').forEach(d=>d.addEventListener('close',()=>{syncArrivalPopup();scheduleUiReport();}));
document.addEventListener('visibilitychange',()=>{
  if(document.visibilityState==='hidden'){
    closeArrivalPopup();if(state.platform==='pleos')state=applyActivity(state,false);discardVisibleData();
  }else{
    expirePendingPopup();if(mayQueryCases(state,true)&&!state.loading)loadCases();
  }
});
$('detailDialog').addEventListener('close',()=>{$('detailContent').replaceChildren();if(mayDisplay(state))render();scheduleUiReport();});
$('settingsForm').addEventListener('submit',event=>{event.preventDefault();const region=$('regionSelect').value;if(region===REGION_UNSELECTED){notify('공고를 볼 지역을 먼저 선택해 주세요.');$('regionSelect').focus();return;}discardVisibleData();state=confirmManualRegion(state,region);state.mode=$('modeSelect').value;state.species='all';state.view='all';state.seen.clear();state.forceError=false;$('settingsDialog').close();render();if(mayDisplay(state))loadCases();});
$('errorBtn').addEventListener('click',()=>{state.forceError=true;$('settingsDialog').close();if(mayDisplay(state))loadCases();else notify('P단 주차 후 오류 화면을 확인할 수 있습니다.');});
$('refreshBtn').addEventListener('click',()=>{state.forceError=false;loadCases();});
$('homeNav').addEventListener('click',()=>{state.view='all';render();});$('seenNav').addEventListener('click',()=>{state.view='seen';render();});
document.querySelectorAll('[data-species]').forEach(button=>button.addEventListener('click',()=>{state.species=button.dataset.species;render();}));
function tick(){$('clock').textContent=new Date().toLocaleTimeString('ko-KR',{hour:'2-digit',minute:'2-digit',hour12:false});expirePendingPopup();}tick();setInterval(tick,30000);render();checkHealth();uiReportReady=true;reportUiState();scheduleUiReport();

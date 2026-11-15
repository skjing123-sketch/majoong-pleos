import {validateSnapshot} from './snapshot.mjs';
const el = id => document.getElementById(id);
const node = (tag, text, className) => {const value=document.createElement(tag);if(text)value.textContent=text;if(className)value.className=className;return value;};
async function readSnapshot() {
  const controller = new AbortController(), timer = setTimeout(()=>controller.abort(),8000);
  try {
    const response=await fetch('./sample.json',{cache:'no-store',credentials:'omit',redirect:'error',signal:controller.signal});
    if(!response.ok)throw new Error('자료를 가져오지 못했습니다.');
    const text=await response.text();if(text.length>65536)throw new Error('자료 크기를 확인해 주세요.');
    return validateSnapshot(JSON.parse(text));
  } finally {clearTimeout(timer);}
}
try {
  const data=await readSnapshot();
  if(data.state==='unavailable'){
    el('statusTitle').textContent='실제 조회 자료가 아직 없습니다';
    el('statusText').textContent='API 인증키 연결과 실제 공고 확인 후 자료를 추가합니다. 확인되지 않은 공고나 사진은 표시하지 않습니다.';
  }else{
    el('statusTitle').textContent=`${data.region} · 실제 API 조회 기록`;
    el('statusText').textContent=`실제 조회 결과 중 선택한 공고 ${data.cases.length}건입니다. 지역 전체 공고 목록, 지금의 실시간 결과, 현재 미발견 상태를 뜻하지 않습니다.`;
    el('capturedAt').hidden=false;el('capturedAt').textContent=`조회 시각 ${new Date(data.capturedAt).toLocaleString('ko-KR',{timeZone:'Asia/Seoul',hour12:false})} KST`;
    if(data.mockup){
      el('mockupSection').hidden=false;el('mockupImage').src=data.mockup.image;
      let caption='공식 Pleos 홍보 화면을 바탕으로 주차 상황을 구성한 제안 UI입니다. 공고 자료는 실제 API 조회 응답이며, 실제 SDK 도착 이벤트가 이 화면을 자동 표시한 결과는 아닙니다. 전체 내비 화면 위 사진 팝업은 아직 구현되지 않았습니다.';
      if(data.mockup.navigationRegion){
        const caseRegions=[...new Set(data.cases.map(item=>item.region))];
        if(caseRegions.some(region=>region!==data.mockup.navigationRegion))caption+=` 배경 지도는 ${data.mockup.navigationRegion}, 적용 공고는 ${caseRegions.join('·')}입니다. 같은 구나 근거리 공고로 일치시킨 결과가 아니며, ${data.region} 조회 범위에서 선택한 사례입니다.`;
      }
      el('mockupCaption').textContent=caption;
    }
    el('caseSection').hidden=data.cases.length===0;
    for(const item of data.cases){
      const article=node('article',null,'case'),image=node('img');image.src=item.photo;image.alt=`공식 공고에 첨부된 ${item.breed||'반려동물'} 사진`;image.loading='lazy';image.referrerPolicy='no-referrer';
      image.addEventListener('error',()=>{image.remove();article.prepend(node('p','사진 파일을 확인하지 못했습니다.','error'));},{once:true});
      const copy=node('div');copy.append(node('h3',item.breed||'반려동물'),node('p',item.region),node('p',item.reportedDate?`실종일 ${item.reportedDate}`:'실종 날짜 미제공'),node('p',item.features||'특징 정보 없음'));article.append(image,copy);el('cases').append(article);
    }
  }
}catch{
  el('statusTitle').textContent='자료를 확인하지 못했습니다';
  el('statusText').textContent='review/README.md의 로컬 서버 실행 방법과 공개용 자료 형식을 확인해 주세요. 공고와 사진은 표시하지 않았습니다.';
}

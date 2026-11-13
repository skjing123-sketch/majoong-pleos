export const ARRIVAL_BANNER_DURATION_MS = 6000;
export const ARRIVAL_PHOTO_TIMEOUT_MS = 15000;
export const ARRIVAL_BRAND_TIMEOUT_MS = 3000;
export const ARRIVAL_BRAND_SOURCE = '/assets/brand/majoong-mark.png';

// Only opaque same-origin image proxies or the explicit fictional illustrations.
const localPhoto = value => typeof value==='string'&&/^(?:\/api\/photo\?id=[A-Za-z0-9_-]{1,128}|\/assets\/(?:cat|dog)\.svg)$/.test(value);
export const firstArrivalPhoto = sources => sources.find(localPhoto)||null;

/** Holds one representative image, with no case text or contact information. */
export function createArrivalThumbnail({element,createImage=()=>new Image(),schedule=setTimeout,cancel=clearTimeout}) {
  let revision=0,currentImage=null,timer=null,photoLoaded=false;
  const cancelTimer=()=>{if(timer!==null)cancel(timer);timer=null;};
  const clear=()=>{
    revision++;cancelTimer();photoLoaded=false;
    currentImage?.removeAttribute('src');currentImage=null;element.replaceChildren();
  };
  const show=(source,{demo=false,onReady=()=>{}}={})=>{
    clear();const expected=revision;
    const render=(src,fallback)=>{
      if(expected!==revision)return;
      cancelTimer();currentImage?.removeAttribute('src');
      const img=createImage();
      let decoding=false,finished=false;
      const current=()=>expected===revision&&currentImage===img&&!finished;
      const finish=hasImage=>{
        if(!current())return;
        finished=true;cancelTimer();photoLoaded=hasImage&&!fallback&&!demo&&src.startsWith('/api/photo?id=');
        onReady({photoLoaded,fallback,hasImage});
      };
      const fail=()=>{
        if(!current())return;
        if(fallback){
          img.removeAttribute('src');element.replaceChildren();element.textContent='사진 없음';
          finish(false);
        }else{finished=true;render(ARRIVAL_BRAND_SOURCE,true);}
      };
      const loaded=()=>{
        if(!current()||decoding)return;
        if(!(img.naturalWidth>0)){fail();return;}
        decoding=true;
        if(typeof img.decode==='function')Promise.resolve().then(()=>img.decode()).then(()=>finish(true),fail);
        else finish(true);
      };
      img.alt=fallback?'마중 · 공고 사진 없음':demo?'가상 공고 중 한 건의 예시 일러스트':'조회된 공고 중 한 건의 사진';
      img.loading='eager';img.decoding='async';img.referrerPolicy='no-referrer';
      img.addEventListener('error',fail,{once:true});img.addEventListener('load',loaded,{once:true});
      currentImage=img;element.replaceChildren(img);
      timer=schedule(fail,fallback?ARRIVAL_BRAND_TIMEOUT_MS:ARRIVAL_PHOTO_TIMEOUT_MS);
      img.src=src;
      if(img.complete)loaded();
    };
    render(localPhoto(source)?source:ARRIVAL_BRAND_SOURCE,!localPhoto(source));
  };
  return {show,clear,isPhotoLoaded:()=>photoLoaded};
}

/** Preparation is cancellable and never consumes an arrival before its image is ready. */
export function createArrivalPresentation({thumbnail,banner,canShow,onShown=()=>{},onChange=()=>{}}) {
  let pending=null;
  const clear=()=>{pending=null;banner.hide();thumbnail.clear();};
  const show=({key,source,demo=false})=>{
    if(banner.isOpen()||pending?.key===key||!canShow(key))return false;
    if(pending)clear();
    const attempt={key};pending=attempt;
    thumbnail.show(source,{demo,onReady:()=>{
      if(pending!==attempt)return;
      pending=null;
      // Re-check parking, visibility, TTL, arrival and query identity after asynchronous decode.
      if(canShow(key)&&banner.show())onShown();else thumbnail.clear();
      onChange();
    }});
    return true;
  };
  return {show,clear};
}

/** An in-page notice: it never takes focus, opens a modal, or changes arrival permission. */
export function createArrivalBanner({element,canShow,onClose=()=>{},onChange=()=>{},schedule=setTimeout,cancel=clearTimeout}) {
  let timer=null;
  const pause=()=>{if(timer!==null)cancel(timer);timer=null;};
  const isOpen=()=>element.hidden===false;
  const hide=()=>{
    pause();
    if(!isOpen())return;
    element.hidden=true;onClose();onChange();
  };
  const resume=()=>{
    pause();
    if(isOpen())timer=schedule(hide,ARRIVAL_BANNER_DURATION_MS);
  };
  const show=()=>{
    if(!canShow())return false;
    if(isOpen())return false;
    element.hidden=false;resume();onChange();return true;
  };
  return {show,hide,pause,resume,isOpen};
}

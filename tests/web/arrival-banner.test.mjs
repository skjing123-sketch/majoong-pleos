import test from 'node:test';
import assert from 'node:assert/strict';
import {createArrivalBanner,ARRIVAL_BANNER_DURATION_MS,createArrivalThumbnail,createArrivalPresentation,firstArrivalPhoto,ARRIVAL_BRAND_SOURCE,ARRIVAL_PHOTO_TIMEOUT_MS,ARRIVAL_BRAND_TIMEOUT_MS} from '../../web/arrival-banner-contest.js';
import {initialState,receiveArrival,applyVehicle,applyActivity,consumeArrivalPopup,shouldShowArrivalPopup} from '../../web/model-contest.js';

function setup(){
  let allowed=true,closed=0,changes=0,next=0;
  const timers=new Map(),element={hidden:true,focus(){assert.fail('Arrival notice must not take focus');},showModal(){assert.fail('Arrival notice must not block the app');}};
  const banner=createArrivalBanner({element,canShow:()=>allowed,onClose:()=>closed++,onChange:()=>changes++,schedule(fn,delay){const id=++next;timers.set(id,{fn,delay});return id;},cancel(id){timers.delete(id);}});
  return {banner,element,timers,allow:value=>allowed=value,closed:()=>closed,changes:()=>changes,fire(){const [id,timer]=[...timers][0];timers.delete(id);timer.fn();}};
}

test('arrival banner is nonmodal and dismisses automatically after six seconds',()=>{
  const h=setup();assert.equal(h.banner.show(),true);assert.equal(h.banner.isOpen(),true);
  assert.equal(h.element.hidden,false);assert.equal([...h.timers.values()][0].delay,6000);assert.equal(ARRIVAL_BANNER_DURATION_MS,6000);
  h.fire();assert.equal(h.banner.isOpen(),false);assert.equal(h.closed(),1);assert.equal(h.changes(),2);
});
test('permission denied leaves the banner hidden without an automatic timer',()=>{
  const h=setup();h.allow(false);assert.equal(h.banner.show(),false);assert.equal(h.element.hidden,true);assert.equal(h.timers.size,0);assert.equal(h.changes(),0);
});
test('duplicate render does not extend the existing arrival banner',()=>{
  const h=setup();h.banner.show();const timer=[...h.timers.keys()][0];assert.equal(h.banner.show(),false);assert.deepEqual([...h.timers.keys()],[timer]);assert.equal(h.changes(),1);
});
test('departure or background hides immediately and cancels the timer',()=>{
  const h=setup();h.banner.show();h.allow(false);h.banner.hide();assert.equal(h.timers.size,0);assert.equal(h.banner.isOpen(),false);assert.equal(h.banner.show(),false);assert.equal(h.closed(),1);
});
test('pointer or keyboard interaction can pause dismissal without stealing focus',()=>{
  const h=setup();h.banner.show();h.banner.pause();assert.equal(h.banner.isOpen(),true);assert.equal(h.timers.size,0);
  h.banner.resume();assert.equal(h.timers.size,1);assert.equal([...h.timers.values()][0].delay,6000);h.fire();assert.equal(h.banner.isOpen(),false);
});
test('late pointerleave or focusout after close cannot revive an old notice',()=>{
  const h=setup();h.banner.show();h.banner.hide();h.banner.resume();h.banner.hide();assert.equal(h.timers.size,0);assert.equal(h.banner.isOpen(),false);assert.equal(h.closed(),1);
});

function fakeClock(){
  let now=1_000_000,next=0;const timers=new Map();
  return {timers,now:()=>now,schedule(fn,delay){const id=++next;timers.set(id,{fn,at:now+delay,delay});return id;},cancel(id){timers.delete(id);},advance(ms){
    const end=now+ms;
    for(;;){const due=[...timers].filter(([,t])=>t.at<=end).sort((a,b)=>a[1].at-b[1].at)[0];if(!due)break;now=due[1].at;timers.delete(due[0]);due[1].fn();}
    now=end;
  }};
}
function thumbnailSetup(clock=fakeClock()){
  const images=[],element={children:[],replaceChildren(...children){this.children=children;}};
  const thumbnail=createArrivalThumbnail({element,schedule:clock.schedule,cancel:clock.cancel,createImage(){
    const listeners=new Map(),image={naturalWidth:0,complete:false,decode(){return Promise.resolve();},addEventListener(type,callback){listeners.set(type,callback);},removeAttribute(name){delete this[name];},load(){this.naturalWidth=100;this.complete=true;listeners.get('load')?.();},fail(){listeners.get('error')?.();}};
    images.push(image);return image;
  }});
  return {thumbnail,element,images,clock};
}
test('representative photo selection uses one opaque local image and rejects remote or contact-bearing URLs',()=>{
  const photo='/api/photo?id=p0123456789abcdef';
  assert.equal(firstArrivalPhoto([null,'https://example.invalid/pet.jpg','/api/photo?id=one&phone=000',photo,'/api/photo?id=second']),photo);
  assert.equal(firstArrivalPhoto(['data:image/png,example','//example.invalid/pet.jpg','/api/photo?id=../secret','/api/photo?id=one#extra']),null);
  assert.equal(firstArrivalPhoto(['/assets/cat.svg','/assets/dog.svg']),'/assets/cat.svg');
});
test('thumbnail holds only one representative photo with generic accessible text and no referrer',()=>{
  const h=thumbnailSetup(),source='/api/photo?id=fictionalopaque';h.thumbnail.show(source);
  assert.equal(h.element.children.length,1);const image=h.element.children[0];assert.equal(image.src,source);
  assert.equal(image.alt,'조회된 공고 중 한 건의 사진');assert.equal(image.referrerPolicy,'no-referrer');assert.equal(image.loading,'eager');
});
test('missing or untrusted thumbnail source uses the brand without requesting it',()=>{
  for(const source of [null,undefined,'https://example.invalid/private.jpg']){
    const h=thumbnailSetup();h.thumbnail.show(source);assert.equal(h.images.length,1);assert.equal(h.images[0].src,ARRIVAL_BRAND_SOURCE);assert.equal(h.images[0].alt,'마중 · 공고 사진 없음');
  }
});
test('a failed photo falls back once and a failed brand never creates an error loop',()=>{
  const h=thumbnailSetup();h.thumbnail.show('/api/photo?id=fictionalopaque');h.images[0].fail();
  assert.equal(h.images.length,2);assert.equal(h.element.children[0].src,ARRIVAL_BRAND_SOURCE);
  h.images[1].fail();assert.equal(h.element.children.length,0);assert.equal(h.images.length,2);
});
test('closing on background removes the photo and ignores its later error callback',()=>{
  const h=thumbnailSetup();h.thumbnail.show('/api/photo?id=fictionalopaque');const old=h.images[0];
  h.thumbnail.clear();assert.equal(old.src,undefined);old.fail();assert.equal(h.element.children.length,0);assert.equal(h.images.length,1);
});
test('late failure from an earlier arrival cannot replace the next arrival thumbnail',()=>{
  const h=thumbnailSetup();h.thumbnail.show('/api/photo?id=previous');const old=h.images[0];
  h.thumbnail.show('/assets/dog.svg',{demo:true});old.fail();
  assert.equal(h.element.children[0].src,'/assets/dog.svg');assert.equal(h.element.children[0].alt,'가상 공고 중 한 건의 예시 일러스트');assert.equal(h.images.length,2);
});

const flush=()=>new Promise(resolve=>setImmediate(resolve));
function presentationSetup({demo=false}={}){
  const clock=fakeClock(),h=thumbnailSetup(clock),element={hidden:true};
  let state={...applyVehicle(receiveArrival(initialState(),'pleos:1',{sequence:1,now:clock.now(),timestamp:clock.now()}),{displayAllowed:true,gear:'P',speedMetersPerSecond:0}),casesReady:true,cases:[{id:'fictional'}],mode:demo?'demo':'live'};
  let visible=true,query=1,shown=0;
  const key=()=>`${state.arrivalKey}:${query}`;
  const permitted=()=>visible&&shouldShowArrivalPopup(state,clock.now());
  const banner=createArrivalBanner({element,canShow:permitted,onClose:h.thumbnail.clear,schedule:clock.schedule,cancel:clock.cancel});
  const presentation=createArrivalPresentation({thumbnail:h.thumbnail,banner,canShow:value=>value===key()&&permitted(),onShown(){shown++;state=consumeArrivalPopup(state);}});
  return {...h,banner,presentation,element,clock,state:()=>state,shown:()=>shown,start:()=>presentation.show({key:key(),source:demo?'/assets/dog.svg':'/api/photo?id=fictionalopaque',demo}),setState:fn=>state=fn(state),hide:()=>visible=false,nextQuery:()=>query++};
}
test('slow live image starts the full six seconds only after successful decode',async()=>{
  const h=presentationSetup();h.start();h.clock.advance(7000);
  assert.equal(h.banner.isOpen(),false);assert.equal(h.shown(),0);assert.equal(h.state().arrivalPopupPending,true);
  let decodeDone;h.images[0].decode=()=>new Promise(resolve=>decodeDone=resolve);h.images[0].load();await flush();
  assert.equal(h.banner.isOpen(),false);decodeDone();await flush();
  assert.equal(h.banner.isOpen(),true);assert.equal(h.thumbnail.isPhotoLoaded(),true);assert.equal(h.shown(),1);
  h.clock.advance(5999);assert.equal(h.banner.isOpen(),true);h.clock.advance(1);assert.equal(h.banner.isOpen(),false);assert.equal(h.thumbnail.isPhotoLoaded(),false);
});
test('demo illustration waits for decode but never reports a real notice photo',async()=>{
  const h=presentationSetup({demo:true});h.start();assert.equal(h.banner.isOpen(),false);
  h.images[0].load();await flush();assert.equal(h.banner.isOpen(),true);assert.equal(h.thumbnail.isPhotoLoaded(),false);assert.equal(h.shown(),1);
});
test('photo decode failure waits for brand fallback and never substitutes another animal',async()=>{
  const h=presentationSetup();h.start();h.images[0].decode=()=>Promise.reject(new Error('decode failed'));h.images[0].load();await flush();
  assert.equal(h.images.length,2);assert.equal(h.images[1].src,ARRIVAL_BRAND_SOURCE);assert.equal(h.banner.isOpen(),false);assert.equal(h.shown(),0);
  h.images[1].load();await flush();assert.equal(h.banner.isOpen(),true);assert.equal(h.thumbnail.isPhotoLoaded(),false);assert.equal(h.shown(),1);
});
test('silent image and silent fallback have bounded waits then show an explicit missing-photo state',()=>{
  const h=presentationSetup();h.start();h.clock.advance(ARRIVAL_PHOTO_TIMEOUT_MS);
  assert.equal(h.images[0].src,undefined);assert.equal(h.images[1].src,ARRIVAL_BRAND_SOURCE);assert.equal(h.banner.isOpen(),false);
  h.clock.advance(ARRIVAL_BRAND_TIMEOUT_MS);assert.equal(h.banner.isOpen(),true);assert.equal(h.thumbnail.isPhotoLoaded(),false);assert.equal(h.shown(),1);
  assert.equal(h.element.hidden,false);assert.equal(h.images.length,2);h.clock.advance(6000);assert.equal(h.banner.isOpen(),false);assert.equal(h.clock.timers.size,0);
});
test('missing source uses only brand fallback and cannot report a real photo',async()=>{
  const h=thumbnailSetup();let ready;h.thumbnail.show(null,{onReady:result=>ready=result});
  assert.equal(ready,undefined);h.images[0].load();await flush();assert.equal(ready.fallback,true);assert.equal(ready.photoLoaded,false);assert.equal(h.thumbnail.isPhotoLoaded(),false);
});
test('duplicate synchronization neither restarts preparation nor extends the visible timer',async()=>{
  const h=presentationSetup();assert.equal(h.start(),true);assert.equal(h.start(),false);assert.equal(h.images.length,1);
  h.images[0].load();await flush();h.clock.advance(3000);assert.equal(h.start(),false);h.clock.advance(3000);
  assert.equal(h.banner.isOpen(),false);assert.equal(h.shown(),1);assert.equal(h.start(),false);
});
for(const [name,change] of [
  ['P released',h=>h.setState(s=>applyVehicle(s,{displayAllowed:false,gear:'NOT_P',speedMetersPerSecond:0}))],
  ['unknown or stale parking',h=>h.setState(s=>applyVehicle(s,{displayAllowed:false,gear:'UNKNOWN'}))],
  ['background activity',h=>h.setState(s=>applyActivity(s,false))],
  ['hidden document',h=>h.hide()],
  ['replaced query',h=>h.nextQuery()],
  ['failed query',h=>h.setState(s=>({...s,error:'unavailable'}))],
])test(`${name} during photo preparation prevents display and acknowledgement`,async()=>{
  const h=presentationSetup();h.start();change(h);h.images[0].load();await flush();
  assert.equal(h.banner.isOpen(),false);assert.equal(h.shown(),0);assert.equal(h.thumbnail.isPhotoLoaded(),false);assert.equal(h.clock.timers.size,0);
});
test('cancel during decode removes the source and ignores both late success and error',async()=>{
  const h=presentationSetup();h.start();let finish;h.images[0].decode=()=>new Promise(resolve=>finish=resolve);h.images[0].load();await flush();
  h.presentation.clear();assert.equal(h.images[0].src,undefined);finish();h.images[0].fail();await flush();
  assert.equal(h.banner.isOpen(),false);assert.equal(h.shown(),0);assert.equal(h.images.length,1);assert.equal(h.clock.timers.size,0);
});
test('a pending arrival can retry after background only with fresh parking and query state',async()=>{
  const h=presentationSetup();h.start();h.setState(s=>applyActivity(s,false));h.presentation.clear();h.images[0].load();await flush();
  h.setState(s=>applyActivity(s,true));assert.equal(h.start(),false);
  h.setState(s=>applyVehicle(s,{displayAllowed:true,gear:'P',speedMetersPerSecond:0}));assert.equal(h.start(),false);
  h.setState(s=>({...s,casesReady:true}));h.nextQuery();assert.equal(h.start(),true);h.images[1].load();await flush();assert.equal(h.shown(),1);
});
test('arrival expiring during preparation cannot display even after image success',async()=>{
  const h=presentationSetup();h.setState(s=>({...s,arrivalAt:h.clock.now()-299000}));h.start();h.clock.advance(1000);h.images[0].load();await flush();
  assert.equal(h.banner.isOpen(),false);assert.equal(h.shown(),0);assert.equal(h.thumbnail.isPhotoLoaded(),false);
});

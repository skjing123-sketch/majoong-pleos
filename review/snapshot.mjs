export const SOURCE_URL = 'https://www.animal.go.kr/front/awtis/loss/lossList.do?menuNo=1000100000';
const ownKeys = (value, allowed) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).every(key => allowed.includes(key));
const plain = (value, max) => typeof value === 'string' && value.length <= max && !/[\u0000-\u001f\u007f<>]/u.test(value);
const regionName = value => plain(value, 60) && value.split(' ').every(part => /^[가-힣]{1,24}(?:도|시|군|구)$/u.test(part));
const safeText = (value, max) => plain(value, max) && !/(?:https?:|servicekey|api[_ -]?key|\d{7,}|0\d{1,2}[- .]\d{3,4}[- .]\d{4}|\d{2,3}\.\d{4,})/iu.test(value);
const assetPath = value => typeof value === 'string' && /^assets\/[A-Za-z0-9][A-Za-z0-9_-]*\.(?:png|jpg|jpeg|webp)$/u.test(value);
const hex = value => typeof value === 'string' && /^[a-f0-9]{64}$/u.test(value);
const isoDay = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/u.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0,10) === value;

/** A presentation-only allowlist. Raw API responses, contact fields and remote photo URLs are rejected. */
export function validateSnapshot(data, now = Date.now()) {
  if (!ownKeys(data, ['schemaVersion','state','dataMode','capturedAt','region','sourceUrl','cases','mockup']) ||
      data.schemaVersion !== 1 || data.dataMode !== 'api_snapshot' || data.sourceUrl !== SOURCE_URL ||
      !Array.isArray(data.cases) || data.cases.length > 10) throw new Error('자료 형식을 확인할 수 없습니다.');
  if (data.state === 'unavailable') {
    if (data.capturedAt !== null || data.region !== null || data.cases.length || data.mockup !== null) throw new Error('확인되지 않은 공고는 표시하지 않습니다.');
    return data;
  }
  if (data.state !== 'ready' || typeof data.capturedAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/u.test(data.capturedAt) ||
      !Number.isFinite(Date.parse(data.capturedAt)) || Date.parse(data.capturedAt) > now + 30000 || !regionName(data.region)) throw new Error('실제 조회 시각과 지역이 필요합니다.');
  for (const item of data.cases) {
    if (!ownKeys(item, ['reference','species','breed','reportedDate','region','features','photo','photoSha256']) ||
        !/^[a-f0-9]{16,64}$/u.test(item.reference) || !['dog','cat','other'].includes(item.species) ||
        !safeText(item.breed, 60) || !safeText(item.features, 240) || !regionName(item.region) ||
        !(item.region === data.region || item.region.startsWith(`${data.region} `)) ||
        !(item.reportedDate === null || isoDay(item.reportedDate)) || !assetPath(item.photo) || !hex(item.photoSha256)) throw new Error('공개용 공고 필드를 확인해 주세요.');
  }
  if (data.mockup !== null && (!ownKeys(data.mockup, ['image','mode','width','height','popupAreaFraction','navigationRegion']) ||
      !assetPath(data.mockup.image) || data.mockup.mode !== 'layout_concept' ||
      !Number.isInteger(data.mockup.width) || data.mockup.width < 1 || !Number.isInteger(data.mockup.height) || data.mockup.height < 1 ||
      !Number.isFinite(data.mockup.popupAreaFraction) || Math.abs(data.mockup.popupAreaFraction - 0.1) > 0.015 ||
      (data.mockup.navigationRegion !== null && !regionName(data.mockup.navigationRegion)))) throw new Error('배치 시안의 출처와 규격을 확인해 주세요.');
  return data;
}

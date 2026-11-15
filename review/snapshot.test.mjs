import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {validateSnapshot, SOURCE_URL} from './snapshot.mjs';

const sample = JSON.parse(await readFile(new URL('./sample.json', import.meta.url), 'utf8'));
const clone = () => structuredClone(sample);

test('published actual snapshot validates and local photo bytes match its hash', async () => {
  validateSnapshot(sample);
  for (const item of sample.cases) {
    const bytes = await readFile(new URL(item.photo, import.meta.url));
    assert.equal(createHash('sha256').update(bytes).digest('hex'), item.photoSha256);
  }
});
test('missing provider date stays null; an invented malformed day is rejected', () => {
  const value = clone();
  value.cases[0].reportedDate = null;
  validateSnapshot(value);
  value.cases[0].reportedDate = '2026-02-30';
  assert.throws(() => validateSnapshot(value));
});
test('contact fields and remote or traversing photo paths are rejected', () => {
  const value = clone();
  value.cases[0].contact = 'unapproved';
  assert.throws(() => validateSnapshot(value));
  delete value.cases[0].contact;
  for (const path of ['https://example.com/photo.jpg', 'assets/../photo.jpg', 'data:image/png;base64,AA']) {
    value.cases[0].photo = path;
    assert.throws(() => validateSnapshot(value));
  }
});
test('a case outside the query region is rejected', () => {
  const value = clone();
  value.cases[0].region = '부산광역시 중구';
  assert.throws(() => validateSnapshot(value));
});
test('a layout concept requires a finite popup area and declared mode', () => {
  const value = clone();
  value.mockup = {image:'assets/layout.png', mode:'layout_concept', width:1920, height:1080, popupAreaFraction:0.1, navigationRegion:'서울특별시 강남구'};
  validateSnapshot(value);
  delete value.mockup.popupAreaFraction;
  assert.throws(() => validateSnapshot(value));
  value.mockup.popupAreaFraction = 0.1;
  value.mockup.mode = 'actual_sdk_capture';
  assert.throws(() => validateSnapshot(value));
});
test('unavailable state cannot expose a case', () => {
  const empty = {schemaVersion:1, state:'unavailable', dataMode:'api_snapshot', capturedAt:null, region:null, sourceUrl:SOURCE_URL, cases:[], mockup:null};
  validateSnapshot(empty);
  empty.cases = sample.cases;
  assert.throws(() => validateSnapshot(empty));
});

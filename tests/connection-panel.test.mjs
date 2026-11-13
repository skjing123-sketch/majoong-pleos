import test from 'node:test';
import assert from 'node:assert/strict';
import {connectionPanelState, connectionPanelNeedsRender} from '../web/connection-panel.js';

test('ready only when monitoring and both independent connections are confirmed', () => {
  assert.deepEqual(connectionPanelState('pleos', {
    monitoring: true, connected: true, vehicleConnected: true,
  }), {ready: true, action: null});
});

for (const [name, status] of [
  ['missing status', null],
  ['stopped service', {monitoring: false, connected: true, vehicleConnected: true}],
  ['navigation unavailable', {monitoring: true, connected: false, vehicleConnected: true}],
  ['vehicle unavailable', {monitoring: true, connected: true, vehicleConnected: false}],
  ['vehicle status unknown', {monitoring: true, connected: true}],
  ['non-boolean state', {monitoring: true, connected: true, vehicleConnected: 'true'}],
]) {
  test(`${name} retains the connection action`, () => {
    assert.deepEqual(connectionPanelState('pleos', status), {ready: false, action: 'connect'});
  });
}

test('browser demo keeps its separate simulation action', () => {
  assert.deepEqual(connectionPanelState('browser', null), {ready: false, action: 'demo'});
});

const ready = {monitoring: true, connected: true, vehicleConnected: true};
const connecting = {monitoring: true, connected: true, vehicleConnected: false};
const base = {platform: 'pleos', phase: 'ready', displayable: false, previousStatus: connecting, nextStatus: ready, previousIssue: null, nextIssue: null};

test('connection completion refreshes the visible waiting panel', () => {
  assert.equal(connectionPanelNeedsRender(base), true);
});

test('repeated status with unchanged waiting panel does not refresh it', () => {
  assert.equal(connectionPanelNeedsRender({...base, previousStatus: ready}), false);
});

test('after arrival visible case cards are not recreated by connection status', () => {
  assert.equal(connectionPanelNeedsRender({...base, displayable: true}), false);
  assert.equal(connectionPanelNeedsRender({...base, displayable: true, nextIssue: 'error'}), false);
});

test('connection loss refreshes the waiting panel for retry', () => {
  assert.equal(connectionPanelNeedsRender({...base, previousStatus: ready, nextStatus: connecting}), true);
});

test('new or cleared connection error refreshes only the non-display panel', () => {
  assert.equal(connectionPanelNeedsRender({...base, phase: 'driving', nextIssue: 'error'}), true);
  assert.equal(connectionPanelNeedsRender({...base, phase: 'driving', previousIssue: 'error'}), true);
  assert.equal(connectionPanelNeedsRender({...base, previousIssue: 'error', nextIssue: 'error'}), false);
});

test('unchanged driving or arrived panel is not recreated on connection updates', () => {
  assert.equal(connectionPanelNeedsRender({...base, phase: 'driving'}), false);
  assert.equal(connectionPanelNeedsRender({...base, phase: 'arrived'}), false);
});

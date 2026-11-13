// Connection status is advisory UI only. It never opens the parked/arrival gate.
export function connectionPanelState(platform, status) {
  if (platform !== 'pleos') return {ready: false, action: 'demo'};
  if (status?.monitoring === true && status.connected === true && status.vehicleConnected === true) {
    return {ready: true, action: null};
  }
  return {ready: false, action: 'connect'};
}

// Rebuild the waiting panel only when its visible content changes. In
// particular, status updates must not recreate already displayed case cards.
export function connectionPanelNeedsRender({platform, phase, displayable, previousStatus, nextStatus, previousIssue, nextIssue}) {
  if (displayable) return false;
  if (previousIssue !== nextIssue) return true;
  if (nextIssue) return false;
  return phase === 'ready' &&
    connectionPanelState(platform, previousStatus).ready !== connectionPanelState(platform, nextStatus).ready;
}

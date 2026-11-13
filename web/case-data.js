export const CASE_REQUEST_TIMEOUT_MS = 20_000;
export const CASE_RESULT_MAX_AGE_MS = 5 * 60_000;
const CLOCK_SKEW_MS = 30_000;

// The provider timestamp belongs to the data, not the time its cached response arrived.
export function caseResultsFresh(state, now = Date.now()) {
  if (state.casesReady !== true) return false;
  if (state.mode !== 'live') return true;
  const fetchedAt = typeof state.meta?.fetchedAt === 'string' ? Date.parse(state.meta.fetchedAt) : NaN;
  return Number.isFinite(fetchedAt) && fetchedAt <= now + CLOCK_SKEW_MS && now - fetchedAt < CASE_RESULT_MAX_AGE_MS;
}

function abortError() {
  const error = new Error('조회가 취소되었습니다.');
  error.name = 'AbortError';
  return error;
}

/** Bounds the response and JSON body together, including a transport ignoring abort. */
export async function fetchJsonWithDeadline(url, {
  signal,
  timeoutMs = CASE_REQUEST_TIMEOUT_MS,
  fetchImpl = globalThis.fetch,
  schedule = setTimeout,
  cancel = clearTimeout,
  ...options
} = {}) {
  if (signal?.aborted) throw abortError();
  const controller = new AbortController();
  let timer, cancelRequest;
  const stopped = new Promise((_, reject) => {
    cancelRequest = () => { controller.abort(); reject(abortError()); };
    signal?.addEventListener('abort', cancelRequest, {once: true});
    timer = schedule(() => {
      const error = new Error('응답이 늦어지고 있어요. 잠시 후 다시 시도해 주세요.');
      error.name = 'TimeoutError';
      // Reject the deadline first: an abort-aware transport must not hide it as user cancellation.
      reject(error);
      controller.abort();
    }, timeoutMs);
  });
  try {
    const result = Promise.resolve().then(async () => {
      if (controller.signal.aborted) throw abortError();
      let response, data;
      try { response = await fetchImpl(url, {...options, signal: controller.signal}); }
      catch (error) {
        if (controller.signal.aborted) throw error;
        throw new Error('네트워크 연결을 확인한 뒤 다시 시도해 주세요.');
      }
      try { data = await response.json(); }
      catch (error) {
        if (controller.signal.aborted) throw error;
        throw new Error('공고 응답을 읽지 못했습니다. 잠시 후 다시 시도해 주세요.');
      }
      if (!response.ok) {
        const message = typeof data?.error?.message === 'string' ? data.error.message.slice(0, 300) : '서버 응답을 확인할 수 없습니다.';
        throw new Error(message);
      }
      return data;
    });
    return await Promise.race([result, stopped]);
  } finally {
    cancel(timer);
    signal?.removeEventListener('abort', cancelRequest);
  }
}

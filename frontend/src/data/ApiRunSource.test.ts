import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ApiRunSource } from './ApiRunSource';

/**
 * Why this file exists.
 *
 * <p>`listRuns` does not hand the server's JSON to the screen — it rebuilds every row field
 * by field, so anything the mapper forgets is invisible to the whole app no matter what the
 * API sends. `doneStepCount` (#129) is the field that turned "4 adım" into "1/4 adım" on the
 * task rail, and a mapper that dropped it would have left the rail looking exactly as it did
 * before the backend work, with nothing to point at.
 *
 * <p>The second claim is the one that costs something to get wrong. A row without the field
 * — an older server, or a cached response served while a new one is deploying — must come
 * out as unknown, not as zero. `?? 0` here would report no progress on a flow that is nearly
 * finished: wrong, and wrong in the reassuring direction.
 */

const fetchMock = vi.fn();

function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    headers: { get: () => 'application/json' },
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const ROW = {
  id: 'r-1',
  goal: 'Blocker etiketli açık kayıtları bul',
  status: 'awaiting_approval',
  costTokens: 1133,
  costUsd: 0.000693,
  budgetUsd: 0.5,
  stepCount: 4,
  createdAt: '2026-08-01T05:51:00Z',
  finishedAt: null,
};

it('the_progress_the_server_counted_survives_the_row_being_rebuilt', async () => {
  fetchMock.mockResolvedValue(jsonResponse({ items: [{ ...ROW, doneStepCount: 1 }] }));

  const rows = await new ApiRunSource('/api').listRuns({ status: 'awaiting_approval' });

  expect(rows[0]?.doneStepCount).toBe(1);
});

/**
 * The page has to reach the server, because a caller counting on it stops when a page
 * comes back short. Akışlar walks `GET /api/runs` to the end of the log — if `page` were
 * dropped here every request would answer with the first hundred, the walk would see a
 * full page for ever, and it would only stop at its own ceiling with five copies of the
 * newest hundred runs in hand.
 *
 * <p>`page=0` is not sent: it is the server's default, and a query string that spells out
 * every default is one nobody can read in a network log.
 */
it('the_page_asked_for_is_the_page_the_request_asks_the_server_for', async () => {
  fetchMock.mockResolvedValue(jsonResponse({ items: [ROW] }));

  await new ApiRunSource('/api').listRuns({ size: 100, page: 2 });
  expect(String(fetchMock.mock.calls[0]?.[0])).toBe('/api/runs?size=100&page=2');

  await new ApiRunSource('/api').listRuns({ size: 100, page: 0 });
  expect(String(fetchMock.mock.calls[1]?.[0])).toBe('/api/runs?size=100');
});

it('a_counted_zero_is_kept_apart_from_a_missing_count', async () => {
  fetchMock.mockResolvedValue(jsonResponse({ items: [{ ...ROW, doneStepCount: 0 }] }));
  const counted = await new ApiRunSource('/api').listRuns();
  // Measured: this run is planned and has not finished a step yet.
  expect(counted[0]?.doneStepCount).toBe(0);

  // No field at all — an older server, or a cache answering mid-deploy.
  fetchMock.mockResolvedValue(jsonResponse({ items: [ROW] }));
  const unknown = await new ApiRunSource('/api').listRuns();
  expect(unknown[0]?.doneStepCount).toBeNull();
  expect(unknown[0]?.stepCount).toBe(4);
});

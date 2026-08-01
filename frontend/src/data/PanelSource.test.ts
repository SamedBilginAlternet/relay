import { afterEach, expect, it, vi } from 'vitest';

/**
 * Why this file exists.
 *
 * <p>The panel's money block is deployed in two pieces that do not land together: the
 * columns that record which model answered arrive in a migration, and the screen that
 * reads them arrives in a container. Between those two moments the API answers without
 * `models` and without `routing`, and the reader on the other side of that gap must get
 * a panel that is missing one block — not a panel that has invented one.
 *
 * <p>Every assertion below is about a zero that must not be printed. `num()` turns a
 * missing number into 0 everywhere else in this file, which is right for a count and
 * catastrophic for a counterfactual: "the strong model would have cost $0.000000" is a
 * claim, and it is the most flattering false claim this product could make about itself.
 *
 * <p>The last one is about arithmetic the reader does by eye. The comparison prints three
 * amounts on one line, and if the third is not exactly the difference of the first two as
 * printed, the one number a buyer repeats out loud is one nobody can check.
 */

vi.mock('./index', () => ({ API_BASE_URL: '/api', RUN_SOURCE_KIND: 'api' }));

const { getPanelSource } = await import('./PanelSource');

/** The rest of the payload, so each test only has to say what it is about. */
const REST = {
  from: '2026-07-25T00:00:00Z',
  to: '2026-08-01T00:00:00Z',
  runs: { total: 3, byStatus: { done: 3 } },
  approvals: {},
  rejections: [],
  cancellations: [],
  tools: [],
  totals: { tokens: 1000, costUsd: 0.001 },
};

function answers(body: Record<string, unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(JSON.stringify({ ...REST, ...body }), { status: 200 })),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

it('a_server_that_does_not_know_about_models_yet_yields_no_block_rather_than_zeros', async () => {
  // Exactly what /api/panel answers today: no `models` key, no `routing` key.
  answers({});

  const report = await getPanelSource().report({});

  expect(report.models).toEqual([]);
  // null, not { costUsd: 0, premiumCostUsd: 0, differenceUsd: 0 }. The screen reads this
  // to decide whether it may make a claim at all.
  expect(report.routing).toBeNull();
  // …and the rest of the panel is untouched by the block that is not there.
  expect(report.runs.total).toBe(3);
  expect(report.totals.costUsd).toBe(0.001);
});

it('a_model_row_without_a_counterfactual_keeps_it_absent_instead_of_free', async () => {
  answers({
    models: [{ model: 'groq:llama-3.1-8b-instant', calls: 4, tokens: 900, costUsd: 0.0002 }],
    routing: null,
  });

  const report = await getPanelSource().report({});

  const [row] = report.models;
  expect(row).toBeDefined();
  expect(row?.calls).toBe(4);
  // The one field in this file that must survive as null all the way to the screen.
  expect(row?.premiumCostUsd).toBeNull();
  expect(report.routing).toBeNull();
});

it('the_difference_is_the_two_printed_amounts_subtracted_not_a_third_number', async () => {
  // A server that contradicts itself — the difference here is not premium minus cost.
  answers({
    models: [],
    routing: {
      calls: 41,
      tokens: 23_800,
      costUsd: 0.01,
      premiumCostUsd: 0.03,
      differenceUsd: 0.09,
      unpricedCalls: 2,
    },
  });

  const report = await getPanelSource().report({});

  // 0.03 - 0.01, because those are the two amounts the reader can see on the line. A
  // difference that does not come out of them is not checkable, which is the only reason
  // this block is allowed on the screen.
  expect(report.routing?.differenceUsd).toBeCloseTo(0.02, 10);
  expect(report.routing?.calls).toBe(41);
  // Coverage travels with the line rather than being inferred from it.
  expect(report.routing?.unpricedCalls).toBe(2);
});

// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ApiError } from '../data/ApiRunSource';
import type { Brief } from '../types/brief';

/**
 * Why this file exists.
 *
 * <p>Bugün used to fail in its own words. It kept a private `Failure` type, its
 * own `notice--danger` box and a `{error.message}` straight onto the screen —
 * the exact line #63 removed from Panel and Politikalar, still here, one bad
 * exception away from putting "Failed to fetch" in the middle of a Turkish
 * product. Converging it onto the shared `LoadError` is a refactor, and a
 * refactor is precisely the change that can quietly take a button away.
 *
 * <p>So these tests hold what the screen promised before the move: a Turkish
 * sentence with no machine detail in it, a way to try again when trying again
 * could work, and no button at all when the server has already said no.
 */

const getBrief = vi.fn<() => Promise<Brief>>();

vi.mock('../data', () => ({
  RUN_SOURCE_KIND: 'api',
  getBriefSource: () => ({
    getBrief,
    refreshBrief: getBrief,
    startFromSuggestion: vi.fn(),
  }),
}));

vi.mock('../data/PlaybookSource', () => ({
  getPlaybookSource: () => ({ list: async () => [], run: vi.fn() }),
}));

const { TodayScreen } = await import('./TodayScreen');

afterEach(() => {
  cleanup();
  getBrief.mockReset();
});

/** A brief with nothing in it but the fields the header reads. */
function briefWith(overrides: Partial<Brief> = {}): Brief {
  return {
    date: '2026-08-01',
    today: null,
    digest: null,
    priority: [],
    inbox: { status: 'ok', items: [] },
    work: { status: 'ok', items: [] },
    code: { status: 'ok', items: [] },
    calendar: { status: 'ok', items: [] },
    ...overrides,
  };
}

async function renderFailing(error: unknown) {
  getBrief.mockRejectedValue(error);
  render(<TodayScreen onNavigate={() => {}} />);
  return await screen.findByRole('alert');
}

it('an_unreachable_server_is_explained_in_turkish_with_a_way_to_try_again', async () => {
  const box = await renderFailing(
    new ApiError('Sunucuya ulaşılamadı. Bağlantını ve API adresini kontrol et.', 0),
  );

  expect(box.textContent ?? '').toContain(
    'Sunucuya ulaşılamadı. Bağlantını ve API adresini kontrol et.',
  );
  expect(screen.getByRole('button', { name: /Tekrar dene/ })).toBeTruthy();
});

it('a_request_the_server_refused_offers_no_button_that_cannot_work', async () => {
  // 403 is the server having read the request and said no. Pressing the same
  // button gets the same answer, so there is no button.
  await renderFailing(new ApiError('Bu bilgiyi görme yetkin yok.', 403));

  await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
  expect(screen.queryByRole('button', { name: /Tekrar dene/ })).toBeNull();
});

it('a_server_error_still_offers_a_way_to_try_again', async () => {
  await renderFailing(new ApiError('Brifing alınamadı (HTTP 502).', 502));

  expect(screen.getByRole('button', { name: /Tekrar dene/ })).toBeTruthy();
});

it('the_error_box_never_repeats_the_browsers_own_english_words', async () => {
  const box = await renderFailing(new TypeError('Failed to fetch'));

  expect(box.textContent ?? '').not.toMatch(/failed to fetch/i);
  expect(box.textContent ?? '').toContain(
    'Sunucuya ulaşılamadı. Bağlantını ve API adresini kontrol et.',
  );
});

it('the_error_box_never_names_a_build_variable_the_reader_cannot_set', async () => {
  const box = await renderFailing(
    new ApiError('VITE_API_BASE_URL ayarlanmamış', 500),
  );

  expect(box.textContent ?? '').not.toMatch(/VITE_/);
});

/**
 * Bugün is the only screen whose column is declared by its own class. Both
 * classes set 1040px, but `scrollbar-gutter: stable` hangs off the modifier
 * alone, so without it this screen sat 5px left of the other five and the nav
 * moved every time you clicked into it (#69).
 */
it('the_column_is_declared_the_same_way_as_every_other_screen', async () => {
  getBrief.mockRejectedValue(new ApiError('Brifing alınamadı (HTTP 502).', 502));
  const { container } = render(<TodayScreen onNavigate={() => {}} />);
  await screen.findByRole('alert');

  const inner = container.querySelector('.page > .page__inner');
  expect(inner?.classList.contains('page__inner--app')).toBe(true);
});

/**
 * The counted line cannot be wrong — it is arithmetic over the rows on screen —
 * but it cannot say what the day is *about* either, and "6 iş seni bekliyor" is
 * not a briefing. #58 deleted four layers that all repeated the list; this one
 * sentence is the one that was asked for back.
 */
it('the_day_is_said_in_words_as_well_as_in_numbers', async () => {
  getBrief.mockResolvedValue(
    briefWith({
      digest: {
        summary: 'Bugün ödeme adımında hata alan bir sipariş ve bekleyen bir PR var.',
        priorities: [],
        advice: 'Önce ödeme hatasını çöz, sonra PR’a bak.',
      },
    }),
  );

  render(<TodayScreen onNavigate={() => {}} />);

  expect(
    await screen.findByText('Bugün ödeme adımında hata alan bir sipariş ve bekleyen bir PR var.'),
  ).toBeTruthy();
  // The advice line came back with it: "first this, then that" is a sentence
  // about the list, and the product owner wants it said rather than implied by
  // the ordering (#102, reversing that half of #58).
  // Still said out loud (#102) — in the margin beside the list now rather than stacked
  // above it, which is where everything ABOUT the day moved in #142.
  expect(screen.getByText(/Önce ödeme hatasını çöz/)).toBeTruthy();
  expect(document.querySelector('.day-rail')?.textContent).toContain('Önce ödeme hatasını');
});

/** A spent token budget costs a sentence, not the screen: the counts stand alone. */
it('no_written_summary_leaves_the_counted_line_standing', async () => {
  getBrief.mockResolvedValue(briefWith({ digest: null }));

  const { container } = render(<TodayScreen onNavigate={() => {}} />);
  await screen.findAllByText(/Seni bekleyen bir şey görünmüyor/);

  expect(container.querySelector('.tally__headline')).toBeTruthy();
  expect(container.querySelector('.tally__summary')).toBeNull();
});

/**
 * The top of the screen said "Bugün" three times inside 200px: the h1, the
 * counted sentence under it and the label on the arrivals block. Only the h1
 * has to — it is the screen's name and it carries the date. The other two read
 * as a stutter, which is what the product owner opened the issue about.
 */
it('the_word_bugun_is_said_once_at_the_top_and_not_again_underneath', async () => {
  getBrief.mockResolvedValue(
    briefWith({
      today: {
        headline: 'sunucunun kendi cümlesi — bu ekranda gösterilmiyor',
        lines: ['6 mail bir kişiden geldi'],
        highlights: [],
        counts: {
          inbox: 6,
          inboxPersonal: 6,
          inboxBulk: 0,
          work: 0,
          code: 0,
          calendar: 0,
          urgent: 0,
        },
      },
      // No digest: the model writes free prose and may legitimately say the
      // word itself, which is not what this test is about.
      digest: null,
    }),
  );

  const { container } = render(<TodayScreen onNavigate={() => {}} />);
  await screen.findByText(/6 mail bir kişiden geldi/);

  const said = (container.textContent ?? '').match(/Bugün/g) ?? [];
  expect(said).toHaveLength(1);
  // And it is the h1 that kept it, with the date beside it.
  expect(container.querySelector('h1')?.textContent).toContain('Bugün');
});

/**
 * The home screen has to answer "what came in today" as well as "what do I do".
 * #58 deleted the counted breakdown along with the layers that repeated the
 * list; the counts were not a repetition, they were the only place the inbox
 * volume is said out loud. They carry a label now, because #60's complaint was
 * that three numbers sat here with nothing saying what each of them counted.
 */
it('what_arrived_today_is_counted_and_labelled_next_to_what_must_be_done', async () => {
  getBrief.mockResolvedValue(
    briefWith({
      today: {
        headline: 'Bugün 10 iş seni bekliyor · 1 toplantı.',
        lines: ['7 mail bir kişiden geldi (8 bülten ayrıldı)', '1 toplantı — ilki 09:00'],
        highlights: [],
        counts: { inbox: 15, inboxPersonal: 7, inboxBulk: 8, work: 0, code: 3, calendar: 1, urgent: 0 },
      },
      digest: {
        summary: 'Ödeme adımında hata var.',
        priorities: [],
        advice: 'Önce ödeme hatasını çöz, sonra bülten olmayan maillere bak.',
      },
    }),
  );

  render(<TodayScreen onNavigate={() => {}} />);

  expect(await screen.findByText(/7 mail bir kişiden geldi/)).toBeTruthy();
  expect(screen.getByText('Gelenler')).toBeTruthy();
  // Still said out loud (#102) — in the margin beside the list now rather than stacked
  // above it, which is where everything ABOUT the day moved in #142.
  expect(screen.getByText(/Önce ödeme hatasını çöz/)).toBeTruthy();
  expect(document.querySelector('.day-rail')?.textContent).toContain('Önce ödeme hatasını');
});

/** A day the server counted nothing for draws no empty label and no stray dot. */
it('a_day_with_nothing_counted_draws_no_breakdown_at_all', async () => {
  const { container } = render(<TodayScreen onNavigate={() => {}} />);
  getBrief.mockResolvedValue(briefWith({}));

  await screen.findAllByText(/Bugün/);
  expect(container.querySelector('.tally__lines')).toBeNull();
  expect(container.querySelector('.tally__advice')).toBeNull();
});

/**
 * The card's promise about a write. It shipped invisible once: the client
 * normaliser rebuilds each suggested action field by field, so `risk` — which
 * the server had been sending all along — never reached the component and the
 * sentence simply never rendered (#107).
 */
it('a_write_says_it_will_stop_for_a_signature_before_it_is_pressed', async () => {
  getBrief.mockResolvedValue(
    briefWith({
      priority: [
        {
          id: 'gmail:1',
          source: 'gmail',
          title: 'Ödeme adımında hata',
          kind: 'bug_report',
          urgency: 'high',
          summary: 'Müşteri ödeme adımında hata alıyor.',
          suggestedActions: [
            {
              tool: 'jira.createIssue',
              label: 'Jira kaydı aç',
              params: { projectKey: 'KAN', summary: 'Ödeme adımında hata' },
              risk: 'write',
            },
          ],
          url: 'https://mail.google.com/mail/u/0/#inbox/1',
        },
      ],
    }),
  );

  const { container } = render(<TodayScreen onNavigate={() => {}} />);

  await screen.findByText('Ödeme adımında hata');
  container.querySelector<HTMLButtonElement>('.arow__open')?.click();

  await screen.findByText(/sen onaylamadan gönderilmez/);
  // The values that will be sent, under the same names the approval gate uses.
  expect(screen.getByText('Proje')).toBeTruthy();
  expect(screen.getByText('KAN')).toBeTruthy();
  // And the way back to the original.
  expect(screen.getByRole('link', { name: /E-posta’da aç/ })).toBeTruthy();
});

/**
 * Building a brief calls five providers and spends two model turns — 3.6s when the model
 * answers and 14.3s when every key is at its daily wall. The server hands back the last
 * brief it had while it builds the next one, so the screen paints at once.
 *
 * <p>Two things have to hold for that to be honest rather than merely fast: the reader is
 * told the answer is being replaced, and the replacement actually arrives without anyone
 * pressing anything. The second one is a `setTimeout` away from silently never happening.
 */
it('a_stale_brief_says_so_and_is_replaced_without_a_refresh', async () => {
  vi.useFakeTimers();
  try {
    getBrief
      .mockResolvedValueOnce(briefWith({ stale: true }))
      .mockResolvedValueOnce(briefWith({ stale: false }));

    render(<TodayScreen onNavigate={() => {}} />);

    await vi.waitFor(() => expect(screen.getByText(/yenileniyor/)).toBeTruthy());
    expect(getBrief).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(6100);

    // Collected once, and the line stops claiming a rebuild that has finished.
    expect(getBrief).toHaveBeenCalledTimes(2);
    await vi.waitFor(() => expect(screen.queryByText(/yenileniyor/)).toBeNull());

    // Once, not a poll: nothing else goes out however long the screen stays open.
    await vi.advanceTimersByTimeAsync(60_000);
    expect(getBrief).toHaveBeenCalledTimes(2);
  } finally {
    vi.useRealTimers();
  }
});

/** A current brief asks for nothing more, whatever the clock does. */
it('a_current_brief_is_not_re_fetched_behind_the_readers_back', async () => {
  vi.useFakeTimers();
  try {
    getBrief.mockResolvedValue(briefWith({ stale: false }));

    render(<TodayScreen onNavigate={() => {}} />);
    await vi.waitFor(() => expect(getBrief).toHaveBeenCalledTimes(1));

    await vi.advanceTimersByTimeAsync(60_000);
    expect(getBrief).toHaveBeenCalledTimes(1);
  } finally {
    vi.useRealTimers();
  }
});

/**
 * The screen is where the strip is made distinct, because a row cannot know it is saying
 * the same thing as the row above it — the whole visible list has to be seen at once
 * (#141). This is the wiring test for that: the same three GitHub rows that shipped with
 * one sentence between them, drawn from a payload shaped like the live one.
 */
it('three_rows_that_arrived_alike_are_drawn_apart_by_their_own_facts', async () => {
  const why = "deposuna ait bir pull request var ve senin PR'ın — incelemeye başlanabilir.";
  getBrief.mockResolvedValue(
    briefWith({
      priority: [
        {
          id: 'github-pr:acme/pay#128',
          source: 'github',
          title: 'Kurulum notunu README ekle',
          subtitle: "senin PR'ın",
          kind: 'fyi',
          urgency: 'normal',
          summary: '',
          suggestedActions: [],
        },
        {
          id: 'github-pr:acme/pay#131',
          source: 'github',
          title: 'Retry politikası eksik',
          subtitle: 'review bekliyor',
          kind: 'fyi',
          urgency: 'normal',
          summary: '',
          suggestedActions: [],
        },
      ],
      code: {
        status: 'ok',
        items: [
          { id: 'github-pr:acme/pay#128', title: 'a', meta: '262 gün' },
          { id: 'github-pr:acme/pay#131', title: 'b', meta: '4 gün' },
        ],
      },
      digest: {
        summary: '',
        priorities: [
          { itemId: 'github-pr:acme/pay#128', why: `acme/pay ${why}` },
          { itemId: 'github-pr:acme/pay#131', why: `relay-web ${why}` },
        ],
      },
    }) as Brief,
  );

  render(<TodayScreen onNavigate={() => {}} />);

  await screen.findByText('Kurulum notunu README ekle');
  const strips = [...document.querySelectorAll('.arow__facts')].map((el) => el.textContent);
  expect(strips).toHaveLength(2);
  // The account name is dropped because both rows carry it: a name shared by every row
  // is not a fact about any of them (#141).
  expect(strips[0]).toContain('pay#128');
  expect(strips[0]).not.toContain('acme/');
  expect(strips[0]).toContain("senin PR'ın");
  expect(strips[0]).toContain('262 gün');
  expect(new Set(strips).size).toBe(strips.length);

  // The sentence they shared is now on one row, not two — the other keeps its copy in
  // the body, one press away.
  expect(document.querySelectorAll('.arow__why')).toHaveLength(1);

  // And the word beside the mark is gone: "GitHub" printed on every row said what the
  // mark says, while the line under the title names the repository, which differs.
  expect(document.querySelector('.src-badge')).toBeNull();
});

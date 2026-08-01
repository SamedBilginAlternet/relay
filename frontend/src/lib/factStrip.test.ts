import { expect, it } from 'vitest';
import { dedupeStrips, dropCommonOwner, factStrip, itemHandle, rationReasons } from './insight';

/**
 * Why this file exists.
 *
 * <p>Three rows of the live brief on 2026-08-01 carried the same sentence, word for word:
 * "…deposuna ait bir pull request var ve senin PR'ın — incelemeye başlanabilir." The row's
 * second line stopped being worth reading after one visit, and with it went the one place
 * a real reason could have appeared.
 *
 * <p>The sentence is replaced by machine tokens, and that replacement has a failure mode
 * worse than the thing it fixes. Repeated prose reads as a tired model. Identical mono
 * reads as measured data, and measured data is believed — so three rows all saying
 * `github · pull request · açık` would be a lie the reader has no reason to doubt.
 * Sameness in a fixed-width column is invisible until it has already destroyed scanning.
 *
 * <p>These tests hold the two rules that stand between the strip and that failure: no two
 * visible rows may print the same strip, and a row that cannot be made distinct prints
 * nothing rather than something invented.
 */

it('the_three_rows_that_read_alike_are_told_apart_by_their_own_facts', () => {
  const rows = [
    { id: 'github-pr:acme/pay#128', source: 'github' as const, subtitle: "senin PR'ın", kind: 'fyi' },
    { id: 'github-pr:acme/pay#131', source: 'github' as const, subtitle: 'review bekliyor', kind: 'fyi' },
    { id: 'github-issue:relay-web#7', source: 'github' as const, subtitle: 'sana atandı', kind: 'fyi' },
  ];
  const ages = ['262 gün', '4 gün', '1 gün'];

  const strips = rows.map((row, i) => ({ id: row.id, tokens: factStrip(row, ages[i]!) }));

  expect(strips.map((s) => s.tokens)).toEqual([
    ['acme/pay#128', "senin PR'ın", '262 gün'],
    ['acme/pay#131', 'review bekliyor', '4 gün'],
    ['relay-web#7', 'sana atandı', '1 gün'],
  ]);
  // Already distinct at the first token, so nothing is taken away.
  expect(dedupeStrips(strips).map((s) => s.tokens.length)).toEqual([3, 3, 3]);
});

it('no_two_visible_rows_ever_print_the_same_strip', () => {
  const same = ['github', 'pull request', 'açık'];
  const out = dedupeStrips([
    { id: 'a', tokens: [...same] },
    { id: 'b', tokens: [...same] },
    { id: 'c', tokens: [...same] },
    { id: 'd', tokens: ['acme/pay#9', 'açık', '2 gün'] },
  ]);

  const visible = out.map((s) => s.tokens.join(' · ')).filter((s) => s !== '');
  expect(new Set(visible).size).toBe(visible.length);
});

it('a_row_that_cannot_be_made_distinct_says_nothing_rather_than_something_invented', () => {
  const same = ['github', 'pull request', 'açık'];
  const out = dedupeStrips([
    { id: 'a', tokens: [...same] },
    { id: 'b', tokens: [...same] },
    { id: 'c', tokens: [...same] },
  ]);

  // The highest-ranked row keeps everything; the others are silent, not shortened to
  // `github` — a shorter version of the same claim is the same claim.
  expect(out[0]!.tokens).toEqual(same);
  expect(out[1]!.tokens).toEqual([]);
  expect(out[2]!.tokens).toEqual([]);
  expect(out.flatMap((s) => s.tokens)).not.toContain('—');
});

it('rank_decides_who_keeps_the_strip_never_the_later_row', () => {
  const out = dedupeStrips([
    { id: 'first', tokens: ['aynı', 'şey'] },
    { id: 'second', tokens: ['aynı', 'şey'] },
  ]);

  expect(out[0]!.tokens).toEqual(['aynı', 'şey']);
  expect(out[1]!.tokens).toEqual([]);
});

it('a_strip_is_never_padded_to_three_and_never_carries_an_empty_token', () => {
  const bare = factStrip(
    { id: 'gmail:18f2', source: 'gmail', from: '', subtitle: '', kind: 'fyi' },
    null,
  );

  expect(bare.length).toBeLessThanOrEqual(3);
  expect(bare.every((token) => token.trim().length > 0)).toBe(true);
  expect(bare).not.toContain('—');
});

it('a_mail_is_named_by_its_sender_because_its_id_is_a_handle_nobody_reads', () => {
  expect(itemHandle({ id: 'gmail:19fbbf392133acdc', source: 'gmail', from: 'Mehmet Kaya' })).toBe(
    'Mehmet Kaya',
  );
  expect(itemHandle({ id: 'jira:KAN-42', source: 'jira' })).toBe('KAN-42');
  expect(itemHandle({ id: 'github-pr:acme/pay#128', source: 'github' })).toBe('acme/pay#128');
});

it('a_mail_does_not_print_its_sender_twice_when_the_subtitle_is_the_sender', () => {
  const tokens = factStrip(
    { id: 'gmail:18f2', source: 'gmail', from: 'Mehmet Kaya', subtitle: 'Mehmet Kaya', kind: 'needs_reply' },
    '6 sa',
  );

  expect(tokens[0]).toBe('Mehmet Kaya');
  expect(tokens[1]).not.toBe('Mehmet Kaya');
  expect(tokens).toHaveLength(3);
});

/**
 * The user's actual complaint, in the Turkish it arrived in. Three reasons that each pass
 * the tautology guard on their own — none of them merely restates its own title — and
 * together are one sentence printed three times.
 */
it('only_the_first_of_three_rows_saying_the_same_thing_keeps_its_sentence', () => {
  const why = "acme/pay deposuna ait bir pull request var ve senin PR'ın — incelemeye başlanabilir.";
  const kept = rationReasons([
    { id: 'a', title: 'Kurulum notunu README ekle', why },
    { id: 'b', title: 'Retry politikası eksik', why: why.replace('acme/pay', 'relay-web') },
    { id: 'c', title: 'Ödeme servisi timeout dönüyor', why },
  ]);

  expect(kept.get('a')).not.toBeNull();
  expect(kept.get('b')).toBeNull();
  expect(kept.get('c')).toBeNull();
});

it('a_reason_that_says_something_new_keeps_its_line', () => {
  const kept = rationReasons([
    {
      id: 'a',
      title: 'Ödeme hatası',
      why: 'Müşteri üç farklı kartla denemiş, sipariş no R-44W-VG2 elimizde.',
    },
    {
      id: 'b',
      title: 'Sprint demosu',
      why: 'Mehmet iki gündür slaytları bekliyor, toplantı yarın 09:00.',
    },
  ]);

  expect(kept.get('a')).not.toBeNull();
  expect(kept.get('b')).not.toBeNull();
});

/**
 * `toLocaleLowerCase('tr')` is the whole reason `words()` exists. Half the titles on this
 * screen carry a dotless ı, and the default locale folds `I` to `i`, so two spellings of
 * the same word stop matching and the duplicate sentence walks straight through.
 */
it('two_spellings_of_one_turkish_word_count_as_one_reason', () => {
  const kept = rationReasons([
    { id: 'a', title: 'Kayıt', why: 'IPTAL edilen sipariş için müşteri geri dönüş bekliyor.' },
    { id: 'b', title: 'Başka kayıt', why: 'ıptal edilen sipariş için müşteri geri dönüş bekliyor.' },
  ]);

  expect(kept.get('a')).not.toBeNull();
  expect(kept.get('b')).toBeNull();
});

/**
 * Live on 2026-08-01 the strip read
 * `SamedBilginAlternet/issue-to-notion-demo#43 · senin PR'ın · SamedBilginAlternet · 8sa
 * önce` — nineteen characters of account name printed twice on one line and identically
 * on the row below it, which is the sameness the strip exists to remove.
 */
it('an_account_name_shared_by_every_row_is_not_printed_on_any_of_them', () => {
  const out = dropCommonOwner([
    { id: 'a', tokens: ['acme/pay#128', "senin PR'ın", '8sa önce'] },
    { id: 'b', tokens: ['acme/api#7', 'sana atandı', '2 gün'] },
  ]);

  expect(out.map((r) => r.tokens[0])).toEqual(['pay#128', 'api#7']);
});

it('an_account_name_that_tells_two_rows_apart_stays_on_both', () => {
  const rows = [
    { id: 'a', tokens: ['acme/pay#128', "senin PR'ın", '8sa önce'] },
    { id: 'b', tokens: ['other/pay#128', 'sana atandı', '2 gün'] },
  ];

  // Dropping it here would leave two rows reading `pay#128`, and the dedupe would then
  // silently empty the second one for a collision this function had caused.
  expect(dropCommonOwner(rows)).toEqual(rows);
});

it('the_subtitle_contributes_one_token_not_a_line_of_its_own', () => {
  const tokens = factStrip(
    {
      id: 'github-pr:acme/pay#43',
      source: 'github',
      // What the backend actually sends: two facts joined by this strip's own separator.
      subtitle: "senin PR'ın · SamedBilginAlternet",
      kind: 'fyi',
    },
    '8sa önce',
  );

  expect(tokens).toEqual(['acme/pay#43', "senin PR'ın", '8sa önce']);
});

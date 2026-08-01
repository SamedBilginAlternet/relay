import { expect, it } from 'vitest';
import { reasonEarnsItsLine } from './insight';

/**
 * Why this file exists.
 *
 * <p>Bugün printed a "Neden şimdi" line under every job, and on most jobs that
 * line was the title again in different grammar (#67, #55): the row "Kurulum
 * notunu README'ye ekle" was justified with "Kurulum notunun README'ye
 * eklenmesi gerekiyor." Nothing was lost by deleting those lines and a whole
 * row of screen was spent printing them — worse, they taught the reader to skip
 * the place where a real reason ("262 gündür review bekliyor") would appear.
 *
 * <p>The rule that replaced them is easy to break in two directions, so both
 * are pinned here. The sentences below are real: the tautologies are the ones
 * quoted as evidence in #67 and #55, and the reasons that must survive are the
 * ones those issues name as the field working properly.
 */

it('a_reason_that_only_conjugates_the_title_is_dropped', () => {
  expect(
    reasonEarnsItsLine(
      "Kurulum notunu README'ye ekle",
      "Kurulum notunun README'ye eklenmesi gerekiyor.",
    ),
  ).toBe(false);
});

it('a_reason_that_repeats_the_title_and_calls_it_important_is_dropped', () => {
  expect(
    reasonEarnsItsLine(
      'Login sonrası yönlendirme kayboluyor',
      'Login sonrası yönlendirme kayboluyor ve bu önemli bir sorun.',
    ),
  ).toBe(false);
});

it('a_reason_that_says_the_title_needs_solving_is_dropped', () => {
  expect(
    reasonEarnsItsLine(
      'Login sonrası yönlendirme kayboluyor',
      'Login sonrası yönlendirme kaybolma sorunu çözülmesi gerekiyor.',
    ),
  ).toBe(false);
});

it('a_reason_that_rewrites_the_title_in_the_passive_is_dropped', () => {
  expect(
    reasonEarnsItsLine(
      'Ödeme adımında hata alıyoruz — sipariş tamamlanmıyor',
      'Ödeme adımında hata alınıyor ve sipariş tamamlanmıyor.',
    ),
  ).toBe(false);
});

it('a_reason_naming_the_deadline_the_title_does_not_mention_is_kept', () => {
  expect(
    reasonEarnsItsLine(
      'Ödeme adımında hata alıyoruz — sipariş tamamlanmıyor',
      'Müşteri demosu 14:00’te ve bu hata onu bloke ediyor.',
    ),
  ).toBe(true);
});

it('a_reason_counting_how_long_it_has_waited_is_kept', () => {
  expect(
    reasonEarnsItsLine('feat: implement amadeus task (initial)', '262 gündür review bekliyor'),
  ).toBe(true);
});

it('a_reason_that_quotes_the_title_but_adds_a_clock_is_kept', () => {
  // The echo test alone would drop this one; the number is what saves it, and
  // a time is precisely the fact #67 asks the line to carry.
  expect(
    reasonEarnsItsLine(
      'Ödeme adımında hata alıyoruz',
      'Ödeme adımında hata alıyoruz, müşteri demosu 14:00’te.',
    ),
  ).toBe(true);
});

it('a_reason_that_quotes_the_title_and_then_says_more_is_kept', () => {
  expect(
    reasonEarnsItsLine(
      'Ödeme adımında hata alıyoruz',
      'Ödeme adımında hata alıyoruz; destek ekibi müşteriye dönmeyi bekliyor.',
    ),
  ).toBe(true);
});

/**
 * The dotless ı is not decoration.
 *
 * With the default locale, `'IPTAL'.toLowerCase()` is "iptal" while the title's
 * own "İptal" folds to "iptal" too — so an unrelated word matches — and
 * "Ödeme"/"ÖDEME" behave differently from "Iptal"/"ıptal". Folding with the
 * Turkish locale on both sides is what makes the comparison mean anything.
 */
it('an_upper_case_turkish_title_still_matches_its_own_words', () => {
  expect(reasonEarnsItsLine('IPTAL ISTEĞI GELDİ', 'Iptal isteği geldi.')).toBe(false);
});

it('an_empty_reason_never_takes_a_line', () => {
  expect(reasonEarnsItsLine('Kurulum notunu ekle', '')).toBe(false);
  expect(reasonEarnsItsLine('Kurulum notunu ekle', '   ')).toBe(false);
  expect(reasonEarnsItsLine('Kurulum notunu ekle', null)).toBe(false);
});

it('a_reason_is_kept_when_the_title_is_not_words_at_all', () => {
  // Nothing to compare against is not evidence of repetition.
  expect(reasonEarnsItsLine('#43', 'Review bekliyor.')).toBe(true);
});

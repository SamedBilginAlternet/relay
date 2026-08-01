import { expect, it } from 'vitest';
import { normalizeBrief } from './ApiBriefSource';

/**
 * Why this file exists.
 *
 * <p>The brief normaliser rebuilds every field by name, which is how `risk`
 * shipped invisible for a release (#107): the server had been sending it all
 * along and no line here copied it across. The rule the file is written to is
 * that an older server *degrades* — it never crashes the screen and it never has
 * a value invented on its behalf — and that rule is only worth anything if
 * something checks it.
 *
 * <p>`today.lines` is the newest field held to it. It used to be a list of
 * Turkish strings and is now a list of `{source, text}`, so a server that has
 * not been redeployed yet is exactly the case that has to keep working.
 */

function payload(today: unknown): unknown {
  return {
    date: '2026-08-01T06:00:00Z',
    today,
    priority: [],
    inbox: { status: 'ok', items: [] },
    work: { status: 'ok', items: [] },
    code: { status: 'ok', items: [] },
    calendar: { status: 'ok', items: [] },
  };
}

it('a_counted_line_keeps_the_source_the_server_counted_it_from', () => {
  const brief = normalizeBrief(
    payload({
      headline: 'Bugün 3 iş seni bekliyor.',
      lines: [
        { source: 'gmail', text: '6 mail bir kişiden geldi' },
        { source: 'github', text: '3 PR ve issue sende' },
      ],
      highlights: [],
      counts: {},
    }),
  );

  expect(brief.today?.lines).toEqual([
    { source: 'gmail', text: '6 mail bir kişiden geldi' },
    { source: 'github', text: '3 PR ve issue sende' },
  ]);
});

/** The server that predates the field sent bare strings. They still read. */
it('an_older_server_that_sends_plain_strings_still_draws_its_lines', () => {
  const brief = normalizeBrief(
    payload({
      headline: 'Bugün 3 iş seni bekliyor.',
      lines: ['6 mail bir kişiden geldi', '  ', '3 PR ve issue sende'],
      highlights: [],
      counts: {},
    }),
  );

  // No source, so no mark — and no source guessed from the Turkish either,
  // which is the whole reason the field was added.
  expect(brief.today?.lines).toEqual([
    { source: null, text: '6 mail bir kişiden geldi' },
    { source: null, text: '3 PR ve issue sende' },
  ]);
});

/** A source outside the four the marks exist for is not a claim we can make. */
it('an_unknown_source_leaves_the_line_unmarked_instead_of_mismarked', () => {
  const brief = normalizeBrief(
    payload({
      headline: 'Bugün 1 iş seni bekliyor.',
      lines: [{ source: 'linear', text: '2 kayıt üstünde' }, { source: 'jira', text: '' }],
      highlights: [],
      counts: {},
    }),
  );

  expect(brief.today?.lines).toEqual([{ source: null, text: '2 kayıt üstünde' }]);
});

/** `stale` is the same rule one field over: silence means current, never "on its way". */
it('a_server_that_never_heard_of_stale_is_read_as_current', () => {
  expect(normalizeBrief(payload(null)).stale).toBe(false);
  expect(normalizeBrief(payload(null)).today).toBeNull();
});

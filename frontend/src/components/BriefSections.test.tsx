// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { PlaybookShelf } from './BriefSections';
import type { Playbook } from '../data/PlaybookSource';

/**
 * Why this file exists.
 *
 * <p>A chip on the shelf is the one press that starts a written-down flow, and
 * a flow ends at an approval gate for a write into somebody's Jira or Slack. So
 * what the chip claims about which apps it touches is not decoration — it is the
 * first thing the reader is told about a run they are about to start.
 *
 * <p>The claim has exactly one honest source: the flow's own steps. A table of
 * playbook id → icon would be correct until the day a step is edited and then
 * wrong silently, on this surface, which is the failure these tests exist to
 * make loud.
 */

function playbook(overrides: Partial<Playbook> = {}): Playbook {
  return {
    id: 'gunun-ozeti',
    title: 'Günün özeti',
    goal: 'goal',
    subtitle: 'Jira · GitHub okunur, ardından Slack mesajı onayına gelir',
    steps: [],
    runnable: true,
    missing: [],
    ...overrides,
  };
}

function shelf(rows: Playbook[]) {
  return render(
    <PlaybookShelf
      playbooks={rows}
      loading={false}
      error={null}
      starting={null}
      onRun={() => {}}
    />,
  );
}

/**
 * Each mark named by the colour it is drawn in — the one thing about these
 * logos BrandMark promises never to restyle. The brand SVGs are a solid `fill`;
 * Slack's is the icon set's line glyph, which fills nothing and strokes.
 */
function marks(): (string | null)[] {
  return [...document.querySelectorAll('.shelf__marks svg')].map((svg) => {
    const fill = svg.getAttribute('fill');
    return fill && fill !== 'none' ? fill : svg.getAttribute('stroke');
  });
}

afterEach(cleanup);

it('a_chips_marks_are_read_off_the_steps_it_will_actually_run', () => {
  shelf([
    playbook({
      steps: [
        { title: 'Kayıtları getir', tool: 'jira.listMyIssues', optional: false },
        { title: 'PR’ları getir', tool: 'github.listMyPullRequests', optional: true },
        { title: 'Ekibe yaz', tool: 'slack.postMessage', optional: false },
      ],
    }),
  ]);

  // In the order the work happens: read, read, then the write.
  expect(marks()).toEqual(['#2684FF', '#181717', '#611f69']);
});

/**
 * The point of deriving instead of tabulating. Edit a step and the chip follows;
 * a playbook-id → icon map would still be advertising Jira here.
 */
it('changing_a_step_changes_the_chip_that_advertises_it', () => {
  shelf([
    playbook({
      steps: [
        { title: 'Mailleri getir', tool: 'gmail.listMessages', optional: false },
        { title: 'Ekibe yaz', tool: 'slack.postMessage', optional: false },
      ],
    }),
  ]);

  expect(marks()).toEqual(['#EA4335', '#611f69']);
});

/** Two steps into the same system are one app, not two identical logos. */
it('two_steps_in_the_same_app_are_one_mark', () => {
  shelf([
    playbook({
      steps: [
        { title: 'Kayıtları ara', tool: 'jira.searchIssues', optional: false },
        { title: 'Kaydı güncelle', tool: 'jira.addComment', optional: false },
      ],
    }),
  ]);

  expect(marks()).toEqual(['#2684FF']);
});

/**
 * Three is what these flows touch — read, read, write — and a fourth would make
 * the chip wider than its own title on a shelf that is one wrapping row. The
 * extras are counted rather than dropped: understating what a flow is about to
 * touch is the one direction this surface must not fail in.
 */
it('a_flow_touching_more_apps_than_fit_counts_the_rest_instead_of_hiding_them', () => {
  shelf([
    playbook({
      steps: [
        { title: 'a', tool: 'jira.listMyIssues', optional: false },
        { title: 'b', tool: 'github.listMyPullRequests', optional: false },
        { title: 'c', tool: 'calendar.listEvents', optional: false },
        { title: 'd', tool: 'slack.postMessage', optional: false },
      ],
    }),
  ]);

  expect(marks()).toHaveLength(3);
  expect(screen.getByText('+1')).toBeTruthy();
});

/** No icon stands in for a source we did not actually count. */
it('a_step_whose_tool_has_no_mark_draws_no_mark', () => {
  shelf([
    playbook({
      steps: [
        { title: 'Notu yaz', tool: 'notes.append', optional: false },
        { title: 'Ekibe yaz', tool: 'slack.postMessage', optional: false },
      ],
    }),
  ]);

  expect(marks()).toEqual(['#611f69']);
});

/** A flow with no steps at all has nothing to advertise and draws no strip. */
it('a_flow_with_nothing_readable_in_its_steps_draws_no_strip_at_all', () => {
  shelf([playbook({ steps: [] })]);

  expect(document.querySelector('.shelf__marks')).toBeNull();
});

/**
 * The marks are silent to a screen reader on purpose: `subtitle` already writes
 * every provider out in words, and a logo repeating a word costs a listener a
 * beat and tells them nothing new.
 */
it('the_marks_add_nothing_to_what_the_chip_says_out_loud', () => {
  shelf([
    playbook({
      steps: [
        { title: 'a', tool: 'jira.listMyIssues', optional: false },
        { title: 'b', tool: 'slack.postMessage', optional: false },
      ],
    }),
  ]);

  const name = screen.getByRole('button').textContent ?? '';
  expect(name).toContain('Jira · GitHub okunur, ardından Slack mesajı onayına gelir');
  expect(document.querySelector('.shelf__marks')?.getAttribute('aria-hidden')).toBe('true');
});

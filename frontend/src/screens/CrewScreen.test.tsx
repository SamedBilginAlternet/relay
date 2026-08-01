// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Crew, CrewMember } from '../data/CrewSource';
import { parseHash } from '../lib/router';

/**
 * Why this file exists.
 *
 * <p>Ekip is the screen the "ajan ekibi" claim is made on, and the claim only
 * holds because nothing on it was written by hand (docs/EKIP.md §5.5). Two of
 * the ways it could quietly stop holding are invisible in review:
 *
 * <ul>
 *   <li>A member with no connection behind it gets dropped from the list, so
 *       the screen answers "which specialists do I have" with a shorter list
 *       every time a credential expires. Idle has to be <em>said</em>.
 *   <li>An id the interface has no Turkish word for gets one invented. The
 *       whole edge of this product is that it does not put a costume on
 *       something with nothing behind it, and a screen that renders
 *       `linear-agent` as "Linear Uzmanı" before a Linear tool exists is doing
 *       exactly that. (This used to be written with Notion, until Notion became
 *       a provider Relay actually ships — which is the whole point of picking
 *       the stand-in from the products it does not have.)
 * </ul>
 *
 * <p>The third test is the one the design turns on: the authority line is
 * counted from the tools, not stored beside the member (§7.5).
 *
 * <p>The tabs (#159) put a fourth way on the list, and it is the same failure
 * wearing a control: a strip built from a list somebody typed here answers
 * "which specialists do I have" with the specialists that were shipped, not the
 * ones the registry holds. So the tests below press on where the tabs come from,
 * and on what a provider Relay owns no mark for is allowed to look like.
 */

let crew: Crew = { core: [], members: [] };

vi.mock('../data/CrewSource', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../data/CrewSource')>();
  return { ...actual, getCrewSource: () => ({ crew: async () => crew }) };
});

const { CrewScreen } = await import('./CrewScreen');

function member(overrides: Partial<CrewMember> = {}): CrewMember {
  return {
    id: 'jira-agent',
    provider: 'jira',
    connectionProvider: 'jira',
    connected: true,
    toolCount: 2,
    auto: 1,
    ask: 1,
    forbidden: 0,
    tier: 'large',
    tools: [
      { name: 'jira.searchIssues', risk: 'read', mode: 'auto', overridden: false },
      { name: 'jira.createIssue', risk: 'write', mode: 'ask', overridden: false },
    ],
    ...overrides,
  };
}

/** The row a member is drawn in, found by the name printed in it. */
async function rowOf(label: string): Promise<HTMLElement> {
  return (await screen.findByText(label)).closest('li') as HTMLElement;
}

/** The five that exist whether or not a single tool is connected. */
const CORE: Crew['core'] = [
  { id: 'planner', purpose: 'plan', tier: 'large' },
  { id: 'coordinator', purpose: null, tier: null },
];

/** Press a tab and wait for the panel under it to be redrawn. */
async function openTab(name: RegExp): Promise<void> {
  screen.getByRole('tab', { name }).click();
  await waitFor(() =>
    expect(screen.getByRole('tab', { name }).getAttribute('aria-selected')).toBe('true'),
  );
}

beforeEach(() => {
  window.location.hash = '#/ekip';
  crew = { core: [], members: [] };
});

afterEach(cleanup);

it('a_member_with_no_connection_is_listed_and_says_it_is_idle', async () => {
  crew = {
    core: [],
    members: [
      member(),
      member({
        id: 'gmail-agent',
        provider: 'gmail',
        connectionProvider: 'google',
        connected: false,
        toolCount: 1,
        auto: 1,
        ask: 0,
        tools: [{ name: 'gmail.listToday', risk: 'read', mode: 'auto', overridden: false }],
      }),
    ],
  };

  render(<CrewScreen />);
  const idle = await rowOf('Gmail Uzmanı');

  expect(within(idle).getByText(/Google bağlantısı yok/)).toBeTruthy();
  expect(within(idle).getByText(/boşta/)).toBeTruthy();
  // Idle is not hidden and not empty: the tools it holds are still on screen.
  expect(within(idle).getByText('gmail.listToday')).toBeTruthy();
  expect(within(await rowOf('Jira Uzmanı')).queryByText(/bağlantısı yok/)).toBeNull();
});

it('a_member_id_with_no_turkish_name_is_printed_exactly_as_it_arrived', async () => {
  crew = {
    core: [],
    members: [
      member({
        id: 'linear-agent',
        provider: 'linear',
        connectionProvider: 'linear',
        connected: false,
        toolCount: 1,
        auto: 0,
        ask: 1,
        tools: [{ name: 'linear.createIssue', risk: 'write', mode: 'ask', overridden: false }],
      }),
    ],
  };

  render(<CrewScreen />);

  expect(await screen.findByText('linear-agent')).toBeTruthy();
  expect(screen.queryByText(/Linear Uzmanı/)).toBeNull();
});

it('the_authority_line_is_counted_from_the_tools_the_member_holds', async () => {
  crew = { core: [], members: [member({ toolCount: 3, auto: 2, ask: 1, forbidden: 0 })] };

  render(<CrewScreen />);
  const row = await rowOf('Jira Uzmanı');

  expect(within(row).getByText('3 araç')).toBeTruthy();
  expect(within(row).getByText(/2 otomatik/)).toBeTruthy();
  expect(within(row).getByText(/1 onay ister/)).toBeTruthy();
  // Nothing forbidden, so no forbidden count is claimed.
  expect(within(row).queryByText(/yasak/)).toBeNull();
});

it('a_forbidden_tool_is_shown_as_forbidden_rather_than_dropped', async () => {
  crew = {
    core: [],
    members: [
      member({
        toolCount: 2,
        auto: 1,
        ask: 0,
        forbidden: 1,
        tools: [
          { name: 'jira.searchIssues', risk: 'read', mode: 'auto', overridden: false },
          { name: 'jira.createIssue', risk: 'write', mode: 'forbidden', overridden: true },
        ],
      }),
    ],
  };

  render(<CrewScreen />);
  const row = await rowOf('Jira Uzmanı');

  expect(within(row).getByText(/1 yasak/)).toBeTruthy();
  expect(within(row).getByText('jira.createIssue')).toBeTruthy();
});

it('an_empty_registry_says_why_there_are_no_members_instead_of_a_blank_frame', async () => {
  crew = { core: [{ id: 'planner', purpose: 'plan', tier: 'large' }], members: [] };

  render(<CrewScreen />);

  expect(await screen.findByText(/Kayıtlı araç yok/)).toBeTruthy();
  // The core is not derived from tools, so it is still there — one tab over,
  // which is the only thing #159 changed about it.
  await openTab(/Sabit çekirdek/);
  expect(screen.getByText('Planlayıcı')).toBeTruthy();
});

it('the_core_member_that_never_calls_a_model_says_so_instead_of_naming_a_tier', async () => {
  crew = {
    core: [
      { id: 'verifier', purpose: 'verify', tier: 'small' },
      { id: 'policy', purpose: null, tier: null },
    ],
    members: [],
  };

  render(<CrewScreen />);
  await screen.findByRole('tab', { name: /Sabit çekirdek/ });
  await openTab(/Sabit çekirdek/);

  expect(within(await rowOf('Doğrulayıcı')).getByText('küçük model')).toBeTruthy();
  expect(within(await rowOf('Politika')).getByText('model çağırmaz')).toBeTruthy();
});

/* ---- the tabs (#159) ------------------------------------------------- */

/**
 * The one that matters. A strip written out here would show the five providers
 * that existed the day it was written, and the crew is the tools — so a screen
 * with a hand-written strip would answer "who is on this team" by leaving out
 * whoever arrived since.
 */
it('a_provider_the_registry_returned_gets_a_tab_nobody_wrote_down', async () => {
  crew = {
    core: [],
    members: [
      member(),
      member({
        id: 'linear-agent',
        provider: 'linear',
        connectionProvider: 'linear',
        connected: false,
        toolCount: 1,
        auto: 0,
        ask: 1,
        tools: [{ name: 'linear.createIssue', risk: 'write', mode: 'ask', overridden: false }],
      }),
    ],
  };

  render(<CrewScreen />);

  const linear = await screen.findByRole('tab', { name: /linear/ });
  // No Turkish word for it and no mark we own: the id, and nothing drawn beside it.
  expect(linear.textContent).toContain('linear');
  expect(linear.querySelector('svg')).toBeNull();
  expect(screen.getByRole('tab', { name: /Jira/ }).querySelector('svg')).toBeTruthy();
  // And no tab for a provider that has no registered tool.
  expect(screen.queryByRole('tab', { name: /Slack/ })).toBeNull();
});

it('tumu_lists_every_provider_and_a_provider_tab_lists_only_its_own', async () => {
  crew = {
    core: CORE,
    members: [
      member(),
      member({
        id: 'slack-agent',
        provider: 'slack',
        connectionProvider: 'slack',
        toolCount: 1,
        auto: 0,
        ask: 1,
        tools: [{ name: 'slack.postMessage', risk: 'write', mode: 'ask', overridden: false }],
      }),
    ],
  };

  render(<CrewScreen />);

  expect(await screen.findByText('Jira Uzmanı')).toBeTruthy();
  expect(screen.getByText('Slack Uzmanı')).toBeTruthy();

  await openTab(/Slack/);
  await waitFor(() => expect(screen.queryByText('Jira Uzmanı')).toBeNull());
  expect(screen.getByText('Slack Uzmanı')).toBeTruthy();
  expect(window.location.hash).toBe('#/ekip?saglayici=slack');
});

/**
 * The count is the authority the tab stands in front of, so it has to be the
 * tools. The fixed core holds none, and a `0` printed next to it would be a
 * claim about a group that is not measured in tools at all.
 */
it('a_tab_counts_the_tools_behind_it_and_the_toolless_core_carries_no_number', async () => {
  crew = { core: CORE, members: [member({ toolCount: 2 })] };

  render(<CrewScreen />);

  await waitFor(() => expect(screen.getByRole('tab', { name: /Tümü/ }).textContent).toBe('Tümü2'));
  expect(screen.getByRole('tab', { name: /Jira/ }).textContent).toBe('Jira2');
  expect(screen.getByRole('tab', { name: /Sabit çekirdek/ }).textContent).toBe('Sabit çekirdek');
});

/**
 * The rule block itself was removed at the owner's request (#164) — a paragraph of
 * governance prose on a screen people visit to see who does what. The FACT it stated
 * still holds and is still enforced: membership derives from the tool registry alone.
 * What remains on screen saying so is the section head's own "araç kayıt defterinden"
 * caption; what remains in the product is that there is simply no way to add a member.
 * This test holds the removal — the paragraph must not creep back as a fifth block.
 */
it('the_governance_paragraph_stays_gone', async () => {
  crew = { core: CORE, members: [member()] };

  render(<CrewScreen />);

  await screen.findByRole('tab', { name: /Tümü/ });
  expect(screen.queryByText(/Bu listeye elle üye eklenemez/)).toBeNull();
});

/** `#/ekip` is the address the nav points at; nothing else may swallow it. */
it('the_ekip_hash_parses_as_the_crew_route', () => {
  expect(parseHash('#/ekip')).toEqual({ name: 'crew' });
  expect(parseHash('#/ekip?saglayici=jira')).toEqual({ name: 'crew' });
  expect(parseHash('#/politikalar')).toEqual({ name: 'policies' });
});

it('a_provider_in_the_address_that_the_registry_no_longer_has_falls_back_to_everyone', async () => {
  window.location.hash = '#/ekip?saglayici=linear';
  crew = { core: [], members: [member()] };

  render(<CrewScreen />);

  // The Linear tool is gone; the address is stale, and an empty frame under a
  // tab that is not on screen is not an answer.
  expect(await screen.findByText('Jira Uzmanı')).toBeTruthy();
  expect(screen.getByRole('tab', { name: /Tümü/ }).getAttribute('aria-selected')).toBe('true');
});

it('the_tab_in_the_address_is_the_tab_that_opens', async () => {
  window.location.hash = '#/ekip?saglayici=cekirdek';
  crew = { core: CORE, members: [member()] };

  render(<CrewScreen />);

  expect(await screen.findByText('Planlayıcı')).toBeTruthy();
  expect(screen.queryByText('Jira Uzmanı')).toBeNull();
});

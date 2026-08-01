// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { Connection } from '../types/api';

/**
 * Why this file exists.
 *
 * <p>Bağlantılar drew four providers as four full-width cards with their forms held open —
 * about 2100px on a 900px screen. The damage was not the scrolling: the question the screen
 * exists to answer, "which of these am I connected to", was four status pills scattered
 * through four forms, and answering it took a scroll (#134).
 *
 * <p>The fix is a state machine with exactly two states, and both halves of it are one
 * `&&` away from being undone: a grid that shows no inputs at all, and a setup view that
 * shows one provider's inputs and no other's. These tests hold both. The last one holds the
 * reason the grid exists — that "Bağlı" is legible before anything has been opened.
 */

const connections: Connection[] = [];
const getConnections = vi.fn(async () => connections);

vi.mock('../data', () => ({
  API_BASE_URL: '/api',
  getRunSource: () => ({
    getConnections,
    getGoogleStatus: async () => ({ configured: true, connected: false, redirectUri: null }),
    saveConnection: async () => {
      throw new Error('not used');
    },
    testConnection: async () => {
      throw new Error('not used');
    },
  }),
}));

const { ConnectionsScreen } = await import('./ConnectionsScreen');

afterEach(() => {
  cleanup();
  connections.length = 0;
});

it('the_grid_names_every_service_without_opening_a_single_form', async () => {
  render(<ConnectionsScreen />);

  await screen.findByRole('article', { name: 'Jira bağlantısı' });
  for (const name of ['Google', 'Jira', 'GitHub', 'Slack']) {
    expect(screen.getByRole('article', { name: `${name} bağlantısı` })).toBeTruthy();
  }
  // The whole point of the grid: four services, zero inputs.
  expect(screen.queryAllByRole('textbox')).toHaveLength(0);
});

it('opening_one_service_leaves_every_other_services_fields_off_the_screen', async () => {
  render(<ConnectionsScreen />);

  const jira = await screen.findByRole('article', { name: 'Jira bağlantısı' });
  const open = jira.querySelector('button');
  expect(open).toBeTruthy();
  open!.click();

  await waitFor(() => expect(screen.getByText('Site adresi')).toBeTruthy());
  expect(screen.getByText('API token')).toBeTruthy();
  // GitHub's only secret field. On the old screen it was on the page at the same time.
  expect(screen.queryByText('Personal access token')).toBeNull();
  expect(screen.queryByRole('article', { name: 'Slack bağlantısı' })).toBeNull();
});

it('a_stored_connection_reads_as_connected_before_anything_is_opened', async () => {
  connections.push({
    provider: 'jira',
    configured: true,
    config: { baseUrl: 'https://x.atlassian.net' },
    updatedAt: new Date().toISOString(),
  });

  render(<ConnectionsScreen />);

  const jira = await screen.findByRole('article', { name: 'Jira bağlantısı' });
  // The word, not the dot — colour is never the only thing carrying the state.
  expect(jira.textContent).toContain('Bağlı');
  expect(jira.textContent).toContain('Yönet');

  const slack = screen.getByRole('article', { name: 'Slack bağlantısı' });
  expect(slack.textContent).toContain('Bağlı değil');
  expect(slack.textContent).toContain('Bağlan');
});

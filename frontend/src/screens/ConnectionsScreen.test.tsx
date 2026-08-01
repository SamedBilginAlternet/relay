// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
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
 *
 * <p>The setup view later became a dialog OVER the grid instead of a screen in place of
 * it. The two-state claim survives — one provider's inputs at a time, hidden from the
 * accessibility tree behind `aria-hidden` when the dialog owns the screen — and four new
 * claims join it: focus moves into the dialog, Escape closes a clean form, a dirty form is
 * asked before its typed keys are thrown away (`sheet-dismiss-confirm`), and the grid was
 * mounted behind the scrim the whole time.
 */

const connections: Connection[] = [];
const getConnections = vi.fn(async () => connections);
const saveConnection = vi.fn(
  async (provider: string, config: Record<string, string>): Promise<Connection> => ({
    provider: provider as Connection['provider'],
    configured: true,
    config,
    updatedAt: new Date().toISOString(),
  }),
);

vi.mock('../data', () => ({
  API_BASE_URL: '/api',
  getRunSource: () => ({
    getConnections,
    getGoogleStatus: async () => ({ configured: true, connected: false, redirectUri: null }),
    saveConnection,
    testConnection: async () => {
      throw new Error('not used');
    },
  }),
}));

const { ConnectionsScreen } = await import('./ConnectionsScreen');

afterEach(() => {
  cleanup();
  connections.length = 0;
  saveConnection.mockClear();
});

/** Open one provider's setup dialog from its tile and hand the dialog back. */
async function openSetup(name: string, title: string): Promise<HTMLElement> {
  const tile = await screen.findByRole('article', { name });
  const open = tile.querySelector('button');
  expect(open).toBeTruthy();
  fireEvent.click(open!);
  return await screen.findByRole('dialog', { name: title });
}

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

it('the_setup_is_a_dialog_over_the_grid_and_the_grid_is_still_mounted_behind_it', async () => {
  render(<ConnectionsScreen />);

  const dialog = await openSetup('Jira bağlantısı', 'Jira');
  expect(dialog.getAttribute('aria-modal')).toBe('true');

  // The grid did not unmount — it left the accessibility tree and nothing else.
  // `hidden: true` is what finds an element sitting under aria-hidden.
  expect(screen.getByRole('article', { name: 'Slack bağlantısı', hidden: true })).toBeTruthy();
  expect(screen.getByRole('article', { name: 'Jira bağlantısı', hidden: true })).toBeTruthy();
});

it('focus_moves_into_the_dialog_the_moment_it_opens', async () => {
  render(<ConnectionsScreen />);

  const dialog = await openSetup('Jira bağlantısı', 'Jira');
  await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
});

it('escape_on_a_clean_form_closes_without_a_question', async () => {
  render(<ConnectionsScreen />);

  await openSetup('Jira bağlantısı', 'Jira');
  fireEvent.keyDown(document, { key: 'Escape' });

  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  // The grid is audible again, exactly as it was.
  expect(screen.getByRole('article', { name: 'Jira bağlantısı' })).toBeTruthy();
  expect(screen.queryAllByRole('textbox')).toHaveLength(0);
});

it('a_dirty_form_is_asked_before_its_typed_keys_are_thrown_away', async () => {
  render(<ConnectionsScreen />);

  await openSetup('Jira bağlantısı', 'Jira');
  const input = screen.getByPlaceholderText('https://sirket.atlassian.net');
  fireEvent.change(input, { target: { value: 'https://yeni.atlassian.net' } });

  // Escape does not close: it asks, inside the dialog, in the product's words.
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(await screen.findByText('Kaydedilmemiş değişiklik var.')).toBeTruthy();
  expect(screen.getByRole('dialog', { name: 'Jira' })).toBeTruthy();

  // "Geri dön" withdraws the question and loses nothing.
  fireEvent.click(screen.getByRole('button', { name: 'Geri dön' }));
  expect(screen.queryByText('Kaydedilmemiş değişiklik var.')).toBeNull();
  expect((input as HTMLInputElement).value).toBe('https://yeni.atlassian.net');

  // Asked again, the discard is one explicit press — never a silent side effect.
  fireEvent.keyDown(document, { key: 'Escape' });
  fireEvent.click(await screen.findByRole('button', { name: 'Kaydetmeden kapat' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

it('save_sends_only_what_was_typed_and_never_the_masked_token', async () => {
  connections.push({
    provider: 'jira',
    configured: true,
    config: { baseUrl: 'https://x.atlassian.net', apiToken: '****oken' },
    updatedAt: new Date().toISOString(),
  });

  render(<ConnectionsScreen />);

  await openSetup('Jira bağlantısı', 'Jira');
  fireEvent.change(screen.getByPlaceholderText('RUN'), { target: { value: 'REL' } });
  fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }));

  // One typed field, one key in the payload: the stored baseUrl and the masked
  // apiToken stay on the server, where they already are.
  await waitFor(() => expect(saveConnection).toHaveBeenCalledTimes(1));
  expect(saveConnection).toHaveBeenCalledWith('jira', { projectKey: 'REL' });
});

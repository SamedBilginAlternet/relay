import { describe, expect, it } from 'vitest';
import { agentLabel } from './agents';

/**
 * Why this test exists.
 *
 * Sohbet's left column is the one place the "you can read who told whom what"
 * half of the pitch is shown. For a while it read in Turkish under
 * `VITE_RUN_SOURCE=mock` and in English service ids under `api` — `coordinator →
 * jira-agent`, `verifier → coordinator` — because the Turkish names lived in the
 * mock script rather than in the interface. Whoever ran the demo saw a finished
 * screen; whoever ran the product saw the internals (#97).
 *
 * Deleting these assertions means one of two things came back: a live run
 * printing backend role ids at a jury, or — the worse one — an unknown id being
 * dressed up in an invented Turkish name, so the screen names an agent that does
 * not exist under that name anywhere else in the system.
 */
describe('agentLabel', () => {
  it('names every agent the backend can put on the wire', () => {
    expect(agentLabel('user')).toBe('Sen');
    expect(agentLabel('planner')).toBe('Planlayıcı');
    expect(agentLabel('coordinator')).toBe('Koordinatör');
    expect(agentLabel('verifier')).toBe('Doğrulayıcı');
    expect(agentLabel('policy')).toBe('Politika');
    expect(agentLabel('cost')).toBe('Maliyet');

    expect(agentLabel('jira-agent')).toBe('Jira Uzmanı');
    expect(agentLabel('slack-agent')).toBe('Slack Uzmanı');
    expect(agentLabel('gmail-agent')).toBe('Gmail Uzmanı');
    expect(agentLabel('github-agent')).toBe('GitHub Uzmanı');
    expect(agentLabel('generalist-agent')).toBe('Genel Uzman');
  });

  it('an_unknown_agent_id_is_shown_as_is', () => {
    // A tool agent we have no name for keeps its id rather than borrowing one.
    expect(agentLabel('linear-agent')).toBe('linear-agent');
    expect(agentLabel('supervisor')).toBe('supervisor');
    // `applyEvent` writes this when the event carries no sender at all.
    expect(agentLabel('unknown')).toBe('unknown');
  });

  it('the_mock_and_the_api_produce_the_same_names', () => {
    // The mock script writes the Turkish name into the message itself; the API
    // writes the role id. Both have to leave the mapping saying the same thing.
    expect(agentLabel('coordinator')).toBe(agentLabel('Koordinatör'));
    expect(agentLabel('jira-agent')).toBe(agentLabel('Jira Uzmanı'));
    expect(agentLabel('verifier')).toBe(agentLabel('Doğrulayıcı'));
  });
});


import { describe, expect, it } from 'vitest';
import { arrivalLink } from './sourceLinks';

/**
 * Why this file exists.
 *
 * <p>"3 PR ve issue sende" is a count of things that live somewhere else, and
 * until now the rail counted them and left the reader to find the page
 * themselves. Adding the link is easy; adding an *honest* one is the whole
 * problem, because for three of the four providers the address is partly the
 * user's own — which Atlassian site their company runs, which of two signed-in
 * Google accounts the mailbox belongs to — and none of that can be guessed from
 * the client.
 *
 * <p>So these tests hold the line between derived and invented. A destination
 * read out of a real item URL is a fact; a Jira URL made up because a Jira line
 * was present is a promise about someone's company that lands on a 404, and the
 * product would rather draw no link at all.
 */

describe('jira', () => {
  it('the_customers_own_site_is_read_from_a_real_issue_url', () => {
    const link = arrivalLink('jira', 'https://acme.atlassian.net/browse/KAN-42');

    expect(link?.href).toBe(
      'https://acme.atlassian.net/issues/?jql=assignee%20%3D%20currentUser()%20AND%20resolution%20%3D%20Unresolved',
    );
    expect(link?.openLabel).toBe('Jira’da aç');
  });

  /**
   * A Jira behind a context path is a real deployment. Taking the origin would
   * drop `/jira` and land on the host's root, which is not the reader's issues.
   */
  it('a_jira_behind_a_context_path_keeps_that_path', () => {
    const link = arrivalLink('jira', 'https://jira.acme.com/jira/browse/KAN-42');

    expect(link?.href).toContain('https://jira.acme.com/jira/issues/?jql=');
  });

  /**
   * The one case the whole module exists for. There is no such thing as "the"
   * Jira: without an issue URL the site is unknown, and a plausible guess is
   * worse than nothing, so nothing is what gets drawn.
   */
  it('with_no_issue_to_read_the_site_from_there_is_no_link_at_all', () => {
    expect(arrivalLink('jira', null)).toBeNull();
    expect(arrivalLink('jira', '')).toBeNull();
    // A URL that is not an issue page says nothing about where the issues are.
    expect(arrivalLink('jira', 'https://acme.atlassian.net/')).toBeNull();
  });
});

describe('gmail', () => {
  /** Two signed-in accounts: `u/0` is the wrong mailbox for half of them. */
  it('the_account_the_mail_arrived_in_is_the_account_the_link_opens', () => {
    const link = arrivalLink('gmail', 'https://mail.google.com/mail/u/2/#inbox/18f2ab');

    expect(link?.href).toBe('https://mail.google.com/mail/u/2/#inbox');
    expect(link?.openLabel).toBe('Gmail’de aç');
  });

  /** One message is not what a line counting six of them is about. */
  it('the_link_lands_on_the_mailbox_and_not_on_one_message', () => {
    expect(arrivalLink('gmail', 'https://mail.google.com/mail/u/0/#inbox/18f2ab')?.href).toBe(
      'https://mail.google.com/mail/u/0/#inbox',
    );
  });

  /** Gmail is one product at one address; the front door is a fact, not a guess. */
  it('with_no_mail_to_read_the_account_from_it_falls_back_to_gmails_own_door', () => {
    expect(arrivalLink('gmail', null)?.href).toBe('https://mail.google.com/');
  });
});

describe('github', () => {
  it('the_host_the_pull_requests_came_from_is_the_host_the_link_opens', () => {
    expect(arrivalLink('github', 'https://github.com/acme/pay/pull/128')?.href).toBe(
      'https://github.com/pulls',
    );
    expect(arrivalLink('github', 'https://git.acme.com/acme/pay/pull/128')?.href).toBe(
      'https://git.acme.com/pulls',
    );
  });

  it('with_no_pull_request_to_read_the_host_from_it_falls_back_to_github_com', () => {
    expect(arrivalLink('github', null)?.href).toBe('https://github.com/pulls');
  });
});

/**
 * Calendar is the honest exception. Google hands back an event link on
 * www.google.com, and no list page can be cut out of it — deriving the origin
 * would send the reader to Google's home page. So the address is a constant, and
 * a real event URL changes nothing about it.
 */
describe('calendar', () => {
  it('an_event_url_on_google_com_is_never_mistaken_for_a_calendar_address', () => {
    const derived = arrivalLink('calendar', 'https://www.google.com/calendar/event?eid=abc');

    expect(derived?.href).toBe('https://calendar.google.com/');
    expect(arrivalLink('calendar', null)?.href).toBe('https://calendar.google.com/');
  });
});

/** A line the server did not name a source for has no destination to offer. */
it('a_line_with_no_source_gets_no_link', () => {
  expect(arrivalLink(null, 'https://mail.google.com/mail/u/0/#inbox/1')).toBeNull();
});

/** An item URL is data from a provider, and data is not trusted to be a scheme. */
it('a_url_that_is_not_http_is_not_a_destination', () => {
  expect(arrivalLink('jira', 'javascript:alert(1)')).toBeNull();
  expect(arrivalLink('gmail', 'not a url')?.href).toBe('https://mail.google.com/');
});

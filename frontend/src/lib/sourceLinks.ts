import type { BriefHighlightSource } from '../types/brief';

/**
 * Where an arrival line goes when it is pressed.
 *
 * <p>The rail counts what came in — "6 mail bir kişiden geldi", "3 PR ve issue
 * sende" — and every one of those counts has a page somewhere that shows the
 * things themselves. The screen knows which provider each line was counted from
 * (the server says so, see `DayTally.Line`), so the only open question is what
 * URL that provider's list actually is for *this* user.
 *
 * <p>Two kinds of answer, and the difference matters:
 *
 * <ul>
 *   <li><b>Derived</b> — read out of a real {@code BriefItem.url} the same
 *       section already carries. A Jira item's URL contains the customer's own
 *       Atlassian site, which nothing else in the client can know; a Gmail URL
 *       contains which signed-in account the mailbox belongs to.</li>
 *   <li><b>Constant</b> — the provider's own global page, used only where the
 *       product genuinely has one address for everybody. Gmail, Google Calendar
 *       and github.com are single sites; a Jira is not.</li>
 * </ul>
 *
 * <p>And where neither is available the answer is null and no link is drawn. A
 * plausible-looking Jira URL is worse than no link: it is a promise about the
 * user's own company that lands on someone else's 404.
 */
export type ArrivalLink = {
  href: string;
  /**
   * "Gmail'de aç" — the tail of the link's accessible name, so it says where it
   * lands and not only what it counts. The suffix is written per provider
   * rather than generated: Turkish vowel harmony wants -de after Gmail and -da
   * after GitHub, and one generated rule gets one of them wrong.
   */
  openLabel: string;
};

const OPEN_LABEL: Record<BriefHighlightSource, string> = {
  gmail: 'Gmail’de aç',
  jira: 'Jira’da aç',
  github: 'GitHub’da aç',
  calendar: 'Google Calendar’da aç',
};

/** The scheme is checked, not assumed: a `javascript:` URL is not a destination. */
function httpUrl(raw: string | null | undefined): URL | null {
  if (!raw) return null;
  try {
    const url = new URL(raw);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url : null;
  } catch {
    return null;
  }
}

/**
 * `…/mail/u/2/#inbox/18f2…` → `…/mail/u/2/#inbox`.
 *
 * The account index is the part worth deriving: a person signed into two Google
 * accounts opens `u/0` and finds the wrong mailbox. Everything after `#inbox/`
 * is one message, which is not what a line counting six of them is about.
 */
function gmailHref(url: URL | null): string {
  const match = url && /^\/mail\/u\/(\d+)\//.exec(url.pathname);
  if (url && match) return `${url.origin}/mail/u/${match[1]}/#inbox`;
  // Gmail's own front door. It is one address for every user of the product and
  // resolves to whichever account the browser is signed into — the same thing
  // the derived link says, minus the account we could not read.
  return 'https://mail.google.com/';
}

/**
 * `https://acme.atlassian.net/browse/KAN-42` → the issue navigator on the same
 * site, filtered to what is open and assigned to the reader — which is exactly
 * what the line counted.
 *
 * The base is taken by removing `/browse/KEY`, not by taking the origin: a Jira
 * behind a context path (`https://jira.acme.com/jira/browse/KAN-42`) would lose
 * that path and land on the host's root.
 */
const MY_OPEN_ISSUES = 'assignee = currentUser() AND resolution = Unresolved';

function jiraHref(url: URL | null): string | null {
  if (!url) return null;
  const match = /^(.*)\/browse\/[^/]+$/.exec(url.pathname);
  if (!match) return null;
  return `${url.origin}${match[1]}/issues/?jql=${encodeURIComponent(MY_OPEN_ISSUES)}`;
}

/**
 * The page an arrival line points at, or null when there is nothing honest to
 * point at.
 *
 * @param source   which provider the server counted the line from
 * @param itemUrl  the deep link of a real item in that same section, when the
 *                 section has one. This is the only evidence about *which*
 *                 Jira, *which* mailbox — there is no other source for it.
 */
export function arrivalLink(
  source: BriefHighlightSource | null,
  itemUrl: string | null | undefined,
): ArrivalLink | null {
  if (!source) return null;
  const url = httpUrl(itemUrl);
  const href =
    source === 'gmail'
      ? gmailHref(url)
      : source === 'jira'
        ? jiraHref(url)
        : source === 'github'
          ? // github.com/pulls is "pull requests that involve you", the page the
            // line is a count of. The origin is derived so an item that came
            // from somewhere else keeps its host.
            `${url?.origin ?? 'https://github.com'}/pulls`
          : // Calendar is a constant with no derived form at all. Google hands
            // back an event link on www.google.com, and no calendar list page
            // can be cut out of it — the origin would send the reader to
            // Google's home page. calendar.google.com is the product's own
            // address and opens on today, which is the day being counted.
            'https://calendar.google.com/';
  return href ? { href, openLabel: OPEN_LABEL[source] } : null;
}

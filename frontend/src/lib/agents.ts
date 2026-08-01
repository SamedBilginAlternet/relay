/**
 * Turkish names for the agents whose traffic the Sohbet screen prints.
 *
 * The left column of Sohbet is where the product's second claim is made — "every
 * step is visible, who told whom what is readable". It was making that claim in
 * two different languages depending on where the run came from: the mock source
 * wrote `Koordinatör` and `Doğrulayıcı` into the message itself, while a live run
 * carried the backend's `AgentRole` values (`coordinator`, `verifier`,
 * `jira-agent`) straight to the screen. So the demo read in Turkish and the real
 * thing — the one a jury would be shown — read in English service ids.
 *
 * Presentation language is the interface's job, not the API's: `AgentRole` is a
 * domain concept and translating it in the backend would point the dependency
 * the wrong way. Hence a mapping here, applied to both sources.
 *
 * The rule about the ones we do not know is the same one `paramLabels.ts`
 * follows, and for the same reason: **an unrecognised id is printed unchanged.**
 * A new agent will be added one day, and a screen that shows `notion-agent`
 * tells the truth, while a screen that guesses a Turkish word for it does not.
 */
const ROLES: Record<string, string> = {
  user: 'Sen',
  planner: 'Planlayıcı',
  coordinator: 'Koordinatör',
  verifier: 'Doğrulayıcı',
  policy: 'Politika',
  cost: 'Maliyet',
};

/** `AgentRole.toolAgent(name)` on the backend builds these as `<name>-agent`. */
const TOOL_AGENTS: Record<string, string> = {
  jira: 'Jira Uzmanı',
  slack: 'Slack Uzmanı',
  gmail: 'Gmail Uzmanı',
  github: 'GitHub Uzmanı',
  // Takvim is a registered provider like the other four; leaving it out meant the
  // Ekip screen printed `calendar-agent` raw — correct by this file's own rule, and
  // wrong about a specialist the product actually ships.
  calendar: 'Takvim Uzmanı',
  notion: 'Notion Uzmanı',
  // sheets.* is its own tool namespace even though it rides the google connection, so
  // AgentRole builds `sheets-agent` and Ekip would otherwise print it raw.
  sheets: 'Tablo Uzmanı',
  // Same shape on the Atlassian side: confluence.* rides the jira connection, and the
  // brand carries the recognition the way Notion's does — hence not "Doküman Uzmanı".
  confluence: 'Confluence Uzmanı',
  generalist: 'Genel Uzman',
};

const TOOL_SUFFIX = '-agent';

/** A name a person reads, or the raw id exactly as it arrived. */
export function agentLabel(id: string): string {
  const key = id.trim().toLowerCase();

  const role = ROLES[key];
  if (role) return role;

  if (key.endsWith(TOOL_SUFFIX)) {
    const tool = TOOL_AGENTS[key.slice(0, -TOOL_SUFFIX.length)];
    if (tool) return tool;
  }

  return id;
}


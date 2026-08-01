import { NotebookText, Slack } from 'lucide-react';

/**
 * The providers' own marks, so a tool call is recognised before it is read.
 *
 * <p>Every screen that names a tool — the day's rows, the flow panel, the audit
 * trail, Politikalar, Bağlantılar — said "jira.createIssue" in mono type and
 * left the reader to parse it. A logo is read in a glance and a tool id is read
 * in a beat, and on the approval gate that beat is the difference between
 * seeing where a write is going and reading that it is going somewhere.
 *
 * <p>Inlined, not fetched: a page that reaches out for a logo can render
 * without one, and these appear next to the sentence that says what is about to
 * be written.
 *
 * <p>The marks are the providers' trademarks, used to refer to the products
 * Relay connects to — the referential use their brand guidelines allow. They
 * are drawn at their own proportions in their own colour and are never
 * restyled, and Relay does not present itself as endorsed by any of them.
 * Slack's mark is not redistributable in this form, so Slack is drawn with the
 * line glyph the icon set already ships, in Slack's own aubergine.
 *
 * <p>Notion is drawn the same way, and the reason is worth writing down because
 * it looks at first like the opposite case: the Notion mark is monochrome, so
 * unlike Slack's it would survive being reduced to one path. What it does not
 * survive is the check. The four marks above are here on the referential use
 * their published brand guidelines grant; Notion's brand page sits behind a
 * login (`notion.com/brand` → 401), so that grant cannot be read, and a
 * trademark you cannot read the terms for is not one to ship in a product UI on
 * the assumption they say what the others say. `NotebookText` in Notion's own
 * near-black says "the notes app" without borrowing anybody's mark, and the day
 * the guidelines can be read this is a five-line change.
 */

export type Provider = 'jira' | 'confluence' | 'github' | 'gmail' | 'calendar' | 'slack' | 'notion';

/** The providers drawn with a line glyph instead of their own mark — see above. */
const GLYPHS: Record<'slack' | 'notion', { icon: typeof Slack; color: string; title: string }> = {
  slack: { icon: Slack, color: '#611f69', title: 'Slack' },
  notion: { icon: NotebookText, color: '#191919', title: 'Notion' },
};

const MARKS: Record<Exclude<Provider, keyof typeof GLYPHS>, { d: string; color: string; title: string }> = {
  jira: {
    d: 'M11.571 11.513H0a5.218 5.218 0 0 0 5.232 5.215h2.13v2.057A5.215 5.215 0 0 0 12.575 24V12.518a1.005 1.005 0 0 0-1.005-1.005zm5.723-5.756H5.736a5.215 5.215 0 0 0 5.215 5.214h2.129v2.058a5.218 5.218 0 0 0 5.215 5.214V6.758a1.001 1.001 0 0 0-1.001-1.001zM23.013 0H11.455a5.215 5.215 0 0 0 5.215 5.215h2.129v2.057A5.215 5.215 0 0 0 24 12.483V1.005A1.001 1.001 0 0 0 23.013 0Z',
    color: '#2684FF',
    title: 'Jira',
  },
  // Same publisher as the Jira mark above, so the check that admitted that one admits this
  // one: Atlassian's trademark guidelines are public and grant exactly this referential use.
  // Notion's fallback (a line glyph) is for the case where the terms cannot be read at all.
  confluence: {
    d: 'M.87 18.257c-.248.382-.53.875-.763 1.245a.764.764 0 0 0 .255 1.04l4.965 3.054a.764.764 0 0 0 1.058-.26c.199-.332.454-.763.733-1.221 1.967-3.247 3.945-2.853 7.508-1.146l4.957 2.337a.764.764 0 0 0 1.028-.382l2.364-5.346a.764.764 0 0 0-.382-1 599.851 599.851 0 0 1-4.965-2.361C10.911 10.97 5.224 11.185.87 18.257zM23.131 5.743c.249-.405.531-.875.764-1.25a.764.764 0 0 0-.256-1.034L18.675.404a.764.764 0 0 0-1.058.26c-.195.335-.451.763-.734 1.225-1.966 3.246-3.945 2.85-7.508 1.146L4.437.694a.764.764 0 0 0-1.027.382L1.046 6.422a.764.764 0 0 0 .382 1c1.039.49 3.105 1.467 4.965 2.361 6.698 3.246 12.392 3.029 16.738-4.04z',
    color: '#172B4D',
    title: 'Confluence',
  },
  github: {
    d: 'M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12',
    color: '#181717',
    title: 'GitHub',
  },
  gmail: {
    d: 'M24 5.457v13.909c0 .904-.732 1.636-1.636 1.636h-3.819V11.73L12 16.64l-6.545-4.91v9.273H1.636A1.636 1.636 0 0 1 0 19.366V5.457c0-2.023 2.309-3.178 3.927-1.964L5.455 4.64 12 9.548l6.545-4.91 1.528-1.145C21.69 2.28 24 3.434 24 5.457z',
    color: '#EA4335',
    title: 'Gmail',
  },
  calendar: {
    d: 'M18.316 5.684H24v12.632h-5.684V5.684zM5.684 24h12.632v-5.684H5.684V24zM18.316 5.684V0H1.895A1.894 1.894 0 0 0 0 1.895v16.421h5.684V5.684h12.632zm-7.207 6.25v-.065c.272-.144.5-.349.687-.617s.279-.595.279-.982c0-.379-.099-.72-.3-1.025a2.05 2.05 0 0 0-.832-.714 2.703 2.703 0 0 0-1.197-.257c-.6 0-1.094.156-1.481.467-.386.311-.65.671-.793 1.078l1.085.452c.086-.249.224-.461.413-.633.189-.172.445-.257.767-.257.33 0 .602.088.816.264a.86.86 0 0 1 .322.703c0 .33-.12.589-.36.778-.24.19-.535.284-.886.284h-.567v1.085h.633c.407 0 .748.109 1.02.327.272.218.407.499.407.843 0 .336-.129.614-.387.832s-.565.327-.924.327c-.351 0-.651-.103-.897-.311-.248-.208-.422-.502-.521-.881l-1.096.452c.178.616.505 1.082.977 1.401.472.319.984.478 1.538.477a2.84 2.84 0 0 0 1.293-.291c.382-.193.684-.458.902-.794.218-.336.327-.72.327-1.149 0-.429-.115-.797-.344-1.105a2.067 2.067 0 0 0-.881-.689zm2.093-1.931l.602.913L15 10.045v5.744h1.187V8.446h-.827l-2.158 1.557zM22.105 0h-3.289v5.184H24V1.895A1.894 1.894 0 0 0 22.105 0zm-3.289 23.5l4.684-4.684h-4.684V23.5zM0 22.105C0 23.152.848 24 1.895 24h3.289v-5.184H0v3.289z',
    color: '#4285F4',
    title: 'Google Calendar',
  },
};

/**
 * What the product is called, for the places the mark is not merely decoration.
 *
 * Kept here rather than beside each caller: these are the products' own names,
 * they are already written once for the `<title>` of each mark, and a second
 * copy somewhere else is a second copy to get wrong.
 */
export function providerTitle(provider: Provider): string {
  return provider in GLYPHS
    ? GLYPHS[provider as keyof typeof GLYPHS].title
    : MARKS[provider as Exclude<Provider, keyof typeof GLYPHS>].title;
}

/** `jira.createIssue` → `jira`; anything unrecognised returns null and draws nothing. */
export function providerOf(toolName: string | null | undefined): Provider | null {
  const head = ((toolName ?? '').split('.')[0] ?? '').trim().toLowerCase();
  if (head === 'jira' || head === 'github' || head === 'gmail' || head === 'slack') return head;
  if (head === 'notion' || head === 'confluence') return head;
  if (head === 'calendar' || head === 'googlecalendar') return 'calendar';
  return null;
}

type Props = {
  provider: Provider;
  size?: number;
  /** The mark is decoration next to a name that is already written out. */
  title?: string;
};

export function BrandMark({ provider, size = 14, title }: Props) {
  const glyph = GLYPHS[provider as keyof typeof GLYPHS];
  if (glyph) {
    const Glyph = glyph.icon;
    return (
      <Glyph
        size={size}
        color={glyph.color}
        aria-hidden={title ? undefined : true}
        aria-label={title}
      />
    );
  }
  const mark = MARKS[provider as Exclude<Provider, keyof typeof GLYPHS>];
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill={mark.color}
      role={title ? 'img' : undefined}
      aria-hidden={title ? undefined : true}
      aria-label={title}
      style={{ flex: 'none' }}
    >
      {title && <title>{title}</title>}
      <path d={mark.d} />
    </svg>
  );
}

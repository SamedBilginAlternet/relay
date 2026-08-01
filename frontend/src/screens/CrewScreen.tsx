import { Ban, Bot, Hand, RefreshCw, Unplug, Users, Zap } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { BrandMark, providerOf } from '../components/BrandMark';
import type { Provider } from '../components/BrandMark';
import { EmptyState } from '../components/EmptyState';
import { LoadError } from '../components/LoadError';
import { TabStrip } from '../components/TabStrip';
import type { TabDef } from '../components/TabStrip';
import { getCrewSource } from '../data/CrewSource';
import type { Crew, CrewMember, HeldTool, ModelTier } from '../data/CrewSource';
import type { PolicyMode } from '../data/PolicySource';
import { agentLabel } from '../lib/agents';
import '../styles/crew.css';
import '../styles/screens.css';

/**
 * Ekip — the crew, as the registry produced it.
 *
 * <p>Every row on this screen is computed: the member ids come from
 * `AgentRole.toolAgent`, the tools from the registry, the authority from the
 * policy engine, the tier from the model routing property. Nothing is authored,
 * which is the only reason the word "ekip" is defensible at all (docs/EKIP.md
 * §1). Two things follow, and both are load-bearing:
 *
 * <ul>
 *   <li><b>No personas.</b> There is no job title, no avatar and no blurb about
 *       what a member is "good at". A member is a name the tools produced, an
 *       authority the policy engine computed and a model tier — and where the
 *       interface has no Turkish word for an id, it prints the id (§7.1).
 *   <li><b>No second permission store.</b> The screen reads and never writes.
 *       Authority is changed on `#/politikalar`, which is a human endpoint (§7.5).
 * </ul>
 *
 * <p>Rows rather than cards: a crew of five providers as five floating panels is
 * the dashboard look issue #124 took the product away from. One surface, hairline
 * separators, machine facts in mono.
 *
 * <p>WHAT THE TABS ARE FOR (#159, #171). One provider per tab, and no `Tümü`:
 * the all-of-them panel stacked every specialist into one column, which was the
 * exact page the tabs were brought in to retire — and it was the tab the screen
 * opened on. With it gone, every state this screen can show is one provider or
 * the fixed core, and each of those fits 1440x900 without a scrollbar. The
 * default is the first provider (in the fixed order) that actually has a
 * member; a fresh workspace with no members falls through to the honest empty
 * state rather than to a tab that opens blank.
 */

const MODES: { key: PolicyMode; label: string; Icon: LucideIcon }[] = [
  { key: 'auto', label: 'otomatik', Icon: Zap },
  { key: 'ask', label: 'onay ister', Icon: Hand },
  { key: 'forbidden', label: 'yasak', Icon: Ban },
];

/**
 * What a provider's credentials are called on Bağlantılar, so "bağlantı yok"
 * names the thing the reader has to go and fix. An unknown provider keeps its
 * own id, for the same reason `agentLabel` does.
 */
const CONNECTION_LABEL: Record<string, string> = {
  jira: 'Jira',
  slack: 'Slack',
  github: 'GitHub',
  google: 'Google',
  notion: 'Notion',
};

/**
 * What the fixed five do. These are not descriptions of a persona — each one is
 * the job of a class that exists (`Planner`, `Coordinator`, `Verifier`,
 * `PolicyEngine`, `CostMeter`), and the crew never grows by adding a line here.
 * A core id with no sentence prints its id and nothing else.
 */
const CORE_DUTY: Record<string, string> = {
  planner: 'Hedefi en çok 8 adıma böler — yalnız kayıtlı araçlarla.',
  coordinator: 'Döngüyü yürütür, onay kapısında durur, bozulan planı onarır.',
  verifier: 'Biten adımı hedefe karşı yargılar, en çok iki kez geri gönderir.',
  policy: 'Yasak adımı reddeder, gerekçesini iz kaydına yazar.',
  cost: 'Bütçe aşıldığında akışı durdurup insana sorar.',
};

function tierLabel(tier: ModelTier): string {
  if (tier === 'large') return 'büyük model';
  if (tier === 'small') return 'küçük model';
  return 'model çağırmaz';
}

function connectionLabel(provider: string): string {
  return CONNECTION_LABEL[provider] ?? provider;
}

/**
 * The mark a member wears, read off the tools it actually holds.
 *
 * <p>One function, so the tab and the row it selects can never disagree about
 * which logo a provider gets. The tool name is asked first because that is the
 * string the registry produced — `member.provider` is the server's split of the
 * same string, and it is only the fallback for a member whose tools did not
 * survive normalisation. An unrecognised provider returns null and draws
 * nothing: a stand-in glyph for a product Relay has no tool for would be the
 * costume §7.1 forbids, in the smallest possible size.
 */
function markOf(member: CrewMember): Provider | null {
  return providerOf(member.tools[0]?.name) ?? providerOf(member.provider);
}

/**
 * A provider id, or `cekirdek`.
 *
 * <p>Not a union of five literals: the providers are whatever the registry
 * returned this morning, and a hand-written list here would be the one place on
 * this screen where a new tool did not show up by itself.
 */
export type CrewTab = string;

/**
 * "Whatever the default is" — the address said nothing. Resolved against the
 * data once it has loaded, because the first provider that has a member is not
 * knowable from the hash alone.
 */
const DEFAULT: CrewTab = '';
const CORE: CrewTab = 'cekirdek';

/** The product's own word for each provider, short enough to be a tab. */
const PROVIDER_LABEL: Record<Provider, string> = {
  jira: 'Jira',
  confluence: 'Confluence',
  gmail: 'Gmail',
  calendar: 'Takvim',
  docs: 'Doküman',
  // The same rule that named Takvim and Doküman: a Google sub-product gets the
  // product's Turkish noun, matching "Tablo Uzmanı" in lib/agents.ts.
  sheets: 'Tablo',
  github: 'GitHub',
  slack: 'Slack',
  notion: 'Notion',
  hr: 'İK',
};

/**
 * Tab order, fixed by hand — the same order, and the same words, that
 * Politikalar's provider tabs use.
 *
 * <p>WHICH tabs exist is still derived, from the members the registry returned:
 * a provider with no registered tool never gets one, and a provider added to the
 * registry gets one without anybody editing a list. The ORDER is the part that
 * cannot be derived here. The members arrive with the connected ones first, so a
 * strip built in their order would rearrange itself the morning a credential
 * expired, and a tab that moves is a tab you have to find again.
 *
 * <p>Two screens hold this list now, because Politikalar grew provider tabs the
 * same day this screen did. Whoever edits either next should lift both constants
 * somewhere shared rather than write them a third time.
 */
const PROVIDER_ORDER: Provider[] = [
  'jira',
  'confluence',
  'gmail',
  'calendar',
  'docs',
  'sheets',
  'github',
  'slack',
  'notion',
  'hr',
];

function providerRank(provider: string): number {
  const index = PROVIDER_ORDER.indexOf(provider as Provider);
  return index < 0 ? PROVIDER_ORDER.length : index;
}

/** The word on the tab: the product's name for it, or the id as it arrived. */
function providerLabel(provider: string, mark: Provider | null): string {
  return mark ? PROVIDER_LABEL[mark] : provider;
}

/**
 * A query, not a path segment — the same shape Akışlar and Politikalar use, and
 * for the same reason: `#/ekip/<x>` has no meaning in `parseHash`, and giving
 * the segment one would make the router a second place that decides what this
 * screen is. The key is `saglayici`, which is the key Politikalar reads too.
 */
export function tabFromHash(hash: string): CrewTab {
  const query = hash.split('?')[1];
  if (!query) return DEFAULT;
  const value = new URLSearchParams(query).get('saglayici');
  return value ? value : DEFAULT;
}

export function hashForTab(tab: CrewTab): string {
  return tab === DEFAULT ? '#/ekip' : `#/ekip?saglayici=${encodeURIComponent(tab)}`;
}

/** The tab the address asks for, kept in step with the back button. */
function useTabInHash(): [CrewTab, (tab: CrewTab) => void] {
  const [tab, setTab] = useState<CrewTab>(() =>
    typeof window === 'undefined' ? DEFAULT : tabFromHash(window.location.hash),
  );

  useEffect(() => {
    const onChange = () => setTab(tabFromHash(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  const choose = useCallback((next: CrewTab) => {
    const hash = hashForTab(next);
    if (window.location.hash === hash) {
      setTab(next);
      return;
    }
    window.location.hash = hash;
  }, []);

  return [tab, choose];
}

export function CrewScreen() {
  const [crew, setCrew] = useState<Crew | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [wanted, choose] = useTabInHash();

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setCrew(await getCrewSource().crew());
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  /*
    Ordered here rather than taken as it arrived. The source hands the members
    over with the connected ones first, which sorts the list by something that
    changes on its own — and the strip above it must not. One order for both, so
    the third tab and the third row are the same member.
  */
  const members = useMemo(
    () =>
      [...(crew?.members ?? [])].sort(
        (a, b) =>
          providerRank(a.provider) - providerRank(b.provider) ||
          a.provider.localeCompare(b.provider, 'tr'),
      ),
    [crew],
  );
  const core = useMemo(() => crew?.core ?? [], [crew]);

  /*
    One tab per provider the registry actually returned. Nothing here knows the
    word "jira": the day a Notion tool is written a Notion tab appears with it,
    wearing no mark and carrying its own id as its label, because BrandMark has
    nothing real to draw for a product Relay has no tool for.

    The count is the tools behind the tab, not the members: a provider is always
    exactly one member, so counting members would print `1` six times and say
    nothing. It also means `Sabit çekirdek` carries no number at all — the fixed
    five hold no tools, and TabStrip does not draw a zero. That is the same fact
    the section's own head states ("araçsız"), reached by the same arithmetic.

    No `Tümü` (#171). The all-of-them panel stacked every member into one
    column — the page this screen's tabs exist to retire — and it was the
    default. Every remaining tab shows one member or the core, so no state of
    this screen is taller than its tallest provider.
  */
  const tabs = useMemo<TabDef<CrewTab>[]>(() => {
    const out: TabDef<CrewTab>[] = [];
    for (const member of members) {
      const mark = markOf(member);
      const label = providerLabel(member.provider, mark);
      out.push({
        id: member.provider,
        label,
        count: member.toolCount,
        icon: mark ? <BrandMark provider={mark} size={14} /> : undefined,
        hint: `${label} araçları`,
      });
    }
    if (core.length > 0) {
      out.push({
        id: CORE,
        label: 'Sabit çekirdek',
        hint: 'araç taşımayan, entegrasyonla çoğalmayan üyeler',
      });
    }
    return out;
  }, [members, core]);

  /*
    A tab the data does not have is not a tab. An address kept from a session
    where a Notion tool existed must not leave the screen showing an empty frame
    and a selected tab nobody can see — it falls back to the default: the first
    provider that actually has a member (the members are already in the fixed
    order). No members at all leaves DEFAULT standing, which renders the
    specialists section's own empty state — a tab that opens blank on a fresh
    workspace would be the wrong kind of first impression.
  */
  const fallback = members[0]?.provider ?? DEFAULT;
  const tab =
    wanted !== DEFAULT && tabs.some((t) => t.id === wanted) ? wanted : fallback;

  const shown = useMemo(
    () => members.filter((member) => member.provider === tab),
    [members, tab],
  );
  const totals = useMemo(() => {
    const tools = shown.reduce((sum, member) => sum + member.toolCount, 0);
    const idle = shown.filter((member) => !member.connected).length;
    return { tools, idle };
  }, [shown]);

  return (
    <div className="page">
      <div className="page__inner page__inner--app crew">
        <div className="page__head">
          <div className="page__head-text">
            {/*
              No paragraph under the title. It said "bu liste elle yazılmadı: her uzman
              bağlı bir araçtan türedi" three lines above a block at the foot of the page
              that says the same thing at greater length and with the receipts —
              `Tool.provider()`, `risk()`, the policy table. One of the two had to go, and
              the one to keep is the one that can be pointed at (#159).
            */}
            <h1 className="t-title">Ekip</h1>
          </div>
          <button
            type="button"
            className="btn btn--outline btn--sm"
            onClick={() => void load()}
            disabled={loading}
          >
            <RefreshCw size={14} aria-hidden className={loading ? 'spin' : undefined} />
            Yenile
          </button>
        </div>

        <p className="sr-only" role="status" aria-live="polite">
          {loading ? 'Ekip yükleniyor.' : `${members.length} uzman listelendi.`}
        </p>

        {error != null && <LoadError error={error} onRetry={() => void load()} />}

        {loading && (
          <>
            <div className="skeleton" style={{ height: 180 }} />
            <div className="skeleton" style={{ height: 140, opacity: 0.6 }} />
          </>
        )}

        {!loading && crew && (
          <>
            <TabStrip label="Ekip listeleri" current={tab} onChoose={choose} tabs={tabs} />

            <div role="tabpanel" id={`tabpanel-${tab}`} aria-labelledby={`tab-${tab}`}>
              {tab === CORE ? (
                /*
                  The fixed five, behind a tab of their own. They were 479px of a 1425px
                  page — a third of the screen, standing in front of the specialists, and
                  never changing: five classes that exist whether or not a single tool is
                  connected. What changes when you connect something is above; what never
                  does is one click away. Nothing was deleted to make the page fit.
                */
                <section className="crew-block" aria-labelledby="crew-core">
                  <div className="crew-block__head">
                    <h2 className="t-title" id="crew-core">
                      Sabit çekirdek
                    </h2>
                    <span className="crew-block__n t-mono">{core.length} üye · araçsız</span>
                  </div>
                  <p className="t-caption crew-block__note">
                    Bu üyeler araç taşımaz, iş taşır — ve entegrasyon eklendikçe çoğalmazlar.
                  </p>
                  <ul className="crew-list">
                    {core.map((member) => (
                      <li className="crew-row crew-row--core" key={member.id}>
                        <span className="crew-row__mark" aria-hidden>
                          <Bot size={16} />
                        </span>
                        <MemberName id={member.id} />
                        <span className="crew-row__tier t-mono">{tierLabel(member.tier)}</span>
                        {CORE_DUTY[member.id] && (
                          <p className="crew-row__duty">{CORE_DUTY[member.id]}</p>
                        )}
                      </li>
                    ))}
                  </ul>
                </section>
              ) : (
                <section className="crew-block" aria-labelledby="crew-specialists">
                  <div className="crew-block__head">
                    <h2 className="t-title" id="crew-specialists">
                      Uzmanlar
                    </h2>
                    {shown.length > 0 && (
                      <span className="crew-block__n t-mono">
                        {shown.length} üye · {totals.tools} araç
                        {totals.idle > 0 ? ` · ${totals.idle} boşta` : ''}
                      </span>
                    )}
                  </div>

                  {shown.length === 0 ? (
                    <EmptyState
                      Icon={Users}
                      title="Kayıtlı araç yok, dolayısıyla uzman da yok"
                      description="Bir uzman ancak kayıtlı bir araçtan doğar. Araç kayıt defteri boşken ekip de boştur — burada gösterilecek uydurma bir üye yok."
                    />
                  ) : (
                    <ul className="crew-list">
                      {shown.map((member) => (
                        <MemberRow key={member.id} member={member} />
                      ))}
                    </ul>
                  )}
                </section>
              )}
            </div>

          </>
        )}
      </div>
    </div>
  );
}

/** The Turkish name when there is one; otherwise the id itself, in mono. */
function MemberName({ id }: { id: string }) {
  const label = agentLabel(id);
  const named = label !== id;
  return (
    <span className="crew-row__id">
      <span className={`crew-row__name${named ? '' : ' t-mono'}`}>{label}</span>
      {named && <code className="crew-row__code t-mono">{id}</code>}
    </span>
  );
}

function MemberRow({ member }: { member: CrewMember }) {
  const mark = markOf(member);
  return (
    <li className={`crew-row${member.connected ? '' : ' crew-row--idle'}`}>
      <span className="crew-row__mark" aria-hidden>
        {mark ? <BrandMark provider={mark} size={16} /> : <Bot size={16} />}
      </span>
      <MemberName id={member.id} />
      <span className="crew-row__tier t-mono">{tierLabel(member.tier)}</span>

      {/* The authority, counted. This line is the claim the screen is here to
          make, so it is built from the tools below it rather than stored. */}
      <p className="crew-row__auth">
        <span className="crew-count t-mono">{member.toolCount} araç</span>
        {MODES.map((mode) =>
          member[mode.key] > 0 ? (
            <span className={`crew-count crew-count--${mode.key} t-mono`} key={mode.key}>
              <mode.Icon size={12} aria-hidden />
              {member[mode.key]} {mode.label}
            </span>
          ) : null,
        )}
      </p>

      <ul className="crew-tools">
        {member.tools.map((tool) => (
          <ToolChip key={tool.name} tool={tool} />
        ))}
      </ul>

      {/* Idle is said out loud. A member with no connection behind it is still a
          member — hiding it would answer "which specialists do I have" with a
          shorter list every time a credential expired (docs/EKIP.md §7.1). */}
      {!member.connected && (
        <p className="crew-row__idle">
          <Unplug size={13} aria-hidden />
          <span>
            {connectionLabel(member.connectionProvider)} bağlantısı yok — bu üye <b>boşta</b>.
            Araçları kayıtlı, yetkisi belli; bağlantı kurulduğu an çalışır.{' '}
            <a href="#/connections">Bağlantılar</a>
          </span>
        </p>
      )}
    </li>
  );
}

function ToolChip({ tool }: { tool: HeldTool }) {
  const mode = MODES.find((m) => m.key === tool.mode);
  return (
    <li className={`crew-tool crew-tool--${tool.mode}`}>
      {mode && <mode.Icon size={11} aria-hidden />}
      <code className="t-mono">{tool.name}</code>
      <span className="sr-only">: {mode?.label ?? tool.mode}</span>
    </li>
  );
}

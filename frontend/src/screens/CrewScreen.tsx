import { Ban, Bot, Hand, RefreshCw, Unplug, Users, Zap } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { BrandMark, providerOf } from '../components/BrandMark';
import { EmptyState } from '../components/EmptyState';
import { LoadError } from '../components/LoadError';
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

export function CrewScreen() {
  const [crew, setCrew] = useState<Crew | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

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

  const members = crew?.members ?? [];
  const totals = useMemo(() => {
    const tools = members.reduce((sum, member) => sum + member.toolCount, 0);
    const idle = members.filter((member) => !member.connected).length;
    return { tools, idle };
  }, [members]);

  return (
    <div className="page">
      <div className="page__inner page__inner--app crew">
        <div className="page__head">
          <div className="page__head-text">
            <h1 className="t-title">Ekip</h1>
            <p className="t-caption">
              Bu liste elle yazılmadı: her uzman <b>bağlı bir araçtan türedi</b>. Bir üye bir
              isim değil — elindeki araçlar, o araçlarda ne yapmaya yetkili olduğu ve hangi
              model kademesinde düşündüğü. Yetki burada gösterilir, <a href="#/politikalar">
              Politikalar</a> ekranında değiştirilir.
            </p>
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
            <section className="crew-block" aria-labelledby="crew-specialists">
              <div className="crew-block__head">
                <h2 className="t-title" id="crew-specialists">
                  Uzmanlar
                </h2>
                <span className="crew-block__n t-mono">
                  {members.length} üye · {totals.tools} araç
                  {totals.idle > 0 ? ` · ${totals.idle} boşta` : ''}
                </span>
              </div>

              {members.length === 0 ? (
                <EmptyState
                  Icon={Users}
                  title="Kayıtlı araç yok, dolayısıyla uzman da yok"
                  description="Bir uzman ancak kayıtlı bir araçtan doğar. Araç kayıt defteri boşken ekip de boştur — burada gösterilecek uydurma bir üye yok."
                />
              ) : (
                <ul className="crew-list">
                  {members.map((member) => (
                    <MemberRow key={member.id} member={member} />
                  ))}
                </ul>
              )}
            </section>

            <section className="crew-block" aria-labelledby="crew-core">
              <div className="crew-block__head">
                <h2 className="t-title" id="crew-core">
                  Sabit çekirdek
                </h2>
                <span className="crew-block__n t-mono">{crew.core.length} üye · araçsız</span>
              </div>
              <p className="t-caption crew-block__note">
                Bu üyeler araç taşımaz, iş taşır — ve entegrasyon eklendikçe çoğalmazlar.
              </p>
              <ul className="crew-list">
                {crew.core.map((member) => (
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

            {/* The rule that keeps every row above honest. Dashed, like the
                matching block on Politikalar: it is a rule, not a member. */}
            <section className="crew-rule" aria-labelledby="crew-rule-head">
              <h2 className="t-title" id="crew-rule-head">
                Bu listeye elle üye eklenemez
              </h2>
              <p className="t-caption">
                Bir üyenin var olabilmesi için en az bir <b>kayıtlı aracı</b> olması gerekir;
                kaynak yalnızca araç kayıt defteridir. Yeni bir sağlayıcının aracı yazıldığı gün
                uzmanı kendiliğinden burada belirir — adı{' '}
                <code className="t-mono">Tool.provider()</code>'dan, yetkisi{' '}
                <code className="t-mono">risk()</code> ve politika tablosundan gelir. Bu yüzden
                arkasında sistem olmayan bir unvan bu ekranda hiç görünmez.
              </p>
            </section>
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
  const mark = providerOf(member.provider);
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

import {
  ArrowLeft,
  ArrowRight,
  Check,
  CircleCheck,
  CircleDashed,
  Eye,
  Play,
  RefreshCw,
  ShieldCheck,
  Workflow,
} from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { getRunSource } from '../data';
import { getPlaybookSource } from '../data/PlaybookSource';
import type { Playbook } from '../data/PlaybookSource';
import type { Session } from '../lib/session';
import type { Provider } from '../types/api';

const STEPS = ['Tanışma', 'Bağlantılar', 'İlk akış'] as const;

const PROVIDERS: { provider: Provider; title: string; blurb: string }[] = [
  { provider: 'jira', title: 'Jira', blurb: 'Kayıtları oku, durum güncelle, yorum yaz.' },
  { provider: 'github', title: 'GitHub', blurb: 'Review bekleyen PR’lar ve sana atanmış issue’lar.' },
  { provider: 'slack', title: 'Slack', blurb: 'Kanala mesaj gönder, thread’e cevap yaz.' },
  { provider: 'google', title: 'Google', blurb: 'Gmail ve Takvim — günün özeti için okunur.' },
];

type ConnectionState = { connected: Record<string, boolean>; loading: boolean; error: string | null };

/**
 * First run, in three moves: what Relay is, what it can reach, and one flow actually
 * running. Skippable at every step — and the fact that it is done is stored on the
 * account, so it never comes back on the next device.
 */
export function OnboardingScreen({
  session,
  onNavigate,
}: {
  session: Session;
  onNavigate: (hash: string) => void;
}) {
  const [step, setStep] = useState(0);
  const [connections, setConnections] = useState<ConnectionState>({
    connected: {},
    loading: true,
    error: null,
  });
  const [playbooks, setPlaybooks] = useState<Playbook[]>([]);
  const [playbookError, setPlaybookError] = useState<string | null>(null);
  const [starting, setStarting] = useState<string | null>(null);
  const [finishing, setFinishing] = useState(false);

  const name = session.state.user?.displayName ?? '';

  const loadConnections = useCallback(async () => {
    setConnections((prev) => ({ ...prev, loading: true, error: null }));
    try {
      const [rows, google] = await Promise.all([
        getRunSource().getConnections(),
        getRunSource().getGoogleStatus(),
      ]);
      const connected: Record<string, boolean> = {};
      for (const row of rows) connected[row.provider] = row.configured;
      connected.google = google.connected;
      setConnections({ connected, loading: false, error: null });
    } catch (err) {
      setConnections({
        connected: {},
        loading: false,
        error: err instanceof Error ? err.message : 'Bağlantı durumu okunamadı.',
      });
    }
  }, []);

  const loadPlaybooks = useCallback(async () => {
    setPlaybookError(null);
    try {
      setPlaybooks(await getPlaybookSource().list());
    } catch (err) {
      setPlaybookError(err instanceof Error ? err.message : 'Hazır akışlar okunamadı.');
    }
  }, []);

  useEffect(() => {
    if (step === 1) void loadConnections();
    if (step === 2) void loadPlaybooks();
  }, [step, loadConnections, loadPlaybooks]);

  async function finish(target: string) {
    if (finishing) return;
    setFinishing(true);
    await session.finishOnboarding();
    setFinishing(false);
    onNavigate(target);
  }

  async function runPlaybook(id: string) {
    if (starting) return;
    setStarting(id);
    try {
      const { runId } = await getPlaybookSource().run(id);
      await session.finishOnboarding();
      // Straight into the live run: the tour ends where the product begins.
      onNavigate(runId ? `#/history/${runId}` : '#/');
    } catch (err) {
      setPlaybookError(err instanceof Error ? err.message : 'Akış başlatılamadı.');
    } finally {
      setStarting(null);
    }
  }

  return (
    <div className="onb">
      <div className="onb__inner">
        <header className="onb__head">
          <ol className="onb__steps">
            {STEPS.map((label, index) => (
              <li
                key={label}
                className={
                  index === step ? 'onb__step onb__step--now' : index < step ? 'onb__step onb__step--done' : 'onb__step'
                }
                aria-current={index === step ? 'step' : undefined}
              >
                <span className="onb__dot">{index < step ? <Check size={12} aria-hidden /> : index + 1}</span>
                <span className="onb__step-label">{label}</span>
              </li>
            ))}
          </ol>
          <button type="button" className="onb__skip" onClick={() => void finish('#/')} disabled={finishing}>
            Şimdilik atla
          </button>
        </header>

        <div className="onb__card">
          {step === 0 ? <Welcome name={name} /> : null}
          {step === 1 ? (
            <Connections state={connections} onRefresh={() => void loadConnections()} onNavigate={onNavigate} />
          ) : null}
          {step === 2 ? (
            <FirstRun
              playbooks={playbooks}
              error={playbookError}
              starting={starting}
              onRun={(id) => void runPlaybook(id)}
            />
          ) : null}
        </div>

        <footer className="onb__foot">
          <button
            type="button"
            className="onb__btn onb__btn--ghost"
            onClick={() => setStep((s) => Math.max(0, s - 1))}
            disabled={step === 0}
          >
            <ArrowLeft size={15} aria-hidden /> Geri
          </button>
          <span className="onb__progress" aria-hidden>
            {step + 1} / {STEPS.length}
          </span>
          {step < STEPS.length - 1 ? (
            <button type="button" className="onb__btn onb__btn--primary" onClick={() => setStep((s) => s + 1)}>
              Devam <ArrowRight size={15} aria-hidden />
            </button>
          ) : (
            <button
              type="button"
              className="onb__btn onb__btn--primary"
              onClick={() => void finish('#/')}
              disabled={finishing}
            >
              {finishing ? 'Bitiriliyor…' : 'Bitir'} <Check size={15} aria-hidden />
            </button>
          )}
        </footer>
      </div>
    </div>
  );
}

function Welcome({ name }: { name: string }) {
  return (
    <>
      <h1 className="onb__title">{name ? `Hoş geldin, ${name}.` : 'Hoş geldin.'}</h1>
      <p className="onb__lead">
        Relay, Jira · GitHub · Slack · Google üzerindeki günlük işleri senin adına yürüten bir ajan
        ekibi. Farkı şurada: ne yaptığını gizlemez.
      </p>
      <ul className="onb__points">
        <li>
          <Workflow size={16} aria-hidden />
          <div>
            <b>Önce plan.</b> Hedefi adımlara böler; hangi aracı hangi parametrelerle çağıracağını
            çalıştırmadan önce gösterir.
          </div>
        </li>
        <li>
          <ShieldCheck size={16} aria-hidden />
          <div>
            <b>Yazma işlemi senin onayınla.</b> Okuma serbest; mesaj göndermek, kayıt açmak gibi
            adımlar onay kapısında bekler.
          </div>
        </li>
        <li>
          <Eye size={16} aria-hidden />
          <div>
            <b>Her adım kayıtlı.</b> Ajanlar arası mesajlar, sonuçlar ve maliyet canlı akar; geçmişte
            aynısını tekrar açabilirsin.
          </div>
        </li>
      </ul>
      <p className="onb__note">
        Relay tek bir ortak çalışma alanı olarak çalışır: giriş yapan herkes aynı bağlantıları ve
        aynı geçmişi görür.
      </p>
    </>
  );
}

function Connections({
  state,
  onRefresh,
  onNavigate,
}: {
  state: ConnectionState;
  onRefresh: () => void;
  onNavigate: (hash: string) => void;
}) {
  const total = PROVIDERS.filter((p) => state.connected[p.provider]).length;
  return (
    <>
      <h1 className="onb__title">Neye erişebilsin?</h1>
      <p className="onb__lead">
        Bağlı olmayan araçlar Relay için yok hükmünde — akışlar o adımı atlar. Hiçbirini şimdi
        bağlamak zorunda değilsin; demo modu kayıtlı örneklerle çalışır.
      </p>

      <div className="onb__conns">
        {PROVIDERS.map((item) => {
          const connected = Boolean(state.connected[item.provider]);
          return (
            <div className="onb__conn" key={item.provider}>
              <span className={connected ? 'onb__badge onb__badge--on' : 'onb__badge'}>
                {connected ? <CircleCheck size={15} aria-hidden /> : <CircleDashed size={15} aria-hidden />}
              </span>
              <div className="onb__conn-text">
                <b>{item.title}</b>
                <span>{item.blurb}</span>
              </div>
              <span className={connected ? 'onb__pill onb__pill--on' : 'onb__pill'}>
                {state.loading ? 'kontrol ediliyor…' : connected ? 'bağlı' : 'bağlı değil'}
              </span>
            </div>
          );
        })}
      </div>

      {state.error ? <p className="onb__alert">{state.error}</p> : null}

      <div className="onb__actions">
        <button type="button" className="onb__btn onb__btn--secondary" onClick={() => onNavigate('#/connections')}>
          Bağlantılar ekranını aç
        </button>
        <button type="button" className="onb__btn onb__btn--ghost" onClick={onRefresh} disabled={state.loading}>
          <RefreshCw size={15} aria-hidden /> Durumu yenile
        </button>
        <span className="onb__count">{total} / {PROVIDERS.length} bağlı</span>
      </div>
      <p className="onb__note">
        Google’ı bağlamak Gmail ve Takvim okuma izni ister; girişteki ad/e-posta izniyle aynı şey
        değil — bu ayrı bir onaydır ve istediğinde geri alınır. Bağlantı kurduktan sonra bu tura
        geri dönersin.
      </p>
    </>
  );
}

function FirstRun({
  playbooks,
  error,
  starting,
  onRun,
}: {
  playbooks: Playbook[];
  error: string | null;
  starting: string | null;
  onRun: (id: string) => void;
}) {
  return (
    <>
      <h1 className="onb__title">İlk akışı çalıştır</h1>
      <p className="onb__lead">
        Hazır akışlar yazılı: hangi adımların hangi araçla çalışacağı bellidir. Birini seç — planı
        görür, onay kapısında dur, sonucu canlı izlersin.
      </p>

      {error ? <p className="onb__alert">{error}</p> : null}
      {!error && playbooks.length === 0 ? <p className="onb__note">Hazır akışlar yükleniyor…</p> : null}

      <div className="onb__plays">
        {playbooks.map((playbook) => (
          <div className="onb__play" key={playbook.id}>
            <div className="onb__play-text">
              <b>{playbook.title}</b>
              <span>{playbook.subtitle}</span>
              {!playbook.runnable ? (
                <span className="onb__missing">
                  Eksik bağlantı: {playbook.missing.join(', ')} — bağlamadan çalıştırılamaz.
                </span>
              ) : null}
            </div>
            <button
              type="button"
              className="onb__btn onb__btn--primary"
              onClick={() => onRun(playbook.id)}
              disabled={!playbook.runnable || starting !== null}
            >
              {starting === playbook.id ? 'Başlatılıyor…' : 'Çalıştır'} <Play size={14} aria-hidden />
            </button>
          </div>
        ))}
      </div>
      <p className="onb__note">
        Çalıştırdığında turu bitirmiş olursun; akışın canlı ekranına düşersin.
      </p>
    </>
  );
}

import { motion, useReducedMotion } from 'motion/react';
import { Eye, ShieldQuestion, Sparkles, Wallet } from 'lucide-react';
import { Composer } from './Composer';

type Props = {
  onSubmit: (goal: string) => void;
  busy?: boolean;
  sourceKind: 'api' | 'mock';
};

const SUGGESTIONS: { text: string; tools: string }[] = [
  {
    text: 'Jira’daki blocker etiketli işleri bul, durumlarını güncelle, ekibe Slack’ten özet at.',
    tools: 'jira.searchIssues · jira.updateIssue · slack.postMessage',
  },
  {
    text: 'Bana atanmış açık ticket’ları listele ve bugün bitmesi gerekenleri #dev-sprint kanalına yaz.',
    tools: 'jira.searchIssues · slack.postMessage',
  },
  {
    text: 'RUN-42’yi incele, kök nedeni yorum olarak ekle ve sorumluya Slack’ten haber ver.',
    tools: 'jira.getIssue · jira.addComment · slack.postMessage',
  },
];

export function Landing({ onSubmit, busy = false, sourceKind }: Props) {
  const reduce = useReducedMotion();

  return (
    <div className="landing">
      <div className="landing__inner">
        <motion.div
          initial={reduce ? { opacity: 0 } : { opacity: 0, transform: 'translateY(12px)' }}
          animate={{ opacity: 1, transform: 'translateY(0px)' }}
          transition={{ duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
          style={{ display: 'flex', flexDirection: 'column', gap: 20 }}
        >
          <span className="landing__eyebrow">
            <Sparkles size={13} aria-hidden />
            {sourceKind === 'mock'
              ? 'Demo modu — senaryo canlı oynatılır, backend gerekmez'
              : 'Canlı mod — gerçek Jira & Slack bağlantıları'}
          </span>

          <h1 className="t-display">
            İşini anlat.
            <br />
            <span className="landing__accent">Ekip yürütsün, sen izle.</span>
          </h1>

          <p className="landing__sub">
            Relay hedefini numaralı bir iş akışına çevirir; Jira ve Slack uzmanı ajanlar adımları
            işler. Her araç çağrısı parametreleriyle görünür, her yazma adımı onayını bekler.
          </p>

          <ul className="landing__points">
            <li className="landing__point">
              <Eye size={13} aria-hidden />
              Her adım görünür
            </li>
            <li className="landing__point">
              <ShieldQuestion size={13} aria-hidden />
              Yazma adımı onay ister
            </li>
            <li className="landing__point">
              <Wallet size={13} aria-hidden />
              Token ve ücret canlı
            </li>
          </ul>
        </motion.div>

        <motion.div
          initial={reduce ? { opacity: 0 } : { opacity: 0, transform: 'translateY(16px)' }}
          animate={{ opacity: 1, transform: 'translateY(0px)' }}
          transition={{ duration: 0.4, delay: 0.08, ease: [0.16, 1, 0.3, 1] }}
          style={{ display: 'flex', flexDirection: 'column', gap: 16 }}
        >
          <Composer onSubmit={onSubmit} busy={busy} variant="landing" autoFocus />

          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <span className="t-label">Hazır örnekler</span>
            <div className="suggestions">
              {SUGGESTIONS.map((s) => (
                <button
                  key={s.text}
                  type="button"
                  className="suggestion"
                  onClick={() => onSubmit(s.text)}
                  disabled={busy}
                >
                  {s.text}
                  <span className="suggestion__tools t-mono">{s.tools}</span>
                </button>
              ))}
            </div>
          </div>
        </motion.div>
      </div>
    </div>
  );
}

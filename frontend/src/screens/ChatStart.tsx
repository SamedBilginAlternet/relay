import { Composer } from '../components/Composer';
import '../styles/screens.css';

type Props = {
  onSubmit: (goal: string) => void;
  busy?: boolean;
  sourceKind: 'api' | 'mock';
};

/*
  Sohbet before a flow exists.

  This used to be the landing page — the marketing one, headline and all —
  rendered inside the application to a person who had already signed up. 470px
  of the screen argued for the product ("İşini anlat. / Ekip yürütsün, sen
  izle.", a paragraph of pitch, three badges that could not be clicked) and the
  box you actually came here to type in started below the fold at y=477 (#69).
  Its container said so too: `.landing__inner`, 860px, while the six other
  screens on the same nav are 1040px, so walking the nav slid the content column
  sideways.

  So it is an application screen now, shaped like the other six: `page__inner
  --app`, a 20px title, and the composer as the first thing under it. The
  suggestions stay — they are how someone learns what to ask for — and the
  56px `.t-display` scale belongs to whoever is not signed in yet.
*/

/*
  The sentence names the JOB, not the vendor (KONUMLANDIRMA.md §3, A1). The
  first example starts in the mailbox because "turn the request that just landed
  into a record" is the flow that belongs to everyone rather than to one craft.
  The mono tool line stays — transparency is the pitch (DESIGN.md) — but it
  reads as a receipt under the sentence, not as a second headline.
*/
const SUGGESTIONS: { text: string; tools: string }[] = [
  {
    text: 'Bugünkü maillerime bak, iş talebi gibi görünenler için Jira kaydı aç ve ilgili kanaldan ekibe haber ver.',
    tools: 'gmail.listToday · jira.createIssue · slack.postMessage',
  },
  {
    text: 'Bana atanmış açık işleri çıkar, bugün bitmesi gerekenleri ekibin kanalına yaz.',
    tools: 'jira.searchIssues · slack.postMessage',
  },
  {
    text: 'Üç gündür ilerlemeyen işleri bul, nerede takıldıklarını not düş ve sahiplerine haber ver.',
    tools: 'jira.searchIssues · jira.addComment · slack.postMessage',
  },
];

export function ChatStart({ onSubmit, busy = false, sourceKind }: Props) {
  return (
    <div className="page">
      <div className="page__inner page__inner--app">
        <div className="page__head">
          <div className="page__head-text">
            <h1 className="t-title">Sohbet</h1>
            {/*
              The heading, the box, the examples. What used to sit here was a paragraph
              explaining what the product would do and promising that every write waits
              for approval — copy that is read once, on the screen where there is nothing
              yet to approve. The composer's placeholder says what to type; the gate says
              what it costs, at the moment it costs it.
            */}
            {sourceKind === 'mock' && (
              <p className="t-caption">Demo modu — senaryo canlı oynatılır, backend gerekmez.</p>
            )}
          </div>
        </div>

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
      </div>
    </div>
  );
}

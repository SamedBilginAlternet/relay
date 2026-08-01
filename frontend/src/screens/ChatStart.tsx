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

  Then it became an application screen shaped like the other six: composer at
  top, examples under it. That fixed the marketing problem but traded away the
  one thing this particular screen is for — it is where a person meets the
  team before they have asked it anything. A form field at the top of a plain
  page does not say that; a mark that is visibly alive, centred, with the
  question underneath it and the keyboard already at the bottom where a hand
  is about to be, does.
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
  /*
    The fourth example exists to say the quiet part: none of this is about
    software. "Bugün ne konuşuldu, ne karara bağlandı" is the job of a lawyer's
    assistant, a consultant and an operations manager, and Notion is where all
    three keep it. The other three examples end in a channel; this one ends in a
    document somebody will still be reading next month.
  */
  {
    text: 'Bugünkü toplantıların ve maillerin özetini çıkar, alınan kararları Notion’a not sayfası olarak yaz.',
    tools: 'calendar.listToday · gmail.listToday · notion.createPage',
  },
];

export function ChatStart({ onSubmit, busy = false, sourceKind }: Props) {
  return (
    <div className="page">
      <div className="chat-landing">
        <div className="chat-landing__hero">
          {/* aria-hidden: decorative — "Sohbet" and the question below already
              name the screen for a screen reader. */}
          <div className="chat-landing__mark-wrap" aria-hidden>
            <span className="chat-landing__ring" />
            <span className="chat-landing__ring chat-landing__ring--2" />
            <span className="chat-landing__mark">
              <svg viewBox="0 0 64 64" width="40" height="40">
                <circle cx="17" cy="38" r="7" fill="currentColor" />
                <rect
                  x="20"
                  y="23.5"
                  width="24"
                  height="8"
                  rx="4"
                  fill="currentColor"
                  transform="rotate(-14 32 27.5)"
                />
                <circle cx="47" cy="20" r="7" fill="currentColor" />
              </svg>
            </span>
          </div>

          <h1 className="chat-landing__title">Ne yapmamı istersin?</h1>
          {/*
            What used to sit here was a paragraph explaining what the product would do
            and promising that every write waits for approval — copy that is read once,
            on the screen where there is nothing yet to approve. The composer's
            placeholder says what to type; the gate says what it costs, at the moment
            it costs it.
          */}
          {sourceKind === 'mock' && (
            <p className="t-caption">Demo modu — senaryo canlı oynatılır, backend gerekmez.</p>
          )}

          <div className="chat-landing__suggestions">
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

        <div className="chat-landing__composer">
          <Composer onSubmit={onSubmit} busy={busy} variant="landing" autoFocus />
        </div>
      </div>
    </div>
  );
}

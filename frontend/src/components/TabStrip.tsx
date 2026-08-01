/**
 * One row of tabs over a list, with the size of what is behind each.
 *
 * <p>Two screens hold a long list that is really two or four lists: Akışlar keeps the runs
 * parked on a decision apart from the log, Politikalar keeps the tools by the rule they run
 * under. Both used to draw every group at once, stacked down one column, and both were
 * unreadable for the same reason — you had to scroll a list you did not come for to reach
 * the one you did.
 *
 * <p>It is one component rather than two because the second one was going to be a copy of
 * the first, and a copied selected-state is how a product ends up with two ways of looking
 * selected.
 *
 * <p>WHAT IT DOES NOT DO. It does not own the tab. The screen keeps that — in the address,
 * so the list can be linked to and survives a refresh — and hands it back here. A tab strip
 * holding the state would be a second place the address could disagree with.
 */

export type TabDef<T extends string> = {
  id: T;
  label: string;
  /** Drawn only when it is above zero: an empty group says so by being empty. */
  count?: number | null;
  /**
   * What the tab means, for the reader who does not know yet. It rides on the control it
   * explains rather than in a paragraph above the list — the paragraph is what this strip
   * replaced, and a sentence nobody needs twice should not be on screen twice.
   */
  hint?: string;
};

type Props<T extends string> = {
  tabs: TabDef<T>[];
  current: T;
  onChoose: (tab: T) => void;
  /** Names the strip for a screen reader — there is more than one list on the page. */
  label: string;
  /**
   * `gate` paints the counts amber, and is for a number that means "this is waiting on
   * you" — the same amber as the sidebar badge, because it is the same set. Everything
   * else counts group sizes, which nobody is being asked to act on, and stays neutral:
   * an amber 12 would claim twelve tools were holding something up.
   */
  tone?: 'gate' | 'plain';
};

export function TabStrip<T extends string>({
  tabs,
  current,
  onChoose,
  label,
  tone = 'plain',
}: Props<T>) {
  return (
    <div className={`tabs${tone === 'gate' ? ' tabs--gate' : ''}`} role="tablist" aria-label={label}>
      {tabs.map((tab) => {
        const selected = current === tab.id;
        return (
          <button
            key={tab.id}
            type="button"
            role="tab"
            id={`tab-${tab.id}`}
            aria-selected={selected}
            aria-controls={`tabpanel-${tab.id}`}
            className={`tab${selected ? ' tab--on' : ''}`}
            title={tab.hint}
            onClick={() => onChoose(tab.id)}
          >
            {tab.label}
            {tab.count != null && tab.count > 0 && (
              <span className="tab__count t-mono">{tab.count}</span>
            )}
          </button>
        );
      })}
    </div>
  );
}

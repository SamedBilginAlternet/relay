import { ChevronUp, ListChecks } from 'lucide-react';
import { useCallback, useEffect, useRef } from 'react';
import { BottomSheet } from '../components/BottomSheet';
import { ChatPanel } from '../components/ChatPanel';
import { TaskRail, useLiveRuns } from '../components/TaskRail';
import { WorkflowPanel } from '../components/WorkflowPanel';
import { RUN_SOURCE_KIND } from '../data';
import { useHashRoute } from '../lib/router';
import { useRunStore } from '../store/runStore';
import { ChatStart } from './ChatStart';

export function ChatScreen() {
  const [route, navigate] = useHashRoute();
  const routeRunId = route.name === 'chat' ? (route.runId ?? null) : null;
  const openRun = useRunStore((s) => s.openRun);
  const run = useRunStore((s) => s.run);
  const phase = useRunStore((s) => s.phase);
  const error = useRunStore((s) => s.error);
  const streamStatus = useRunStore((s) => s.streamStatus);
  const expandedStepId = useRunStore((s) => s.expandedStepId);
  const rejectingStepId = useRunStore((s) => s.rejectingStepId);
  const busyStepId = useRunStore((s) => s.busyStepId);
  const editError = useRunStore((s) => s.editError);
  const sheetOpen = useRunStore((s) => s.sheetOpen);

  const startRun = useRunStore((s) => s.startRun);
  const retry = useRunStore((s) => s.retry);
  const rerun = useRunStore((s) => s.rerun);
  const approve = useRunStore((s) => s.approve);
  const reject = useRunStore((s) => s.reject);
  const toggleStep = useRunStore((s) => s.toggleStep);
  const setRejecting = useRunStore((s) => s.setRejecting);
  const setSheetOpen = useRunStore((s) => s.setSheetOpen);
  const watchRun = useRunStore((s) => s.watchRun);
  const stopWatching = useRunStore((s) => s.stopWatching);

  const awaiting = run?.steps.filter((s) => s.status === 'awaiting_approval').length ?? 0;

  // Leaving for the Bugün screen used to leave the connection behind, still open and still
  // writing into the store from a screen nobody was looking at. The run itself stays — it is
  // picked back up here when the user returns and it has not finished in the meantime.
  useEffect(() => {
    watchRun();
    return stopWatching;
  }, [watchRun, stopWatching]);

  /*
    The address bar is the only part of this screen that survives a refresh.

    It used not to carry the run at all: a flow sitting at its approval gate
    lived in memory and nowhere else, so F5 — or a trip to Bugün and back on a
    reloaded tab — returned the empty "İşini anlat." greeting while the flow
    stayed `awaiting_approval` on the server with no button anywhere that could
    answer it. The plan, the reads and the tokens spent on them were simply
    stranded; 32 runs had piled up that way.

    So: an id in the hash is loaded, and a loaded run writes its id into the
    hash. `replace`, not push — the URL is catching up with something the user
    already did, and Back belongs to wherever they came from.
  */
  useEffect(() => {
    if (!routeRunId) return;
    if (useRunStore.getState().run?.id === routeRunId) return;
    void openRun(routeRunId);
  }, [routeRunId, openRun]);

  /*
    …and the run writes its id back, but only for a run that has not written it yet.

    The two effects above and below run in the same commit whenever the hash changes, and
    they disagree by one render: the effect above asks the store for the run the hash names,
    while this one still holds the run that was on screen when the click happened. Told only
    "these differ, so publish mine", it published the OLD id — `replace`, so there was no
    Back to undo it — and the address bar quietly cancelled the navigation. Nothing else in
    the product could change the hash while a run was loaded, so nothing exposed it until a
    row on the rail could.

    The ref is the missing half of the question: it is not "do these differ", it is "did the
    RUN change". A run that has already published its id stays quiet and lets the hash lead;
    a run the store has just created (`startRun`, `rerun`) has published nothing, so it
    still writes itself into the address bar — which is the reason this effect exists.
  */
  const publishedRunId = useRef<string | null>(null);
  useEffect(() => {
    if (!run) return;
    if (run.id === routeRunId) {
      publishedRunId.current = run.id;
      return;
    }
    if (publishedRunId.current === run.id) return;
    publishedRunId.current = run.id;
    navigate(`#/sohbet/${run.id}`, { replace: true });
  }, [run, routeRunId, navigate]);

  // An approval gate must never hide behind a closed sheet on mobile.
  useEffect(() => {
    if (awaiting > 0 && window.matchMedia('(max-width: 900px)').matches) setSheetOpen(true);
  }, [awaiting, setSheetOpen]);

  /*
    The other flows. This screen showed one run and behaved as if it were the only one;
    on the live box that meant 1 of 28 runs stopped on a decision was on screen and the
    other 27 had no route in the product except Geçmiş (#125).

    Only the address is set here. The effect above owns the loading — it is the one place
    that decides what `#/sohbet/<id>` means, and a second caller would race it.
  */
  const liveRuns = useLiveRuns(run);
  const openFromRail = useCallback(
    (runId: string) => navigate(`#/sohbet/${runId}`),
    [navigate],
  );
  // Never rendered empty: no live run means no column, and the composer keeps the width.
  const rail =
    liveRuns.length > 0 ? (
      <TaskRail runs={liveRuns} currentRunId={run?.id ?? null} onOpen={openFromRail} />
    ) : null;

  if (!run && phase === 'idle') {
    return (
      <div className="rail-start">
        {rail}
        <ChatStart
          onSubmit={(goal) => void startRun(goal)}
          busy={phase !== 'idle'}
          sourceKind={RUN_SOURCE_KIND}
        />
      </div>
    );
  }

  const panel = (
    <WorkflowPanel
      run={run}
      phase={phase}
      error={error}
      streamStatus={streamStatus}
      expandedStepId={expandedStepId}
      rejectingStepId={rejectingStepId}
      busyStepId={busyStepId}
      editError={editError}
      onToggleStep={toggleStep}
      onApprove={(id, params) => void approve(id, params)}
      onReject={(id, reason) => void reject(id, reason)}
      onStartReject={setRejecting}
      onRetry={() => void retry()}
      onRerun={() => void rerun()}
    />
  );

  const doneCount = run?.steps.filter((s) => s.status === 'done').length ?? 0;

  return (
    <>
      <h1 className="sr-only">Sohbet — çalışan akış</h1>
      <button
        type="button"
        className="mobile-bar"
        onClick={() => setSheetOpen(true)}
        aria-label="İş akışı panelini aç"
      >
        <ListChecks size={16} aria-hidden />
        <span style={{ fontWeight: 600 }}>İş akışı</span>
        <span className="t-caption">
          {run ? `${doneCount}/${run.steps.length} adım` : 'hazırlanıyor'}
          {awaiting > 0 ? ` · ${awaiting} onay bekliyor` : ''}
        </span>
        <ChevronUp size={16} aria-hidden style={{ marginLeft: 'auto' }} />
      </button>

      <div className={`workbench${rail ? ' workbench--railed' : ''}`}>
        {rail}
        <ChatPanel
          run={run}
          phase={phase}
          error={error}
          onSubmit={(goal) => void startRun(goal)}
          onRetry={() => void retry()}
        />
        {panel}
      </div>


      <BottomSheet open={sheetOpen} title="İş akışı" onClose={() => setSheetOpen(false)}>
        {panel}
      </BottomSheet>
    </>
  );
}

import { ChevronUp, ListChecks } from 'lucide-react';
import { useEffect } from 'react';
import { BottomSheet } from '../components/BottomSheet';
import { ChatPanel } from '../components/ChatPanel';
import { Landing } from '../components/Landing';
import { WorkflowPanel } from '../components/WorkflowPanel';
import { RUN_SOURCE_KIND } from '../data';
import { useHashRoute } from '../lib/router';
import { useRunStore } from '../store/runStore';

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

  useEffect(() => {
    if (!run || run.id === routeRunId) return;
    navigate(`#/sohbet/${run.id}`, { replace: true });
  }, [run, routeRunId, navigate]);

  // An approval gate must never hide behind a closed sheet on mobile.
  useEffect(() => {
    if (awaiting > 0 && window.matchMedia('(max-width: 900px)').matches) setSheetOpen(true);
  }, [awaiting, setSheetOpen]);

  if (!run && phase === 'idle') {
    return (
      <Landing
        onSubmit={(goal) => void startRun(goal)}
        busy={phase !== 'idle'}
        sourceKind={RUN_SOURCE_KIND}
      />
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

      <div className="workbench">
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

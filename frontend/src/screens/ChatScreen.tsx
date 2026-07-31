import { ChevronUp, ListChecks } from 'lucide-react';
import { useEffect } from 'react';
import { BottomSheet } from '../components/BottomSheet';
import { ChatPanel } from '../components/ChatPanel';
import { Landing } from '../components/Landing';
import { WorkflowPanel } from '../components/WorkflowPanel';
import { RUN_SOURCE_KIND } from '../data';
import { useRunStore } from '../store/runStore';

export function ChatScreen() {
  const run = useRunStore((s) => s.run);
  const phase = useRunStore((s) => s.phase);
  const error = useRunStore((s) => s.error);
  const streamStatus = useRunStore((s) => s.streamStatus);
  const expandedStepId = useRunStore((s) => s.expandedStepId);
  const rejectingStepId = useRunStore((s) => s.rejectingStepId);
  const busyStepId = useRunStore((s) => s.busyStepId);
  const sheetOpen = useRunStore((s) => s.sheetOpen);

  const startRun = useRunStore((s) => s.startRun);
  const retry = useRunStore((s) => s.retry);
  const rerun = useRunStore((s) => s.rerun);
  const approve = useRunStore((s) => s.approve);
  const reject = useRunStore((s) => s.reject);
  const toggleStep = useRunStore((s) => s.toggleStep);
  const setRejecting = useRunStore((s) => s.setRejecting);
  const setSheetOpen = useRunStore((s) => s.setSheetOpen);

  const awaiting = run?.steps.filter((s) => s.status === 'awaiting_approval').length ?? 0;

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
      onToggleStep={toggleStep}
      onApprove={(id) => void approve(id)}
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

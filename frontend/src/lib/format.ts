export function formatTokens(tokens: number): string {
  return tokens.toLocaleString('tr-TR');
}

/**
 * Money, to the digit the server actually keeps.
 *
 * <p>`CostMeter` fixes every price at six decimals before it leaves the API — deliberately,
 * so that two endpoints can never disagree about the same money. The screen rounded to four
 * and disagreed with all of them: a step the API reports as `0.000113` read `$0.0001`.
 *
 * <p>The small numbers are the ones this product is about. A step routed to the cheap model
 * costs a fraction of a thousandth of a dollar, and at four decimals that evidence goes
 * missing exactly where the claim is made. Neither branch below can produce scientific
 * notation, which is how `3.82E-4` once reached a screen — `toFixed` gives up and writes an
 * exponent above 1e21, so the whole-dollar side goes through a grouping formatter instead.
 *
 * <p>A number that is not a number is not $0.00 — the dash says "unknown" out loud.
 */
export function formatUsd(usd: number): string {
  if (!Number.isFinite(usd)) return '—';
  const sign = usd < 0 ? '-' : '';
  const abs = Math.abs(usd);
  const digits =
    abs >= 1
      ? abs.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
      : abs.toFixed(6);
  return `${sign}$${digits}`;
}

export function formatDurationMs(ms: number): string {
  if (ms < 1000) return `${Math.max(ms, 0).toFixed(0)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} sn`;
  const min = Math.floor(ms / 60_000);
  const sec = Math.round((ms % 60_000) / 1000);
  return `${min} dk ${sec} sn`;
}

export function stepDuration(startedAt: string | null, finishedAt: string | null): string | null {
  if (!startedAt) return null;
  const start = Date.parse(startedAt);
  if (Number.isNaN(start)) return null;
  const end = finishedAt ? Date.parse(finishedAt) : Date.now();
  if (Number.isNaN(end)) return null;
  return formatDurationMs(end - start);
}

export function formatDateTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString('tr-TR', {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** "31 Temmuz" — the Bugün header date. Falls back to today. */
export function formatDayMonth(iso: string | null): string {
  const d = iso ? new Date(iso) : new Date();
  const safe = Number.isNaN(d.getTime()) ? new Date() : d;
  return safe.toLocaleDateString('tr-TR', { day: 'numeric', month: 'long' });
}

export function formatRelative(iso: string | null): string {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '—';
  const diff = Date.now() - t;
  const min = Math.round(diff / 60_000);
  if (min < 1) return 'az önce';
  if (min < 60) return `${min} dk önce`;
  const hours = Math.round(min / 60);
  if (hours < 24) return `${hours} sa önce`;
  const days = Math.round(hours / 24);
  if (days < 7) return `${days} gün önce`;
  return formatDateTime(iso);
}

export function formatTime(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' });
}

export function prettyJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2) ?? 'null';
  } catch {
    return String(value);
  }
}

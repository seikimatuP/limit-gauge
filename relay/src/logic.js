// Pure logic for the relay (no Workers APIs), so it can be unit-tested with plain Node.

export const WINDOWS = ["five_hour", "seven_day"];

/** Reports whose resets_at differ by at most this much describe the same window. */
export const SAME_WINDOW_TOLERANCE_S = 15 * 60;
/** A report whose reset moved *earlier* is trusted only if it is at least this much newer. */
export const MOVED_WINDOW_TRUST_AFTER_S = 10 * 60;
/** captured_at further in the future than this is treated as clock skew and replaced by "now". */
export const MAX_FUTURE_SKEW_S = 5 * 60;

/** Epoch seconds from seconds, milliseconds, numeric strings or ISO-8601. null when unusable. */
export function toEpochSeconds(v) {
  if (typeof v === "string") {
    const s = v.trim();
    if (s === "") return null;
    if (/^\d+(\.\d+)?$/.test(s)) {
      v = Number(s);
    } else {
      const t = Date.parse(s);
      return Number.isNaN(t) ? null : Math.floor(t / 1000);
    }
  }
  if (typeof v !== "number" || !Number.isFinite(v) || v <= 0) return null;
  return Math.floor(v > 1e12 ? v / 1000 : v);
}

function normalizeWindow(w, capturedAt, source) {
  if (!w || typeof w !== "object") return null;
  const used = typeof w.used_percentage === "string" ? Number(w.used_percentage) : w.used_percentage;
  if (typeof used !== "number" || !Number.isFinite(used)) return null;
  const resetsAt = toEpochSeconds(w.resets_at);
  if (resetsAt == null) return null;
  return {
    used_percentage: Math.min(100, Math.max(0, used)),
    resets_at: resetsAt,
    captured_at: capturedAt,
    source,
  };
}

/**
 * Accepts the sender's payload ({five_hour, seven_day, captured_at, source}) or Claude Code's raw
 * status line JSON ({rate_limits: {five_hour, seven_day}}). Returns null when it has no usable window.
 */
export function normalizeReport(body, nowS) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return null;
  const src = body.rate_limits && typeof body.rate_limits === "object" ? body.rate_limits : body;
  let capturedAt = toEpochSeconds(body.captured_at) ?? nowS;
  if (capturedAt > nowS + MAX_FUTURE_SKEW_S) capturedAt = nowS;
  const source = typeof body.source === "string" && body.source ? body.source.slice(0, 64) : "unknown";
  const report = {};
  for (const name of WINDOWS) {
    const w = normalizeWindow(src[name], capturedAt, source);
    if (w) report[name] = w;
  }
  return Object.keys(report).length ? report : null;
}

/**
 * Merges one incoming window into the stored one. Returns `cur` itself when nothing changes.
 *
 * Several Claude Code sessions (WSL, Windows, other PCs) can report the same account, sometimes late.
 * Within one window usage only grows until the reset, so the highest value wins; a later reset time
 * means a new window; an earlier reset time is a stale report unless it is clearly newer data.
 */
export function mergeWindow(cur, inc) {
  if (!inc) return cur;
  if (!cur) return inc;
  const shift = inc.resets_at - cur.resets_at;
  if (Math.abs(shift) <= SAME_WINDOW_TOLERANCE_S) {
    const incNewer = inc.captured_at >= cur.captured_at;
    const next = {
      used_percentage: Math.max(cur.used_percentage, inc.used_percentage),
      resets_at: incNewer ? inc.resets_at : cur.resets_at,
      captured_at: Math.max(cur.captured_at, inc.captured_at),
      source: inc.used_percentage >= cur.used_percentage ? inc.source : cur.source,
    };
    const same =
      next.used_percentage === cur.used_percentage &&
      next.resets_at === cur.resets_at &&
      next.captured_at === cur.captured_at &&
      next.source === cur.source;
    return same ? cur : next;
  }
  if (shift > 0) return inc;
  if (inc.captured_at - cur.captured_at >= MOVED_WINDOW_TRUST_AFTER_S) return inc;
  return cur;
}

/** Applies a normalized report to the stored state. Returns {changed, state}. */
export function applyReport(state, report) {
  const next = { ...(state || {}) };
  let changed = false;
  for (const name of WINDOWS) {
    const before = next[name] ?? null;
    const after = mergeWindow(before, report[name] ?? null);
    if (after !== before) {
      next[name] = after;
      changed = true;
    }
  }
  if (changed) {
    next.updated_at = Math.max(0, ...WINDOWS.map((n) => next[n]?.captured_at ?? 0));
  }
  return { changed, state: changed ? next : state || {} };
}

/** The JSON the app reads. */
export function present(state, nowS) {
  return {
    v: 1,
    server_time: nowS,
    updated_at: state?.updated_at ?? null,
    five_hour: state?.five_hour ?? null,
    seven_day: state?.seven_day ?? null,
  };
}

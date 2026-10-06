import assert from "node:assert/strict";
import { test } from "node:test";
import { applyReport, mergeWindow, normalizeReport, present, toEpochSeconds } from "../src/logic.js";

const NOW = 1_760_000_000;
const H = 3600;
const w = (used, resets, captured, source = "pc") => ({
  used_percentage: used,
  resets_at: resets,
  captured_at: captured,
  source,
});

test("toEpochSeconds handles seconds, millis, strings and junk", () => {
  assert.equal(toEpochSeconds(NOW), NOW);
  assert.equal(toEpochSeconds(NOW * 1000 + 999), NOW);
  assert.equal(toEpochSeconds(String(NOW)), NOW);
  assert.equal(toEpochSeconds("2025-10-09T08:53:20Z"), 1_760_000_000);
  assert.equal(toEpochSeconds("2025-10-09T17:53:20+09:00"), 1_760_000_000);
  for (const bad of [null, undefined, "", "soon", 0, -5, NaN, Infinity, {}]) {
    assert.equal(toEpochSeconds(bad), null, String(bad));
  }
});

test("normalizeReport accepts the sender payload", () => {
  const r = normalizeReport(
    { v: 1, source: "wsl", captured_at: NOW - 5, five_hour: { used_percentage: 23.5, resets_at: NOW + H } },
    NOW,
  );
  assert.deepEqual(r, { five_hour: w(23.5, NOW + H, NOW - 5, "wsl") });
});

test("normalizeReport accepts raw Claude Code status line JSON", () => {
  const r = normalizeReport(
    {
      model: { display_name: "Opus" },
      rate_limits: {
        five_hour: { used_percentage: 10, resets_at: NOW + H },
        seven_day: { used_percentage: "41.2", resets_at: (NOW + 5 * 24 * H) * 1000 },
      },
    },
    NOW,
  );
  assert.equal(r.five_hour.used_percentage, 10);
  assert.equal(r.five_hour.captured_at, NOW);
  assert.equal(r.five_hour.source, "unknown");
  assert.equal(r.seven_day.used_percentage, 41.2);
  assert.equal(r.seven_day.resets_at, NOW + 5 * 24 * H);
});

test("normalizeReport clamps, rejects junk and future clocks", () => {
  assert.equal(normalizeReport(null, NOW), null);
  assert.equal(normalizeReport([], NOW), null);
  assert.equal(normalizeReport({}, NOW), null);
  assert.equal(normalizeReport({ five_hour: { used_percentage: "x", resets_at: NOW } }, NOW), null);
  assert.equal(normalizeReport({ five_hour: { used_percentage: 5 } }, NOW), null);
  const clamped = normalizeReport({ five_hour: { used_percentage: 140, resets_at: NOW + H } }, NOW);
  assert.equal(clamped.five_hour.used_percentage, 100);
  const skewed = normalizeReport({ captured_at: NOW + 3600, five_hour: { used_percentage: 1, resets_at: NOW + H } }, NOW);
  assert.equal(skewed.five_hour.captured_at, NOW);
  const longSource = normalizeReport({ source: "x".repeat(200), five_hour: { used_percentage: 1, resets_at: NOW + H } }, NOW);
  assert.equal(longSource.five_hour.source.length, 64);
});

test("mergeWindow: same window keeps the highest usage", () => {
  const cur = w(30, NOW + H, NOW - 100, "wsl");
  const lower = w(25, NOW + H + 30, NOW, "windows"); // late report from a session that saw less
  const merged = mergeWindow(cur, lower);
  assert.equal(merged.used_percentage, 30);
  assert.equal(merged.source, "wsl");
  assert.equal(merged.captured_at, NOW);
  assert.equal(merged.resets_at, NOW + H + 30, "newest reset estimate wins");

  const higher = mergeWindow(cur, w(31.5, NOW + H, NOW, "windows"));
  assert.equal(higher.used_percentage, 31.5);
  assert.equal(higher.source, "windows");
});

test("mergeWindow: identical report is a no-op (same object)", () => {
  const cur = w(30, NOW + H, NOW);
  assert.equal(mergeWindow(cur, { ...cur }), cur);
  assert.equal(mergeWindow(cur, null), cur);
  const inc = w(1, NOW + H, NOW);
  assert.equal(mergeWindow(null, inc), inc);
});

test("mergeWindow: a later reset means a new window, even with lower usage", () => {
  const cur = w(95, NOW - 10, NOW - 4 * H);
  const next = w(2, NOW + 5 * H, NOW);
  assert.equal(mergeWindow(cur, next), next);
});

test("mergeWindow: stale report from an older window is ignored", () => {
  const cur = w(5, NOW + 5 * H, NOW);
  const stale = w(90, NOW - 60, NOW - 30); // earlier window, not clearly newer
  assert.equal(mergeWindow(cur, stale), cur);
});

test("mergeWindow: an earlier reset is trusted when clearly newer", () => {
  const cur = w(40, NOW + 6 * 24 * H, NOW - 3 * H);
  const moved = w(10, NOW + 2 * 24 * H, NOW);
  assert.equal(mergeWindow(cur, moved), moved);
});

test("applyReport tracks changes and updated_at", () => {
  const first = applyReport({}, { five_hour: w(10, NOW + H, NOW - 50), seven_day: w(40, NOW + 99 * H, NOW - 20) });
  assert.equal(first.changed, true);
  assert.equal(first.state.updated_at, NOW - 20);
  const again = applyReport(first.state, { five_hour: w(10, NOW + H, NOW - 50) });
  assert.equal(again.changed, false);
  assert.equal(again.state, first.state);
  const more = applyReport(first.state, { five_hour: w(12, NOW + H, NOW) });
  assert.equal(more.changed, true);
  assert.equal(more.state.five_hour.used_percentage, 12);
  assert.equal(more.state.seven_day.used_percentage, 40);
  assert.equal(more.state.updated_at, NOW);
  assert.equal(first.state.five_hour.used_percentage, 10, "input state is not mutated");
});

test("present always has both keys", () => {
  assert.deepEqual(present(null, NOW), { v: 1, server_time: NOW, updated_at: null, five_hour: null, seven_day: null });
});

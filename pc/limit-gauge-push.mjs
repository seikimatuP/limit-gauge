#!/usr/bin/env node
// limit-gauge-push — sends Claude Code's rate-limit values to your Limit Gauge relay.
//
// Claude Code runs the status line command after each reply and passes JSON on stdin; for Pro/Max
// accounts that JSON includes rate_limits.five_hour / seven_day (used_percentage, resets_at).
// In status line mode this script:
//   1. runs your previous status line command (e.g. ccstatusline) with the same stdin and prints its
//      output unchanged — or prints a short default line if you had none;
//   2. when the limits changed, sends them to the relay from a detached background process, so the
//      status line never waits on the network.
// No Claude credentials are read or sent: only the two percentages and their reset times.
//
// Commands (Node 18+, no dependencies):
//   node limit-gauge-push.mjs install --url <relay url> --token <token>   hook into ~/.claude/settings.json
//   node limit-gauge-push.mjs uninstall [--purge]                          put the previous status line back
//   node limit-gauge-push.mjs status                                       show config and what the relay holds
//   node limit-gauge-push.mjs                                              status line mode (run by Claude Code)

import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const SELF = fileURLToPath(import.meta.url);
const CLAUDE_DIR = process.env.CLAUDE_CONFIG_DIR || path.join(os.homedir(), ".claude");
const SETTINGS = path.join(CLAUDE_DIR, "settings.json");
const CONFIG = path.join(CLAUDE_DIR, "limit-gauge.json");
const STATE = path.join(CLAUDE_DIR, "limit-gauge.state.json");
const INSTALLED_SCRIPT = path.join(CLAUDE_DIR, "limit-gauge-push.mjs");
const MARKER = "limit-gauge-push";

/** Send even small changes once this long has passed since the last send. */
const HEARTBEAT_S = 10 * 60;
/** Forget sessions not seen for this long. */
const SESSION_TTL_S = 3 * 24 * 3600;
const IS_WSL = process.platform === "linux" && /microsoft/i.test(os.release());

// ---------------------------------------------------------------------------------------------
// Small helpers

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    return null;
  }
}

function writeJsonAtomic(file, data, mode) {
  const tmp = `${file}.${process.pid}.${Date.now()}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2) + "\n", mode ? { mode } : undefined);
  try {
    fs.renameSync(tmp, file);
  } catch (e) {
    try {
      fs.unlinkSync(tmp);
    } catch {}
    throw e;
  }
}

function nowS() {
  return Math.floor(Date.now() / 1000);
}

function endpointFor(base) {
  const u = new URL(base.trim());
  if (u.pathname === "" || u.pathname === "/") u.pathname = "/v1/usage";
  return u.toString();
}

function defaultSource() {
  return `${os.hostname()}${IS_WSL ? " (WSL)" : ""}`.slice(0, 64);
}

function debug(msg) {
  if (!process.env.LIMIT_GAUGE_DEBUG) return;
  try {
    fs.appendFileSync(path.join(CLAUDE_DIR, "limit-gauge.log"), `${new Date().toISOString()} ${msg}\n`);
  } catch {}
}

/** "ECONNREFUSED", "ENOTFOUND", "TimeoutError"… rather than undici's generic "fetch failed". */
function errorDetail(e) {
  const c = e?.cause;
  return String(c?.code || c?.errors?.[0]?.code || (e?.name === "TimeoutError" ? "TimeoutError" : "") || c?.message || e?.message || e);
}

/** Epoch seconds from seconds, milliseconds, numeric strings or ISO-8601; null when unusable. */
function toEpochSeconds(v) {
  if (typeof v === "string" && v.trim() !== "" && !/^\d+(\.\d+)?$/.test(v.trim())) {
    const t = Date.parse(v);
    return Number.isNaN(t) ? null : Math.floor(t / 1000);
  }
  const n = Number(v);
  if (v == null || v === "" || !Number.isFinite(n) || n <= 0) return null;
  return Math.floor(n > 1e12 ? n / 1000 : n);
}

function pickWindow(w) {
  if (!w || typeof w !== "object") return null;
  const used = Number(w.used_percentage);
  const resets = toEpochSeconds(w.resets_at);
  if (w.used_percentage == null || !Number.isFinite(used) || resets == null) return null;
  return { used_percentage: Math.round(used * 10) / 10, resets_at: resets };
}

/** Remembers what the last status line run received, so `status` can explain a missing push. */
function recordSeen(data) {
  try {
    const state = readJson(STATE) || {};
    state.lastSeen = {
      at: nowS(),
      version: data?.version ?? null,
      session: data?.session_id ?? null,
      rate_limits: data?.rate_limits === undefined ? "(なし)" : data.rate_limits,
    };
    writeJsonAtomic(STATE, state);
  } catch (e) {
    debug(`recordSeen failed: ${e}`);
  }
}

function windowKey(five, seven) {
  return [five?.used_percentage, five?.resets_at, seven?.used_percentage, seven?.resets_at].map((v) => v ?? "").join("|");
}

/** True when the change is worth sending right away (whole percent changed, or a window reset). */
function changedEnough(last, five, seven) {
  const pct = (w) => (w ? Math.floor(w.used_percentage) : -1);
  const reset = (w) => (w ? w.resets_at : 0);
  return (
    pct(last.five) !== pct(five) ||
    pct(last.seven) !== pct(seven) ||
    Math.abs(reset(last.five) - reset(five)) > 60 ||
    Math.abs(reset(last.seven) - reset(seven)) > 60
  );
}

// ---------------------------------------------------------------------------------------------
// Status line mode

function readStdin() {
  return new Promise((resolve) => {
    if (process.stdin.isTTY) return resolve("");
    let buf = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (d) => (buf += d));
    process.stdin.on("end", () => resolve(buf));
    process.stdin.on("error", () => resolve(buf));
  });
}

function findGitBash() {
  const candidates = [
    process.env.CLAUDE_CODE_GIT_BASH_PATH,
    "C:\\Program Files\\Git\\bin\\bash.exe",
    "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, "Programs", "Git", "bin", "bash.exe"),
  ];
  return candidates.find((p) => p && fs.existsSync(p)) || null;
}

/** Same shell Claude Code would use: sh on macOS/Linux; Git Bash, else PowerShell, on Windows. */
function shellFor(config) {
  if (config.wrappedShell) return config.wrappedShell;
  if (process.platform !== "win32") return true;
  return findGitBash() || "powershell.exe";
}

function runWrapped(config, input) {
  return new Promise((resolve) => {
    let child;
    try {
      child = spawn(config.wrappedCommand, {
        shell: shellFor(config),
        stdio: ["pipe", "inherit", "inherit"],
        windowsHide: true,
        env: process.env,
      });
    } catch (e) {
      debug(`wrapped spawn failed: ${e}`);
      return resolve(1);
    }
    child.on("error", (e) => {
      debug(`wrapped error: ${e}`);
      resolve(1);
    });
    child.on("close", (code) => resolve(code ?? 0));
    child.stdin.on("error", () => {}); // the command may not read stdin at all
    child.stdin.end(input);
  });
}

function hhmm(epochS) {
  const d = new Date(epochS * 1000);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

/** Printed when you had no status line before: "Opus · ctx 23% · 5h 23% (15:00) · 7d 54%". */
function defaultLine(d) {
  if (!d || typeof d !== "object") return "";
  const parts = [];
  if (d.model?.display_name) parts.push(d.model.display_name);
  const ctx = Number(d.context_window?.used_percentage);
  if (d.context_window?.used_percentage != null && Number.isFinite(ctx)) parts.push(`ctx ${Math.round(ctx)}%`);
  const five = pickWindow(d.rate_limits?.five_hour);
  const seven = pickWindow(d.rate_limits?.seven_day);
  if (five) parts.push(`5h ${Math.round(five.used_percentage)}% (${hhmm(five.resets_at)})`);
  if (seven) parts.push(`7d ${Math.round(seven.used_percentage)}%`);
  return parts.join(" · ");
}

/** Decides whether this status line run carries new limits, and if so sends them in the background. */
function maybePush(config, data) {
  const five = pickWindow(data.rate_limits?.five_hour);
  const seven = pickWindow(data.rate_limits?.seven_day);
  if (!five && !seven) return;
  const now = nowS();
  const key = windowKey(five, seven);
  const sid = String(data.session_id || "unknown");
  const state = readJson(STATE) || {};
  state.sessions = state.sessions && typeof state.sessions === "object" ? state.sessions : {};

  // Status lines also re-run without a new reply (mode changes, vim, timers). Only a change in this
  // session's own values means Claude Code got a fresh API response.
  if (state.sessions[sid]?.k === key) return;
  state.sessions[sid] = { k: key, t: now };
  for (const [id, s] of Object.entries(state.sessions)) {
    if (!s || now - (s.t || 0) > SESSION_TTL_S) delete state.sessions[id];
  }

  const last = state.lastPush;
  const due = !last || (last.k !== key && (changedEnough(last, five, seven) || now - (last.t || 0) >= HEARTBEAT_S));
  if (due) state.lastPush = { k: key, five, seven, t: now };
  try {
    writeJsonAtomic(STATE, state);
  } catch (e) {
    debug(`state write failed: ${e}`);
  }
  if (!due) return;

  const payload = { v: 1, source: config.source || defaultSource(), captured_at: now, five_hour: five, seven_day: seven };
  const child = spawn(process.execPath, [SELF, "--push", Buffer.from(JSON.stringify(payload)).toString("base64url")], {
    detached: true,
    stdio: "ignore",
    windowsHide: true,
  });
  child.on("error", (e) => debug(`push spawn failed: ${e}`));
  child.unref();
}

async function statusLineMode() {
  const input = await readStdin();
  let data = null;
  try {
    data = JSON.parse(input);
  } catch {}
  const config = readJson(CONFIG) || {};

  const wrapped = config.wrappedCommand ? runWrapped(config, input) : null;
  if (!wrapped) {
    const line = defaultLine(data);
    if (line) process.stdout.write(line + "\n");
  }
  try {
    if (data) recordSeen(data);
    if (config.url && config.token && data) maybePush(config, data);
  } catch (e) {
    debug(`maybePush failed: ${e?.stack || e}`);
  }
  process.exitCode = wrapped ? await wrapped : 0;
}

// ---------------------------------------------------------------------------------------------
// Background push (spawned detached by status line mode)

async function pushMode(b64) {
  const config = readJson(CONFIG);
  if (!config?.url || !config?.token) return;
  const payload = JSON.parse(Buffer.from(b64, "base64url").toString("utf8"));
  let ok = false;
  let detail = "";
  try {
    const res = await fetch(endpointFor(config.url), {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${config.token}`,
        "user-agent": "limit-gauge-push/1",
      },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(15000),
    });
    ok = res.ok;
    detail = `HTTP ${res.status}`;
  } catch (e) {
    detail = errorDetail(e);
  }
  debug(`push ${ok ? "ok" : "failed"} ${detail} ${JSON.stringify(payload)}`);

  const state = readJson(STATE) || {};
  const key = windowKey(payload.five_hour, payload.seven_day);
  if (ok) {
    state.lastOk = { at: nowS(), detail };
  } else {
    state.lastError = { at: nowS(), detail };
    // Let the next status line run try again.
    if (state.lastPush?.k === key) delete state.lastPush;
    for (const s of Object.values(state.sessions || {})) if (s?.k === key) s.k = "";
  }
  try {
    writeJsonAtomic(STATE, state);
  } catch {}
}

// ---------------------------------------------------------------------------------------------
// install / uninstall / status

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) {
      const [k, v] = a.slice(2).split("=", 2);
      if (v !== undefined) out[k] = v;
      else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) out[k] = argv[++i];
      else out[k] = true;
    } else out._.push(a);
  }
  return out;
}

function forwardSlashes(p) {
  return p.replace(/\\/g, "/");
}

function readSettingsOrExit() {
  if (!fs.existsSync(SETTINGS)) return {};
  const raw = fs.readFileSync(SETTINGS, "utf8");
  if (!raw.trim()) return {};
  try {
    const s = JSON.parse(raw);
    if (s && typeof s === "object" && !Array.isArray(s)) return s;
  } catch {}
  console.error(`✗ ${SETTINGS} を JSON として読めませんでした。変更せずに終了します。`);
  console.error(`  手動で statusLine.command を次に変更してください: node "${forwardSlashes(INSTALLED_SCRIPT)}"`);
  process.exit(1);
}

function backupSettings() {
  if (!fs.existsSync(SETTINGS)) return null;
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const backup = `${SETTINGS}.bak-limit-gauge-${stamp}`;
  fs.copyFileSync(SETTINGS, backup);
  return backup;
}

function mask(token) {
  return token ? `${token.slice(0, 4)}…${token.slice(-4)} (${token.length}文字)` : "(なし)";
}

async function fetchRelay(config) {
  const res = await fetch(endpointFor(config.url), {
    headers: { authorization: `Bearer ${config.token}`, "user-agent": "limit-gauge-push/1" },
    signal: AbortSignal.timeout(15000),
  });
  const body = await res.text();
  return { status: res.status, body };
}

function describeWindow(label, w) {
  if (!w) return `  ${label}: データなし`;
  const left = Math.max(0, Math.floor(100 - w.used_percentage + 1e-9));
  const expired = w.resets_at <= nowS();
  return `  ${label}: ${expired ? "リセット済み" : `残り${left}%（${new Date(w.resets_at * 1000).toLocaleString()} にリセット）`}`;
}

async function checkRelay(config) {
  try {
    const { status, body } = await fetchRelay(config);
    if (status === 200) {
      const d = JSON.parse(body);
      console.log(`✓ リレーに接続できました (${endpointFor(config.url)})`);
      console.log(describeWindow("5時間", d.five_hour));
      console.log(describeWindow("週間", d.seven_day));
      return true;
    }
    console.log(`✗ リレーが HTTP ${status} を返しました: ${body.slice(0, 200)}`);
    if (status === 401) console.log("  トークンが一致していません。");
  } catch (e) {
    console.log(`✗ リレーに接続できませんでした: ${errorDetail(e)}`);
  }
  return false;
}

async function install(args) {
  const url = typeof args.url === "string" ? args.url.trim() : "";
  const token = typeof args.token === "string" ? args.token.trim() : "";
  if (!/^https?:\/\/[^/\s]+/i.test(url) || !token || /\s/.test(token)) {
    console.error("使い方: node limit-gauge-push.mjs install --url https://limit-gauge-relay.<you>.workers.dev --token <token>");
    process.exit(2);
  }
  fs.mkdirSync(CLAUDE_DIR, { recursive: true });
  if (path.resolve(SELF) !== path.resolve(INSTALLED_SCRIPT)) fs.copyFileSync(SELF, INSTALLED_SCRIPT);

  const settings = readSettingsOrExit();
  const previous = readJson(CONFIG) || {};
  const current = settings.statusLine && typeof settings.statusLine === "object" ? settings.statusLine : null;
  let wrapped = previous.wrappedCommand ?? null;
  if (current?.command && !String(current.command).includes(MARKER)) wrapped = String(current.command);

  const ourCommand = `node "${forwardSlashes(INSTALLED_SCRIPT)}"`;
  const backup = backupSettings();
  settings.statusLine = { ...(current || {}), type: "command", command: ourCommand };
  writeJsonAtomic(SETTINGS, settings);

  const config = {
    url,
    token,
    source: typeof args.source === "string" ? args.source.slice(0, 64) : previous.source || defaultSource(),
    wrappedCommand: wrapped,
    wrappedShell: previous.wrappedShell ?? null,
  };
  writeJsonAtomic(CONFIG, config, 0o600);
  try {
    fs.chmodSync(CONFIG, 0o600);
  } catch {}

  console.log(`✓ Claude Code のステータスラインに送信処理を追加しました (${SETTINGS})`);
  if (wrapped) console.log(`  これまでのステータスライン「${wrapped}」はそのまま表示されます。`);
  else console.log("  ステータスラインが未設定だったので、簡易表示（モデル・ctx・5h・7d）を出します。");
  if (backup) console.log(`  元の設定のバックアップ: ${backup}`);
  console.log(`  設定ファイル: ${CONFIG}（トークンを含むので共有しないでください）`);
  await checkRelay(config);
  console.log("→ Claude Code で何か 1 回応答させると、リミットの値がリレーに送られます。");
}

function uninstall(args) {
  const config = readJson(CONFIG) || {};
  if (fs.existsSync(SETTINGS)) {
    const settings = readSettingsOrExit();
    const cmd = settings.statusLine?.command;
    if (typeof cmd === "string" && cmd.includes(MARKER)) {
      const backup = backupSettings();
      if (config.wrappedCommand) settings.statusLine = { ...settings.statusLine, command: config.wrappedCommand };
      else delete settings.statusLine;
      writeJsonAtomic(SETTINGS, settings);
      console.log(`✓ ステータスラインを元に戻しました${config.wrappedCommand ? `（${config.wrappedCommand}）` : "（未設定）"}`);
      if (backup) console.log(`  変更前のバックアップ: ${backup}`);
    } else {
      console.log("ステータスラインは limit-gauge-push を使っていません。settings.json は変更しません。");
    }
  }
  if (args.purge) {
    for (const f of [CONFIG, STATE, INSTALLED_SCRIPT, path.join(CLAUDE_DIR, "limit-gauge.log")]) {
      try {
        fs.unlinkSync(f);
        console.log(`  削除: ${f}`);
      } catch {}
    }
  } else {
    console.log(`  設定ファイル ${CONFIG} は残しています（--purge で削除）。`);
  }
}

async function status() {
  const config = readJson(CONFIG);
  const settings = readJson(SETTINGS) || {};
  const state = readJson(STATE) || {};
  console.log(`設定ディレクトリ: ${CLAUDE_DIR}`);
  console.log(`statusLine.command: ${settings.statusLine?.command ?? "(未設定)"}`);
  if (!config) {
    console.log("limit-gauge は未インストールです。install を実行してください。");
    return;
  }
  console.log(`リレー: ${config.url}`);
  console.log(`トークン: ${mask(config.token)}`);
  console.log(`送信元の名前: ${config.source}`);
  console.log(`元のステータスライン: ${config.wrappedCommand ?? "(なし)"}`);
  const fmt = (t) => (t ? new Date(t * 1000).toLocaleString() : "-");
  if (state.lastSeen) {
    console.log(`最後に Claude Code から呼ばれた: ${fmt(state.lastSeen.at)}（Claude Code ${state.lastSeen.version ?? "?"}）`);
    console.log(`  受け取った rate_limits: ${JSON.stringify(state.lastSeen.rate_limits)}`);
  } else {
    console.log("最後に Claude Code から呼ばれた: まだ一度も呼ばれていません（Claude Code で 1 回応答させてください）");
  }
  console.log(`最後の送信: ${fmt(state.lastPush?.t)} / 成功: ${fmt(state.lastOk?.at)}${state.lastError ? ` / 失敗: ${fmt(state.lastError.at)} ${state.lastError.detail}` : ""}`);
  await checkRelay(config);
}

// ---------------------------------------------------------------------------------------------

const argv = process.argv.slice(2);
try {
  if (argv[0] === "--push") {
    await pushMode(argv[1] || "");
  } else if (argv[0] === "install") {
    await install(parseArgs(argv.slice(1)));
  } else if (argv[0] === "uninstall") {
    uninstall(parseArgs(argv.slice(1)));
  } else if (argv[0] === "status") {
    await status();
  } else if (argv[0] === "help" || argv[0] === "--help" || argv[0] === "-h") {
    console.log(fs.readFileSync(SELF, "utf8").split("\n").slice(1, 20).map((l) => l.replace(/^\/\/ ?/, "")).join("\n"));
  } else {
    await statusLineMode();
  }
} catch (e) {
  debug(`fatal: ${e?.stack || e}`);
  if (argv.length && argv[0] !== "--push") {
    console.error(String(e?.message || e));
    process.exitCode = 1;
  }
}

#!/usr/bin/env node
// One-time setup, run from this folder after `npm install`:
//   1. log in to Cloudflare (browser) if needed
//   2. deploy the relay Worker (free plan is fine)
//   3. create a random token and store it as the Worker secret TOKEN
//   4. open the pairing page (QR code) for the phone
//   5. optionally hook the sender into Claude Code's status line on this machine
//
// Options: --new-token   rotate the token (the phone and every PC must be re-paired)
//          --no-open     don't open the browser
//          --yes         install the sender without asking

import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createInterface } from "node:readline/promises";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const LOCAL = path.join(HERE, ".relay.json");
const PUSH = path.join(HERE, "..", "pc", "limit-gauge-push.mjs");
const IS_WIN = process.platform === "win32";
const IS_WSL = process.platform === "linux" && /microsoft/i.test(os.release());
const args = new Set(process.argv.slice(2));

function run(cmdline, { input, echo = true, interactive = false } = {}) {
  return new Promise((resolve) => {
    const child = spawn(cmdline, {
      cwd: HERE,
      shell: true,
      stdio: interactive ? "inherit" : [input == null ? "inherit" : "pipe", "pipe", "pipe"],
      env: process.env,
    });
    let out = "";
    if (!interactive) {
      child.stdout.on("data", (d) => {
        out += d;
        if (echo) process.stdout.write(d);
      });
      child.stderr.on("data", (d) => {
        out += d;
        if (echo) process.stderr.write(d);
      });
      if (input != null) child.stdin.end(input);
    }
    child.on("error", (e) => resolve({ code: -1, out: out + String(e) }));
    child.on("close", (code) => resolve({ code, out }));
  });
}

function fail(msg) {
  console.error(`\n✗ ${msg}`);
  process.exit(1);
}

function openInBrowser(url) {
  let cmd;
  let cmdArgs;
  if (IS_WIN || IS_WSL) {
    cmd = "powershell.exe";
    cmdArgs = ["-NoProfile", "-NonInteractive", "-Command", `Start-Process '${url.replace(/'/g, "''")}'`];
  } else if (process.platform === "darwin") {
    cmd = "open";
    cmdArgs = [url];
  } else {
    cmd = "xdg-open";
    cmdArgs = [url];
  }
  try {
    const child = spawn(cmd, cmdArgs, { stdio: "ignore", detached: true, windowsHide: true });
    child.on("error", () => {});
    child.unref();
  } catch {}
}

async function ask(question, defaultYes = true) {
  if (args.has("--yes")) return true;
  if (!process.stdin.isTTY) return false;
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const answer = (await rl.question(`${question} ${defaultYes ? "[Y/n]" : "[y/N]"} `)).trim().toLowerCase();
  rl.close();
  return answer === "" ? defaultYes : answer.startsWith("y");
}

async function checkRelay(url, token) {
  for (let i = 0; i < 15; i++) {
    try {
      const res = await fetch(`${url}/v1/usage`, {
        headers: { authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(10000),
      });
      if (res.ok) return true;
      // A fresh secret can take a few seconds to reach every location.
    } catch {}
    await new Promise((r) => setTimeout(r, 2000));
  }
  return false;
}

async function main() {
  if (!fs.existsSync(path.join(HERE, "node_modules", "wrangler"))) {
    fail("先に `npm install` を実行してください（wrangler をインストールします）。");
  }

  console.log("== 1/5 Cloudflare ログインの確認");
  let who = await run("npx wrangler whoami", { echo: false });
  if (who.code !== 0 || /not authenticated|You are not logged in/i.test(who.out)) {
    console.log("ブラウザで Cloudflare にログインします…");
    const login = await run("npx wrangler login", { interactive: true });
    if (login.code !== 0) fail("wrangler login に失敗しました。");
    who = await run("npx wrangler whoami", { echo: false });
    if (who.code !== 0) fail("Cloudflare にログインできていません。`npx wrangler login` を確認してください。");
  }
  console.log("✓ ログイン済み");

  console.log("\n== 2/5 リレーをデプロイ");
  const deploy = await run("npx wrangler deploy");
  if (deploy.code !== 0) fail("デプロイに失敗しました。上のエラーを確認してください。");
  const found = deploy.out.match(/https:\/\/[a-z0-9.-]+\.workers\.dev/i);
  let saved = {};
  try {
    saved = JSON.parse(fs.readFileSync(LOCAL, "utf8"));
  } catch {}
  let url = found ? found[0] : saved.url;
  if (!url) {
    if (!process.stdin.isTTY) fail("デプロイ先 URL を取得できませんでした。");
    const rl = createInterface({ input: process.stdin, output: process.stdout });
    url = (await rl.question("デプロイされた URL（https://…workers.dev）を入力してください: ")).trim();
    rl.close();
  }
  url = url.replace(/\/+$/, "");
  if (!/^https:\/\/[^/\s]+$/i.test(url)) fail(`URL が正しくありません: ${url}`);
  console.log(`✓ ${url}`);

  console.log("\n== 3/5 トークンを設定");
  const rotate = args.has("--new-token") || !saved.token;
  const token = rotate ? randomBytes(32).toString("base64url") : saved.token;
  const secret = await run("npx wrangler secret put TOKEN", { input: `${token}\n` });
  if (secret.code !== 0) fail("TOKEN シークレットの設定に失敗しました。");
  fs.writeFileSync(LOCAL, JSON.stringify({ url, token }, null, 2) + "\n", { mode: 0o600 });
  console.log(`✓ ${rotate ? "新しいトークンを作成" : "既存のトークンを再設定"}しました（${path.basename(LOCAL)} に保存。共有しないでください）`);

  process.stdout.write("  リレーの応答を確認中… ");
  console.log((await checkRelay(url, token)) ? "OK" : "まだ応答しません（数十秒後に再確認してください）");

  console.log("\n== 4/5 スマホとペアリング");
  const pairUrl = `${url}/pair#t=${encodeURIComponent(token)}`;
  console.log("次のページを PC のブラウザで開き、表示された QR コードをスマホで読み取ってください:");
  console.log(`  ${pairUrl}`);
  if (!args.has("--no-open")) openInBrowser(pairUrl);

  console.log("\n== 5/5 Claude Code への送信処理");
  const installCmd = `node "${PUSH.replace(/\\/g, "/")}" install --url ${url} --token ${token}`;
  if (await ask("この環境の Claude Code（~/.claude/settings.json）に送信処理を追加しますか？")) {
    const res = await run(installCmd, { interactive: true });
    if (res.code !== 0) console.log("✗ 追加に失敗しました。上のメッセージを確認してください。");
  } else {
    console.log("あとで追加する場合は次を実行してください:");
    console.log(`  ${installCmd}`);
  }

  console.log("\n別の環境（例: WSL と Windows の両方）でも Claude Code を使う場合は、その環境の pc フォルダで:");
  console.log(`  node limit-gauge-push.mjs install --url ${url} --token ${token}`);
  console.log("\n完了です。Claude Code が次に応答すると、ウィジェットに値が表示されます。");
}

main().catch((e) => fail(e?.stack || String(e)));

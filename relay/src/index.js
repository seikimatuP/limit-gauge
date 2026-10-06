// Limit Gauge relay: receives Claude Code rate-limit values from your PC and serves them to the
// Android widget. One SQLite-backed Durable Object per feed keeps the latest state (free plan OK).
//
//   POST /v1/usage[/<feed>]   (Bearer TOKEN)  report from limit-gauge-push.mjs
//   GET  /v1/usage[/<feed>]   (Bearer TOKEN)  current state for the app
//   GET  /pair#t=<token>                       pairing page (QR on PC, "open in app" on Android)

import { DurableObject } from "cloudflare:workers";
import { applyReport, normalizeReport, present } from "./logic.js";
import PAIR_HTML from "./pair.html";
import PAIR_JS from "./pair.js.txt";
import QR_JS from "./vendor/qrcode-1.4.4.js.txt";

const MAX_BODY_BYTES = 16 * 1024;

export class UsageStore extends DurableObject {
  async read() {
    return (await this.ctx.storage.get("state")) ?? null;
  }

  async merge(report) {
    const current = (await this.ctx.storage.get("state")) ?? {};
    const { changed, state } = applyReport(current, report);
    if (changed) await this.ctx.storage.put("state", state);
    return { changed, state };
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    const isRead = request.method === "GET" || request.method === "HEAD";

    if (isRead && path === "/") return text("Limit Gauge relay is running.\n");
    if (isRead && path === "/pair") return page(PAIR_HTML);
    if (isRead && path === "/pair.js") return script(PAIR_JS);
    if (isRead && path === "/qrcode.js") return script(QR_JS);

    const match = path.match(/^\/v1\/usage(?:\/([A-Za-z0-9_-]{1,64}))?$/);
    if (!match) return json({ error: "not_found" }, 404);
    if (!env.TOKEN) {
      return json({ error: "server_not_configured", hint: "npx wrangler secret put TOKEN" }, 500);
    }
    if (!(await authorized(request, env.TOKEN))) {
      return json({ error: "unauthorized" }, 401, { "www-authenticate": "Bearer" });
    }

    const store = env.USAGE_STORE.get(env.USAGE_STORE.idFromName(match[1] || "default"));
    const nowS = Math.floor(Date.now() / 1000);

    if (isRead) return json(present(await store.read(), nowS));

    if (request.method === "POST" || request.method === "PUT") {
      const raw = await request.text();
      if (raw.length > MAX_BODY_BYTES) return json({ error: "too_large" }, 413);
      let body;
      try {
        body = JSON.parse(raw);
      } catch {
        return json({ error: "invalid_json" }, 400);
      }
      const report = normalizeReport(body, nowS);
      if (!report) return json({ error: "no_rate_limits" }, 422);
      const { changed, state } = await store.merge(report);
      return json({ ok: true, changed, ...present(state, nowS) });
    }

    return json({ error: "method_not_allowed" }, 405, { allow: "GET, HEAD, POST, PUT" });
  },
};

async function authorized(request, token) {
  const header = request.headers.get("authorization") || "";
  const m = header.match(/^Bearer\s+(\S+)\s*$/i);
  if (!m) return false;
  const enc = new TextEncoder();
  const given = enc.encode(m[1]);
  const expected = enc.encode(token);
  if (given.byteLength !== expected.byteLength) return false;
  return crypto.subtle.timingSafeEqual(given, expected);
}

const NO_STORE = { "cache-control": "no-store" };

function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...NO_STORE, ...headers },
  });
}

function text(body) {
  return new Response(body, { headers: { "content-type": "text/plain; charset=utf-8", ...NO_STORE } });
}

function page(html) {
  return new Response(html, {
    headers: {
      "content-type": "text/html; charset=utf-8",
      ...NO_STORE,
      "referrer-policy": "no-referrer",
      "x-content-type-options": "nosniff",
      "content-security-policy":
        "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; img-src data:; " +
        "base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    },
  });
}

function script(js) {
  return new Response(js, {
    headers: {
      "content-type": "text/javascript; charset=utf-8",
      "cache-control": "public, max-age=3600",
      "x-content-type-options": "nosniff",
    },
  });
}

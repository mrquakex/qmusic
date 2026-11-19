// qeyf-media: where the photos, voice messages, videos and files of Qeyf's messages are kept (not in Firebase, whose
// free plan holds 1 GB and serves 10 GB a month).
//
//   POST /media/upload?chat=<chatId>&ext=<jpg|m4a|mp4|pdf|...>   body: the file   Authorization: Bearer <Firebase ID token>
//     → 200 {"url": "https://<host>/m/<chatId>/<32 hex>.<ext>", "size": n}
//
// Only a member of the conversation (one of the two people, or a member of the group) can upload to it. The files are
// served by nginx straight from disk under /m/ with a name nobody can guess (128 random bits): whoever has the message
// has the link, like WhatsApp's media links.
//
//   POST /media/transcribe   body: a voice message (m4a/ogg/mp3, up to 10 MB)   → {"text": "..."}
//   POST /media/translate    {"text": "...", "to": "tr"|"en"|...}                 → {"text": "..."}
//   POST /media/speak        {"text": "..."}                                       → audio/mpeg (read aloud, voice "kaan")
// Both are done by EVREN (evren-llmapi.ssyz.org.tr: qwen3-asr for speech, deepseek for translation) with the key in
// /etc/qmusic/evren-key, which never leaves this server. Nothing is kept here; each person has an hourly allowance.
const admin = require("firebase-admin");
const crypto = require("crypto");
const fs = require("fs");
const http = require("http");
const path = require("path");

const DATABASE_URL = "https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app";
const ROOT = "/var/lib/qeyf-media";
const PORT = 8790;
const HOST = process.env.MEDIA_HOST || "qeyf.159.195.147.140.sslip.io";
const MAX_BYTES = 100 * 1024 * 1024;
const EXT = /^[a-z0-9]{1,8}$/;
const DM = /^[A-Za-z0-9]+_[A-Za-z0-9]+$/;
const GROUP = /^grp[A-Za-z0-9]{20}$/;

admin.initializeApp({
  credential: admin.credential.cert(JSON.parse(fs.readFileSync("/etc/qmusic/firebase-key.json", "utf8"))),
  databaseURL: DATABASE_URL,
});
const db = admin.database();
const log = (...args) => console.log(new Date().toISOString(), ...args);

async function isMember(chatId, uid) {
  if (DM.test(chatId)) return chatId.startsWith(uid + "_") || chatId.endsWith("_" + uid);
  if (GROUP.test(chatId)) return (await db.ref(`groups/${chatId}/members/${uid}`).get()).exists();
  return false;
}

function reply(res, code, body) {
  res.writeHead(code, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

async function upload(req, res, url) {
  const chatId = url.searchParams.get("chat") || "";
  const ext = (url.searchParams.get("ext") || "bin").toLowerCase();
  if (!EXT.test(ext) || !(DM.test(chatId) || GROUP.test(chatId))) return reply(res, 400, { error: "bad request" });
  const token = (req.headers.authorization || "").replace(/^Bearer /, "");
  let uid;
  try {
    uid = (await admin.auth().verifyIdToken(token)).uid;
  } catch (_) {
    return reply(res, 401, { error: "sign in" });
  }
  if (!(await isMember(chatId, uid))) return reply(res, 403, { error: "not a member" });
  // Banned or muted by an admin: nothing goes up until it ends.
  const ban = (await db.ref(`bans/${uid}/until`).get()).val();
  if (typeof ban === "number" && ban > Date.now()) return reply(res, 403, { error: "banned" });
  const length = Number(req.headers["content-length"] || 0);
  if (length > MAX_BYTES) return reply(res, 413, { error: "too large" });

  const dir = path.join(ROOT, chatId);
  fs.mkdirSync(dir, { recursive: true });
  const name = crypto.randomBytes(16).toString("hex") + "." + ext;
  const file = path.join(dir, name);
  const tmp = file + ".part";
  const out = fs.createWriteStream(tmp);
  let size = 0;
  let failed = false;
  req.on("data", (chunk) => {
    size += chunk.length;
    if (size > MAX_BYTES && !failed) {
      failed = true;
      req.destroy();
      out.destroy();
      fs.rm(tmp, () => {});
      reply(res, 413, { error: "too large" });
    }
  });
  req.pipe(out);
  out.on("finish", () => {
    if (failed) return;
    fs.renameSync(tmp, file);
    log("upload", chatId.length, ext, size);
    reply(res, 200, { url: `https://${HOST}/m/${chatId}/${name}`, size });
  });
  out.on("error", () => {
    if (failed) return;
    failed = true;
    fs.rm(tmp, () => {});
    reply(res, 500, { error: "write failed" });
  });
  req.on("aborted", () => {
    failed = true;
    fs.rm(tmp, () => {});
  });
}

// ------------------------------------------------------------------ GIFs and stickers (KLIPY)
//
//   GET /media/gifs?kind=gifs|stickers&q=<words>&page=<n>   Authorization: Bearer <Firebase ID token>
//     → 200 {"items": [{"id", "preview", "url", "w", "h"}], "more": bool}
//
// The KLIPY key stays here (/etc/qmusic/klipy-key); the app never holds it. Each person is a stable, anonymous
// customer id (a hash of their uid) so KLIPY can keep their recent ones without learning who they are.
const KLIPY = "https://api.klipy.com/api/v1";
const gifCache = new Map();

function klipyKey() {
  try {
    return fs.readFileSync("/etc/qmusic/klipy-key", "utf8").trim();
  } catch (_) {
    return "";
  }
}

/** The best of a KLIPY item's files for a size: an animated picture (GIF or WebP) the phone can show. */
function pick(file, sizes) {
  for (const size of sizes) {
    const f = file && file[size];
    if (!f) continue;
    const one = f.webp || f.gif;
    if (one && one.url) return { url: one.url, w: Number(one.width) || 0, h: Number(one.height) || 0 };
  }
  return null;
}

async function gifs(req, res, url) {
  const token = (req.headers.authorization || "").replace(/^Bearer /, "");
  let uid;
  try {
    uid = (await admin.auth().verifyIdToken(token)).uid;
  } catch (_) {
    return reply(res, 401, { error: "sign in" });
  }
  const key = klipyKey();
  if (!key) return reply(res, 503, { error: "no gif key" });
  const kind = url.searchParams.get("kind") === "stickers" ? "stickers" : "gifs";
  const q = (url.searchParams.get("q") || "").trim().slice(0, 60);
  const page = Math.max(1, Math.min(20, Number(url.searchParams.get("page")) || 1));
  const customer = crypto.createHash("sha256").update("qeyf:" + uid).digest("hex").slice(0, 32);
  const query = new URLSearchParams({ page: String(page), per_page: "30", customer_id: customer, locale: "tr", content_filter: "medium" });
  if (q) query.set("q", q);
  const target = `${KLIPY}/${key}/${kind}/${q ? "search" : "trending"}?${query}`;
  const cacheKey = `${kind}|${q.toLowerCase()}|${page}`;
  const hit = gifCache.get(cacheKey);
  if (hit && Date.now() - hit.at < 10 * 60 * 1000) return reply(res, 200, hit.body);
  try {
    const r = await fetch(target, { signal: AbortSignal.timeout(8000) });
    const json = await r.json();
    const list = (json && json.data && json.data.data) || [];
    const items = list
      .map((it) => {
        const preview = pick(it.file, ["sm", "xs", "md"]);
        const full = pick(it.file, ["md", "hd", "sm"]);
        if (!preview || !full) return null;
        return { id: String(it.slug || it.id), preview: preview.url, url: full.url, w: full.w, h: full.h };
      })
      .filter(Boolean);
    const body = { items, more: !!(json && json.data && json.data.has_next) };
    gifCache.set(cacheKey, { at: Date.now(), body });
    if (gifCache.size > 500) gifCache.delete(gifCache.keys().next().value);
    reply(res, 200, body);
  } catch (e) {
    log("gifs failed:", e.message);
    reply(res, 502, { error: "gif service" });
  }
}

// ------------------------------------------------------------------ the key vault (end-to-end encryption recovery)
//
// A person's encryption key is backed up in Firebase locked with their 6-digit PIN AND a 32-byte secret only this
// server holds. The server gives the secret only to the right PIN's proof, and after 10 wrong tries in a row it forgets
// it for good (the backup can then never be opened), so the PIN cannot be guessed by trying them all.
//
//   POST /media/vault/store  {proof}          → {secret}   a new secret for this PIN (replaces the old one)
//   POST /media/vault/open   {proof}          → {secret}   | 403 {left} | 410 (locked for good) | 404 (none)
//   POST /media/vault/check                   → {exists, left}
//   POST /media/vault/forget                  → {}         the person wipes it (a forgotten PIN: start again)
//
// proof = PBKDF2(PIN, salt, "proof"), from the phone; kept here only as a salted SHA-256.
const VAULT = "/var/lib/qeyf-vault";
const VAULT_TRIES = 10;
fs.mkdirSync(VAULT, { recursive: true, mode: 0o700 });

function vaultFile(uid) {
  return path.join(VAULT, crypto.createHash("sha256").update("uid:" + uid).digest("hex") + ".json");
}
function readVault(uid) {
  try {
    return JSON.parse(fs.readFileSync(vaultFile(uid), "utf8"));
  } catch (_) {
    return null;
  }
}
function writeVault(uid, v) {
  const f = vaultFile(uid);
  fs.writeFileSync(f + ".tmp", JSON.stringify(v), { mode: 0o600 });
  fs.renameSync(f + ".tmp", f);
}
function readJson(req, limit = 4096) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (c) => {
      size += c.length;
      if (size > limit) {
        reject(new Error("too large"));
        req.destroy();
      } else chunks.push(c);
    });
    req.on("end", () => {
      try {
        resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on("error", reject);
  });
}
// ------------------------------------------------------------------ voice to text, translation (EVREN)

const EVREN = "https://evren-llmapi.ssyz.org.tr/v1";
const { execFile } = require("child_process");
const os = require("os");
let evrenKeyCache = null;
function evrenKey() {
  if (evrenKeyCache == null) evrenKeyCache = (() => { try { return fs.readFileSync("/etc/qmusic/evren-key", "utf8").trim(); } catch (_) { return ""; } })();
  return evrenKeyCache;
}

/** Each person's allowance: [what] at most [max] times an hour. */
const allowance = new Map();
function allowed(uid, what, max) {
  const key = what + ":" + uid;
  const now = Date.now();
  const recent = (allowance.get(key) || []).filter((t) => now - t < 3600e3);
  if (recent.length >= max) return false;
  recent.push(now);
  allowance.set(key, recent);
  return true;
}

async function signedIn(req) {
  const token = (req.headers.authorization || "").replace(/^Bearer /, "");
  try {
    return (await admin.auth().verifyIdToken(token)).uid;
  } catch (_) {
    return null;
  }
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (c) => {
      size += c.length;
      if (size > limit) {
        reject(new Error("too large"));
        req.destroy();
      } else chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

const run = (cmd, args) => new Promise((resolve, reject) => execFile(cmd, args, { timeout: 60e3 }, (e, out) => (e ? reject(e) : resolve(out))));

async function evrenAsr(file) {
  const form = new FormData();
  form.append("model", "qwen3-asr-1.7b");
  form.append("file", new Blob([fs.readFileSync(file)], { type: "audio/wav" }), "piece.wav");
  const r = await fetch(EVREN + "/audio/transcriptions", { method: "POST", headers: { "X-API-Key": evrenKey() }, body: form, signal: AbortSignal.timeout(60e3) });
  if (!r.ok) throw new Error("asr " + r.status);
  return String((await r.json()).text || "").trim();
}

async function transcribe(req, res) {
  const uid = await signedIn(req);
  if (!uid) return reply(res, 401, { error: "sign in" });
  if (!evrenKey()) return reply(res, 503, { error: "not set up" });
  if (!allowed(uid, "asr", 60)) return reply(res, 429, { error: "later" });
  const audio = await readBody(req, 10 * 1024 * 1024);
  if (audio.length < 100) return reply(res, 400, { error: "empty" });
  // The model hears about 30 seconds at a time: the message goes in 25-second pieces, read one after the other.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "asr-"));
  try {
    const input = path.join(dir, "in");
    fs.writeFileSync(input, audio);
    await run("ffmpeg", ["-loglevel", "error", "-i", input, "-t", "600", "-ac", "1", "-ar", "16000", "-f", "segment", "-segment_time", "25", path.join(dir, "p%03d.wav")]);
    const pieces = fs.readdirSync(dir).filter((f) => /^p\d+\.wav$/.test(f)).sort();
    const parts = [];
    for (const p of pieces) parts.push(await evrenAsr(path.join(dir, p)));
    const text = parts.filter(Boolean).join(" ").replace(/\s+/g, " ").trim();
    log("transcribed", pieces.length, "pieces");
    reply(res, 200, { text });
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

const LANGS = { tr: "Turkish", en: "English", de: "German", fr: "French", es: "Spanish", ar: "Arabic", ru: "Russian", it: "Italian", nl: "Dutch", az: "Azerbaijani", ku: "Kurdish", fa: "Persian" };

async function translate(req, res) {
  const uid = await signedIn(req);
  if (!uid) return reply(res, 401, { error: "sign in" });
  if (!evrenKey()) return reply(res, 503, { error: "not set up" });
  if (!allowed(uid, "tr", 300)) return reply(res, 429, { error: "later" });
  const body = await readJson(req, 20000);
  const text = String(body.text || "").slice(0, 4000);
  const to = LANGS[String(body.to || "tr")] || "Turkish";
  if (!text.trim()) return reply(res, 400, { error: "empty" });
  const r = await fetch(EVREN + "/chat/completions", {
    method: "POST",
    headers: { "X-API-Key": evrenKey(), "Content-Type": "application/json" },
    body: JSON.stringify({
      model: "deepseek-v4.1-flash",
      thinking: false,
      max_tokens: 2048,
      temperature: 0.2,
      messages: [
        { role: "system", content: `Translate the user's message into ${to}. Keep emoji, names and the tone. Reply with the translation only, nothing else. If it is already in ${to}, reply with it unchanged.` },
        { role: "user", content: text },
      ],
    }),
    signal: AbortSignal.timeout(45e3),
  });
  if (!r.ok) return reply(res, 502, { error: "translate " + r.status });
  const out = String(((await r.json()).choices || [])[0]?.message?.content || "").trim();
  reply(res, 200, { text: out });
}

async function speak(req, res) {
  const uid = await signedIn(req);
  if (!uid) return reply(res, 401, { error: "sign in" });
  if (!evrenKey()) return reply(res, 503, { error: "not set up" });
  if (!allowed(uid, "tts", 200)) return reply(res, 429, { error: "later" });
  const body = await readJson(req, 20000);
  const text = String(body.text || "").replace(/\s+/g, " ").trim().slice(0, 1500);
  if (!text) return reply(res, 400, { error: "empty" });
  const r = await fetch(EVREN + "/audio/speech", {
    method: "POST",
    headers: { "X-API-Key": evrenKey(), "Content-Type": "application/json" },
    body: JSON.stringify({ model: "voxcpm2", input: text, voice: "kaan", response_format: "mp3" }),
    signal: AbortSignal.timeout(60e3),
  });
  if (!r.ok) return reply(res, 502, { error: "speak " + r.status });
  const audio = Buffer.from(await r.arrayBuffer());
  res.writeHead(200, { "Content-Type": "audio/mpeg", "Content-Length": audio.length });
  res.end(audio);
}

const HEX64 = /^[0-9a-f]{64}$/;
// One caller at a time per person (two quick wrong tries cannot both count as the first).
const vaultBusy = new Set();

async function vault(req, res, action) {
  const token = (req.headers.authorization || "").replace(/^Bearer /, "");
  let uid;
  try {
    uid = (await admin.auth().verifyIdToken(token)).uid;
  } catch (_) {
    return reply(res, 401, { error: "sign in" });
  }
  if (vaultBusy.has(uid)) return reply(res, 429, { error: "busy" });
  vaultBusy.add(uid);
  try {
    const body = await readJson(req);
    const proof = String(body.proof || "");
    const v = readVault(uid);
    if (action === "check") {
      return reply(res, 200, { exists: !!(v && !v.locked), locked: !!(v && v.locked), left: v ? Math.max(0, VAULT_TRIES - v.fails) : VAULT_TRIES });
    }
    if (action === "forget") {
      try {
        fs.unlinkSync(vaultFile(uid));
      } catch (_) {}
      log("vault forgotten");
      return reply(res, 200, {});
    }
    if (!HEX64.test(proof)) return reply(res, 400, { error: "bad proof" });
    if (action === "store") {
      const salt = crypto.randomBytes(16).toString("hex");
      const secret = crypto.randomBytes(32).toString("hex");
      writeVault(uid, { salt, hash: crypto.createHash("sha256").update(salt + proof).digest("hex"), secret, fails: 0, locked: false, at: Date.now() });
      return reply(res, 200, { secret });
    }
    if (action === "open") {
      if (!v) return reply(res, 404, { error: "none" });
      if (v.locked) return reply(res, 410, { error: "locked" });
      const hash = crypto.createHash("sha256").update(v.salt + proof).digest("hex");
      const ok = crypto.timingSafeEqual(Buffer.from(hash, "hex"), Buffer.from(v.hash, "hex"));
      if (ok) {
        if (v.fails) writeVault(uid, { ...v, fails: 0 });
        return reply(res, 200, { secret: v.secret });
      }
      const fails = (v.fails || 0) + 1;
      if (fails >= VAULT_TRIES) {
        // Forgotten for good: the backup can no longer be opened by anyone.
        writeVault(uid, { locked: true, fails, at: Date.now() });
        log("vault locked after too many tries");
        return reply(res, 410, { error: "locked" });
      }
      writeVault(uid, { ...v, fails });
      return reply(res, 403, { error: "wrong", left: VAULT_TRIES - fails });
    }
    return reply(res, 404, { error: "not found" });
  } finally {
    vaultBusy.delete(uid);
  }
}

http
  .createServer((req, res) => {
    if (req.method === "GET" && req.url.startsWith("/media/gifs")) {
      gifs(req, res, new URL(req.url, "http://localhost")).catch(() => {
        if (!res.headersSent) reply(res, 500, { error: "failed" });
      });
      return;
    }
    const url = new URL(req.url, "http://localhost");
    if (req.method === "POST" && url.pathname === "/media/upload") {
      upload(req, res, url).catch((e) => {
        log("upload failed:", e.message);
        if (!res.headersSent) reply(res, 500, { error: "failed" });
      });
      return;
    }
    const ai = { "/media/transcribe": transcribe, "/media/translate": translate, "/media/speak": speak }[url.pathname];
    if (req.method === "POST" && ai) {
      ai(req, res).catch((e) => {
        log(url.pathname, "failed:", e.message);
        if (!res.headersSent) reply(res, 500, { error: "failed" });
      });
      return;
    }
    const vaultMatch = /^\/media\/vault\/(store|open|check|forget)$/.exec(url.pathname);
    if (req.method === "POST" && vaultMatch) {
      vault(req, res, vaultMatch[1]).catch((e) => {
        log("vault failed:", e.message);
        if (!res.headersSent) reply(res, 500, { error: "failed" });
      });
      return;
    }
    if (url.pathname === "/media/health") return reply(res, 200, { ok: true });
    reply(res, 404, { error: "not found" });
  })
  .listen(PORT, "127.0.0.1", () => log(`qeyf-media on ${PORT}`));

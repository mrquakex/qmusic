// qeyf-media: where the photos, voice messages, videos and files of Qeyf's messages are kept (not in Firebase, whose
// free plan holds 1 GB and serves 10 GB a month).
//
//   POST /media/upload?chat=<chatId>&ext=<jpg|m4a|mp4|pdf|...>   body: the file   Authorization: Bearer <Firebase ID token>
//     → 200 {"url": "https://<host>/m/<chatId>/<32 hex>.<ext>", "size": n}
//
// Only a member of the conversation (one of the two people, or a member of the group) can upload to it. The files are
// served by nginx straight from disk under /m/ with a name nobody can guess (128 random bits): whoever has the message
// has the link, like WhatsApp's media links.
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
    if (url.pathname === "/media/health") return reply(res, 200, { ok: true });
    reply(res, 404, { error: "not found" });
  })
  .listen(PORT, "127.0.0.1", () => log(`qeyf-media on ${PORT}`));

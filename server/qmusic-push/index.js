// qmusic-push: QMusic's small server, running beside coturn on the NakitPOS machine.
//
// 1. Notifications without Firebase's paid plan: the app writes a short entry to /notify when it sends a message or
//    starts a call; this service sends the other person's phones a push message (FCM) and removes the entry.
// 2. Credentials for the calls relay (coturn): every 12 hours it writes /turn, a username and password valid for 24
//    hours, made from coturn's shared secret (use-auth-secret), readable by signed-in users only.
//
// Files: /etc/qmusic/firebase-key.json (service account), /etc/qmusic/turn-secret (same as coturn's).
const admin = require("firebase-admin");
const crypto = require("crypto");
const fs = require("fs");

const DATABASE_URL = "https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app";
const TURN_HOST = "159.195.147.140";
const TURN_EVERY_MS = 12 * 3600 * 1000;
const TURN_VALID_S = 24 * 3600;
const MESSAGE_MAX_AGE_MS = 6 * 3600 * 1000;
const CALL_MAX_AGE_MS = 45 * 1000;
const DEAD_TOKEN = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

admin.initializeApp({
  credential: admin.credential.cert(JSON.parse(fs.readFileSync("/etc/qmusic/firebase-key.json", "utf8"))),
  databaseURL: DATABASE_URL,
});
const db = admin.database();
const turnSecret = fs.readFileSync("/etc/qmusic/turn-secret", "utf8").trim();

const log = (...args) => console.log(new Date().toISOString(), ...args);

// ------------------------------------------------------------------ calls relay credentials

function turnCredentials() {
  const expires = Math.floor(Date.now() / 1000) + TURN_VALID_S;
  const username = `${expires}:qmusic`;
  const credential = crypto.createHmac("sha1", turnSecret).update(username).digest("base64");
  return {
    urls: [`turn:${TURN_HOST}:3478?transport=udp`, `turn:${TURN_HOST}:3478?transport=tcp`, `stun:${TURN_HOST}:3478`],
    username,
    credential,
    expires: expires * 1000,
  };
}

async function publishTurn() {
  try {
    await db.ref("turn").set(turnCredentials());
    log("turn credentials written");
  } catch (e) {
    log("turn credentials failed:", e.message);
  }
}

// ------------------------------------------------------------------ notifications

const isMember = (chatId, uid) => chatId.startsWith(uid + "_") || chatId.endsWith("_" + uid);

async function handle(n) {
  if (!n || typeof n.from !== "string" || typeof n.to !== "string" || typeof n.chatId !== "string") return;
  if (n.from === n.to || !isMember(n.chatId, n.to)) return;
  // The owner's Claude speaks in the owner's conversations without being a member of them.
  if (n.from !== "claudebot" && !isMember(n.chatId, n.from)) return;
  const call = n.type === "call";
  const age = Date.now() - (Number(n.at) || 0);
  if (age > (call ? CALL_MAX_AGE_MS : MESSAGE_MAX_AGE_MS)) return;
  if ((await db.ref(`blocked/${n.to}/${n.from}`).get()).val() === true) return;

  const tokensByKey = (await db.ref(`pushTokens/${n.to}`).get()).val() || {};
  const keys = Object.keys(tokensByKey);
  if (keys.length === 0) return;
  // Claude speaking in someone's conversation: the notification is about that conversation (the other member),
  // so tapping it opens it; the text says it was Claude.
  const [m1, m2] = n.chatId.split("_");
  const partner = n.to === m1 ? m2 : m1;
  const viaClaude = n.from === "claudebot" && partner !== "claudebot";
  const shownFrom = viaClaude ? partner : n.from;
  const sender = (await db.ref(`users/${shownFrom}`).get()).val() || {};
  if (viaClaude) n.body = "Claude: " + String(n.body || "").replace(/^Claude: /, "");

  const data = {
    type: call ? "call" : "chat",
    chatId: n.chatId,
    from: shownFrom,
    name: String(sender.name || ""),
    username: String(sender.username || ""),
    photo: String(sender.photo || ""),
    body: String(n.body || "").slice(0, 300),
  };
  if (call) {
    data.callId = String(n.callId || "");
    data.video = n.video ? "1" : "0";
    data.action = n.action === "cancel" ? "cancel" : "ring";
  }
  const result = await admin.messaging().sendEachForMulticast({
    tokens: keys.map((k) => tokensByKey[k]),
    data,
    android: { priority: "high", ttl: call ? 30 * 1000 : 24 * 3600 * 1000 },
  });

  // Phones that uninstalled the app or signed out: their addresses are dropped.
  const removals = {};
  result.responses.forEach((r, i) => {
    if (!r.success && r.error && DEAD_TOKEN.has(r.error.code)) removals[keys[i]] = null;
  });
  if (Object.keys(removals).length > 0) await db.ref(`pushTokens/${n.to}`).update(removals);
  log(`${data.type} ${n.chatId.length} -> ${result.successCount}/${keys.length}`);
}

db.ref("notify").on("child_added", async (snap) => {
  const n = snap.val();
  snap.ref.remove().catch(() => {});
  try {
    await handle(n);
  } catch (e) {
    log("notify failed:", e.message);
  }
});

publishTurn();
setInterval(publishTurn, TURN_EVERY_MS);
log("qmusic-push started");

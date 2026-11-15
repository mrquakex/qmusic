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

/** Errors worth another try a moment later (Google's side, the network); the others will not change. */
const TRANSIENT = new Set(["messaging/internal-error", "messaging/server-unavailable", "messaging/unknown-error", "app/network-error"]);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Sends to every address, and tries the ones that failed for a passing reason twice more. */
async function sendWithRetry(message) {
  const result = await admin.messaging().sendEachForMulticast(message);
  for (const wait of [500, 1500]) {
    const again = [];
    result.responses.forEach((r, i) => {
      if (!r.success && r.error && TRANSIENT.has(r.error.code)) again.push(i);
    });
    if (again.length === 0) break;
    await sleep(wait);
    const retry = await admin.messaging().sendEachForMulticast({ ...message, tokens: again.map((i) => message.tokens[i]) });
    retry.responses.forEach((r, j) => {
      result.responses[again[j]] = r;
    });
  }
  result.successCount = result.responses.filter((r) => r.success).length;
  return result;
}

/** For a call: how many of the other person's phones were told, so the caller can say "Unavailable" at once. */
async function reportCallPush(n, ok) {
  if (n.type !== "call" || n.action === "cancel") return;
  await db.ref(`calls/${n.chatId}/push`).set({ id: String(n.callId || ""), ok, at: admin.database.ServerValue.TIMESTAMP }).catch(() => {});
}

const GROUP = /^grp[A-Za-z0-9]{20}$/;

/** A message in a group: every member but the sender gets it, titled with the group, "Name: text" as the body. */
async function handleGroup(n) {
  if (n.type !== "chat" || n.to !== n.chatId) return;
  if (Date.now() - (Number(n.at) || 0) > MESSAGE_MAX_AGE_MS) return;
  const group = (await db.ref(`groups/${n.chatId}`).get()).val();
  if (!group || !group.members) return;
  if (n.from !== "claudebot" && !group.members[n.from]) return;
  const senderName = n.from === "claudebot" ? "Claude" : String((group.members[n.from] || {}).name || "");
  const data = {
    type: "chat",
    chatId: n.chatId,
    from: n.chatId,
    name: String(group.name || "Grup"),
    username: "",
    photo: String(group.photo || ""),
    body: (senderName ? senderName + ": " : "") + String(n.body || "").replace(/^Claude: /, "").slice(0, 280),
    group: "1",
  };
  const targets = Object.keys(group.members).filter((uid) => uid !== n.from);
  await Promise.all(targets.map((uid) => deliver(uid, data, 24 * 3600 * 1000).catch((e) => log("group push failed:", e.message))));
}

/** Sends [data] to every phone of [uid]; drops the addresses of phones that are gone. Resolves how many got it. */
async function deliver(uid, data, ttl) {
  const tokensByKey = (await db.ref(`pushTokens/${uid}`).get()).val() || {};
  const keys = Object.keys(tokensByKey);
  if (keys.length === 0) return 0;
  const result = await sendWithRetry({ tokens: keys.map((k) => tokensByKey[k]), data, android: { priority: "high", ttl } });
  const removals = {};
  result.responses.forEach((r, i) => {
    if (!r.success && r.error && DEAD_TOKEN.has(r.error.code)) removals[keys[i]] = null;
  });
  if (Object.keys(removals).length > 0) await db.ref(`pushTokens/${uid}`).update(removals);
  log(`${data.type} group -> ${result.successCount}/${keys.length}`);
  return result.successCount;
}

async function handle(n) {
  if (!n || typeof n.from !== "string" || typeof n.to !== "string" || typeof n.chatId !== "string") return;
  if (GROUP.test(n.chatId)) return handleGroup(n);
  if (n.from === n.to || !isMember(n.chatId, n.to)) return;
  // The owner's Claude speaks in the owner's conversations without being a member of them.
  if (n.from !== "claudebot" && !isMember(n.chatId, n.from)) return;
  const call = n.type === "call";
  const friend = n.type === "friend";
  const age = Date.now() - (Number(n.at) || 0);
  if (age > (call ? CALL_MAX_AGE_MS : MESSAGE_MAX_AGE_MS)) return;
  if ((await db.ref(`blocked/${n.to}/${n.from}`).get()).val() === true) return;

  const tokensByKey = (await db.ref(`pushTokens/${n.to}`).get()).val() || {};
  const keys = Object.keys(tokensByKey);
  if (keys.length === 0) {
    await reportCallPush(n, 0);
    log(`${n.type} ${n.chatId.length} -> no push address`);
    return;
  }
  // Claude speaking in someone's conversation: the notification is about that conversation (the other member),
  // so tapping it opens it; the text says it was Claude.
  const [m1, m2] = n.chatId.split("_");
  const partner = n.to === m1 ? m2 : m1;
  const viaClaude = n.from === "claudebot" && partner !== "claudebot";
  const shownFrom = viaClaude ? partner : n.from;
  const sender = (await db.ref(`users/${shownFrom}`).get()).val() || {};
  if (viaClaude) n.body = "Claude: " + String(n.body || "").replace(/^Claude: /, "");

  const data = {
    type: call ? "call" : friend ? "friend" : "chat",
    chatId: n.chatId,
    from: shownFrom,
    name: String(sender.name || ""),
    username: String(sender.username || ""),
    photo: String(sender.photo || ""),
    body: String(n.body || "").slice(0, 300),
  };
  if (friend) data.action = n.action === "accepted" ? "accepted" : "request";
  if (call) {
    data.callId = String(n.callId || "");
    data.video = n.video ? "1" : "0";
    data.action = n.action === "cancel" ? "cancel" : "ring";
  }
  const result = await sendWithRetry({
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
  await reportCallPush(n, result.successCount);
  const errors = result.responses.filter((r) => !r.success).map((r) => (r.error && r.error.code) || "unknown");
  log(`${data.type} ${n.chatId.length} -> ${result.successCount}/${keys.length}` + (errors.length ? ` errors: ${errors.join(", ")}` : ""));
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

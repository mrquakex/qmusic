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
const TURN_TLS_HOST = "qeyf.159.195.147.140.sslip.io";
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
    // UDP 3478; UDP 443 (redirected to 3478 by the firewall) and TLS on 5349 for networks that block the rest.
    urls: [
      `turn:${TURN_HOST}:3478?transport=udp`,
      `turn:${TURN_HOST}:443?transport=udp`,
      `turn:${TURN_HOST}:3478?transport=tcp`,
      `turns:${TURN_TLS_HOST}:5349?transport=tcp`,
      `stun:${TURN_HOST}:3478`,
    ],
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

/** Shortens a preview; a sealed one (end-to-end encrypted, "e2e:…") whole, or it could not be opened. */
const cut = (text, n) => (text.startsWith("e2e:") ? text.slice(0, 2000) : text.slice(0, n));

/** A message in a group: every member but the sender gets it, titled with the group, "Name: text" as the body. */
async function handleGroup(n) {
  if ((n.type !== "chat" && n.type !== "groupcall") || n.to !== n.chatId) return;
  if (Date.now() - (Number(n.at) || 0) > MESSAGE_MAX_AGE_MS) return;
  const group = (await db.ref(`groups/${n.chatId}`).get()).val();
  if (!group || !group.members) return;
  if (n.from !== "claudebot" && !group.members[n.from]) return;
  const senderName = n.from === "claudebot" ? "Claude" : String((group.members[n.from] || {}).name || "");
  const data = {
    type: "chat",
    chatId: n.chatId,
    sender: n.chatId,
    name: String(group.name || "Grup"),
    username: "",
    photo: String(group.photo || ""),
    body: (senderName ? senderName + ": " : "") + cut(String(n.body || "").replace(/^Claude: /, ""), 280),
    group: "1",
    // Who wrote it: the notification shows each message under its writer's name and picture.
    author: String(n.from),
    authorName: senderName,
    authorPhoto: n.from === "claudebot" ? "" : String((group.members[n.from] || {}).photo || ""),
    text: cut(String(n.body || "").replace(/^Claude: /, ""), 280),
  };
  const call = n.type === "groupcall";
  // A secret group (made in the secret space): nothing of it in the notification, not even its name.
  if (group.secret === true) {
    Object.assign(data, {
      name: "Nuvelo",
      photo: "",
      body: n.invite ? "Gizli bir sohbete davet edildin" : "Yeni mesaj",
      text: n.invite ? "Gizli bir sohbete davet edildin" : "Yeni mesaj",
      author: n.chatId,
      authorName: "",
      authorPhoto: "",
      secret: "1",
    });
  }
  if (call) {
    // A group call rings each member like a phone call (the app shows it full screen, with Join and Decline).
    data.type = "groupcall";
    data.starter = senderName || "Biri";
    data.video = n.video ? "1" : "0";
    data.callAt = String(Number(n.at) || Date.now());
    data.body = group.secret === true ? "Nuvelo araması" : `${senderName || "Biri"} grup araması başlattı`;
    if (group.secret === true) data.starter = "Nuvelo";
  }
  const targets = Object.keys(group.members).filter((uid) => uid !== n.from);
  // People named with @ in the message hear it even when the group is muted.
  const mentioned = n.mentions && typeof n.mentions === "object" ? n.mentions : {};
  await Promise.all(
    targets.map(async (uid) => {
      // A muted group neither notifies nor rings.
      if (!(mentioned[uid] === true && !call) && (await isMuted(uid, n.chatId))) return;
      await deliver(uid, data, call ? 60 * 1000 : 24 * 3600 * 1000);
    }).map((p) => p.catch((e) => log("group push failed:", e.message))),
  );
}

/** The person muted the conversation (until a time, or for good: -1). */
async function isMuted(uid, chatId) {
  const until = (await db.ref(`userChats/${uid}/${chatId}/mutedUntil`).get()).val();
  return until === -1 || (typeof until === "number" && until > Date.now());
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

/** A preview's mark ("📷", "🎤 0:12", "📞 Cevapsız arama") said in words: no emoji in notifications. */
const MARKS = [["📞", ""], ["🎥", ""], ["🎤", "Sesli mesaj"], ["🎬", "Video"], ["📄", "Dosya"], ["📷", "Fotoğraf"], ["📍", "Konum"], ["👤", ""], ["📊", ""], ["🎵", ""], ["🎧", ""], ["🍿", ""], ["⏱", ""], ["📎", "Dosya"]];
function plainPreview(text) {
  const t = String(text || "").trimStart();
  for (const [mark, word] of MARKS) {
    if (!t.startsWith(mark)) continue;
    const rest = t.slice(mark.length).replace(/^️/, "").trim();
    if (!rest) return word;
    return word && (rest.startsWith("×") || /^[0-9]/.test(rest)) ? `${word} ${rest}` : rest;
  }
  return t;
}

/** Someone put a reaction on a message: its writer hears about it ("Ali reacted ❤️ to: …"). */
async function handleReaction(n) {
  if (n.from === n.to) return;
  if (Date.now() - (Number(n.at) || 0) > MESSAGE_MAX_AGE_MS) return;
  const group = GROUP.test(n.chatId) ? (await db.ref(`groups/${n.chatId}`).get()).val() : null;
  if (group) {
    if (!group.members || !group.members[n.from] || !group.members[n.to]) return;
  } else if (!isMember(n.chatId, n.from) || !isMember(n.chatId, n.to)) {
    return;
  }
  if ((await db.ref(`blocked/${n.to}/${n.from}`).get()).val() === true) return;
  if (await isMuted(n.to, n.chatId)) return;
  // A secret group's reactions are not told.
  if (group && group.secret === true) return;
  const who = (await db.ref(`users/${n.from}`).get()).val() || {};
  const emoji = String(n.emoji || "").slice(0, 8);
  const quote = plainPreview(n.body).slice(0, 120);
  const data = {
    type: "reaction",
    chatId: n.chatId,
    sender: group ? n.chatId : n.from,
    name: group ? String(group.name || "Grup") : String(who.name || ""),
    username: group ? "" : String(who.username || ""),
    photo: group ? String(group.photo || "") : String(who.photo || ""),
    // An encrypted message is quoted sealed (data.enc): the phone opens it with the conversation's key.
    body: String(n.body || "").startsWith("e2e:")
      ? `${group ? String(who.name || "") + " " : ""}${emoji} tepki verdi`.trim()
      : `${group ? String(who.name || "") + " " : ""}${emoji} tepki verdi: "${quote}"`.trim(),
    enc: String(n.body || "").startsWith("e2e:") ? String(n.body).slice(0, 600) : "",
    emoji,
    author: String(n.from),
    authorName: String(who.name || ""),
    authorPhoto: String(who.photo || ""),
  };
  if (group) data.group = "1";
  await deliver(n.to, data, 6 * 3600 * 1000);
}

async function handle(n) {
  if (!n || typeof n.from !== "string" || typeof n.to !== "string" || typeof n.chatId !== "string") return;
  if (n.type === "reaction") return handleReaction(n);
  if (GROUP.test(n.chatId)) return handleGroup(n);
  if (n.from === n.to || !isMember(n.chatId, n.to)) return;
  // The owner's Claude speaks in the owner's conversations without being a member of them.
  if (n.from !== "claudebot" && !isMember(n.chatId, n.from)) return;
  const call = n.type === "call";
  const friend = n.type === "friend";
  const age = Date.now() - (Number(n.at) || 0);
  if (age > (call ? CALL_MAX_AGE_MS : MESSAGE_MAX_AGE_MS)) return;
  if ((await db.ref(`blocked/${n.to}/${n.from}`).get()).val() === true) return;
  if (n.type === "chat" && (await isMuted(n.to, n.chatId))) return;

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
    sender: shownFrom,
    name: String(sender.name || ""),
    username: String(sender.username || ""),
    photo: String(sender.photo || ""),
    body: cut(String(n.body || ""), 300),
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

// ------------------------------------------------------------------ disappearing messages

/** Self-deleting groups (made in the secret space with an end): the group and all in it go at that time. */
async function sweepEndedGroups() {
  try {
    const ended = (await db.ref("groups").orderByChild("endsAt").startAt(1).endAt(Date.now()).get()).val() || {};
    for (const [id, group] of Object.entries(ended)) {
      if (!GROUP.test(id)) continue;
      const updates = { [`groups/${id}`]: null, [`chats/${id}`]: null, [`chatKeys/${id}`]: null, [`typing/${id}`]: null, [`liveLocations/${id}`]: null, [`ttlChats/${id}`]: null };
      for (const uid of Object.keys((group && group.members) || {})) updates[`userChats/${uid}/${id}`] = null;
      await db.ref().update(updates);
      log("a self-deleting group ended");
    }
  } catch (e) {
    log("ended groups sweep failed:", e.message);
  }
}

/** Removes the messages whose time is up, in the conversations that have disappearing messages on. */
async function sweepExpired() {
  sweepEndedGroups();
  try {
    const chats = Object.keys((await db.ref("ttlChats").get()).val() || {});
    let removed = 0;
    for (const chatId of chats) {
      const due = (await db.ref(`chats/${chatId}/messages`).orderByChild("expireAt").startAt(1).endAt(Date.now()).get()).val() || {};
      const updates = {};
      for (const id of Object.keys(due)) {
        updates[`chats/${chatId}/messages/${id}`] = null;
        updates[`chats/${chatId}/reactions/${id}`] = null;
      }
      if (Object.keys(updates).length > 0) {
        await db.ref().update(updates);
        removed += Object.keys(due).length;
      }
    }
    if (removed > 0) log(`expired messages removed: ${removed}`);
    // Statuses older than a day: gone, with who saw them and their file on the media server.
    const statuses = (await db.ref("statuses").get()).val() || {};
    const gone = {};
    for (const [uid, items] of Object.entries(statuses)) {
      for (const [id, st] of Object.entries(items || {})) {
        if (!st || Number(st.expireAt || 0) > Date.now()) continue;
        gone[`statuses/${uid}/${id}`] = null;
        gone[`statusViews/${uid}/${id}`] = null;
        const file = String(st.url || "").match(/\/m\/(u_[A-Za-z0-9]+)\/([0-9a-f]{32}\.[a-z0-9]+)$/);
        if (file) fs.rm(`/var/lib/qeyf-media/${file[1]}/${file[2]}`, { force: true }, () => {});
      }
    }
    if (Object.keys(gone).length > 0) {
      await db.ref().update(gone);
      log(`expired statuses removed: ${Object.keys(gone).length / 2}`);
    }
  } catch (e) {
    log("sweep failed:", e.message);
  }
}

publishTurn();
setInterval(publishTurn, TURN_EVERY_MS);
sweepExpired();
setInterval(sweepExpired, 5 * 60 * 1000);
log("qmusic-push started");

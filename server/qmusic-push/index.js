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
const path = require("path");

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
      body: "Yeni mesaj",
      text: "Yeni mesaj",
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
  // Who is only invited (a secret group's invitation not yet taken) hears of the invitation and nothing else.
  const invited = (uid) => group.members[uid] && group.members[uid].invited === true;
  const targets = Object.keys(group.members).filter((uid) => uid !== n.from && (!invited(uid) || (n.invite && !call)));
  const inviteData = { ...data, body: "Gizli bir sohbete davet edildin", text: "Gizli bir sohbete davet edildin" };
  // People named with @ in the message hear it even when the group is muted.
  const mentioned = n.mentions && typeof n.mentions === "object" ? n.mentions : {};
  await Promise.all(
    targets.map(async (uid) => {
      // A muted group neither notifies nor rings.
      if (!(mentioned[uid] === true && !call) && (await isMuted(uid, n.chatId))) return;
      await deliver(uid, invited(uid) ? inviteData : data, call ? 60 * 1000 : 24 * 3600 * 1000);
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
    if (n && n.type === "chat" && typeof n.from === "string") {
      countMessage();
      watchSpam(n.from).catch((e) => log("spam check failed:", e.message));
      // Someone answered Nuvelo's account: the founder reads it in their Claude chat.
      if (n.to === NUVELO) {
        const name = String(((await db.ref(`users/${n.from}/name`).get()).val()) || n.from);
        await tellFounder(`${name} Nuvelo hesabına yazdı: ${String(n.body || "").slice(0, 500)}`, `${name} Nuvelo'ya yazdı`);
        return;
      }
    }
    await handle(n);
  } catch (e) {
    log("notify failed:", e.message);
  }
});

// ------------------------------------------------------------------ reports

/** A report (someone or a group, with the last messages): the founder hears of it in their Claude chat. */
const FOUNDER = "2hxOw0dRcwUwsW7N6ogH4fRcqXo1";
db.ref("reports").orderByChild("at").startAt(Date.now()).on("child_added", async (snap) => {
  try {
    const r = snap.val() || {};
    if (r.notified) return;
    const name = async (uid) => {
      if (/^grp/.test(String(uid))) return "grup: " + String(((await db.ref(`groups/${uid}/name`).get()).val()) || uid);
      return String(((await db.ref(`users/${uid}/name`).get()).val()) || uid);
    };
    const lines = [];
    for (const m of Array.isArray(r.recent) ? r.recent : Object.values(r.recent || {})) {
      lines.push(`  ${await name(m.from)}: ${String(m.text || "").slice(0, 200)}`);
    }
    const text = `Yeni şikayet (${r.reason}). Şikayet eden: ${await name(r.by)}. Şikayet edilen: ${await name(r.target)}.` +
      (lines.length ? `\nSon mesajlar:\n${lines.join("\n")}` : "") + `\nKayıt: reports/${snap.key}`;
    const chat = [FOUNDER, "claudebot"].sort().join("_");
    const id = db.ref("x").push().key;
    const TS = admin.database.ServerValue.TIMESTAMP;
    await db.ref().update({
      [`chats/${chat}/messages/${id}`]: { from: "claudebot", text, at: TS },
      [`userChats/${FOUNDER}/${chat}/lastText`]: text.slice(0, 120),
      [`userChats/${FOUNDER}/${chat}/lastAt`]: TS,
      [`userChats/${FOUNDER}/${chat}/lastFrom`]: "claudebot",
      [`userChats/${FOUNDER}/${chat}/unread`]: admin.database.ServerValue.increment(1),
      [`notify/${db.ref("notify").push().key}`]: { from: "claudebot", to: FOUNDER, chatId: chat, type: "chat", body: "Yeni şikayet: " + r.reason, at: TS },
      [`reports/${snap.key}/notified`]: true,
    });
    log("a report came in");
  } catch (e) {
    log("report failed:", e.message);
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

// ------------------------------------------------------------------ the admin panel

// What an admin asks for in the app (adminActions/{id}: kind, target, value, by, at) that the rules cannot let a
// phone do: done here, then marked done (or with the error). Bans and mutes are written by the app itself (bans/{uid});
// their entries here only keep the record. No one's messages are read for any of this.
const ADMIN_NAME_RE = /^[a-z0-9._]{3,20}$/;
const execFile = require("child_process").execFile;
const du = (dir) => new Promise((ok) => execFile("du", ["-sb", dir], (e, out) => ok(e ? 0 : Number(String(out).split(/\s/)[0]) || 0)));

async function isAdmin(uid) {
  return (await db.ref(`admins/${uid}`).get()).val() === true;
}

/** Closes a group: it, its messages and its place in every member's list go. */
async function closeGroup(id) {
  if (!GROUP.test(id)) throw new Error("not a group");
  const members = (await db.ref(`groups/${id}/members`).get()).val() || {};
  const gone = {
    [`groups/${id}`]: null,
    [`chats/${id}`]: null,
    [`groupCalls/${id}`]: null,
    [`typing/${id}`]: null,
    [`together/${id}`]: null,
    [`chatKeys/${id}`]: null,
    [`liveLocations/${id}`]: null,
    [`chatLists/${id}`]: null,
    [`reacts/${id}`]: null,
    [`ttlChats/${id}`]: null,
  };
  for (const uid of Object.keys(members)) gone[`userChats/${uid}/${id}`] = null;
  await db.ref().update(gone);
  fs.rm(`/var/lib/qeyf-media/${id}`, { recursive: true, force: true }, () => {});
}

/** Gives someone another user name (the old one is let go). */
async function renameUser(uid, name) {
  if (!ADMIN_NAME_RE.test(name)) throw new Error("bad name");
  const owner = (await db.ref(`usernames/${name}`).get()).val();
  if (owner && owner !== uid) throw new Error("taken");
  const old = (await db.ref(`users/${uid}/username`).get()).val();
  if (!old) throw new Error("no such user");
  const change = { [`usernames/${name}`]: uid, [`users/${uid}/username`]: name };
  if (old !== name) change[`usernames/${old}`] = null;
  await db.ref().update(change);
}

/** Takes a picture, a name, an about line or the statuses off someone's profile. */
async function resetProfile(uid, what) {
  if (!(await db.ref(`users/${uid}/name`).get()).exists()) throw new Error("no such user");
  const change = {};
  if (what === "photo") {
    Object.assign(change, { [`users/${uid}/photo`]: null, [`privateProfile/${uid}/photo`]: null });
    // Its copies too: in the others' chat lists, their friend cards and the groups it is in.
    const chats = (await db.ref(`userChats/${uid}`).get()).val() || {};
    for (const [chatId, c] of Object.entries(chats)) {
      if (GROUP.test(chatId)) change[`groups/${chatId}/members/${uid}/photo`] = null;
      else if (c && c.other) change[`userChats/${c.other}/${chatId}/photo`] = null;
    }
    const friends = (await db.ref(`friends/${uid}`).get()).val() || {};
    for (const other of Object.keys(friends)) {
      change[`friends/${other}/${uid}/photo`] = null;
      change[`userChats/${other}/${[uid, other].sort().join("_")}/photo`] = null;
    }
  }
  else if (what === "name") change[`users/${uid}/name`] = "Nuvelo kullanıcısı";
  else if (what === "about") Object.assign(change, { [`users/${uid}/about`]: null, [`privateProfile/${uid}/about`]: null });
  else if (what === "status") Object.assign(change, { [`statuses/${uid}`]: null, [`statusViews/${uid}`]: null });
  else throw new Error("unknown part");
  await db.ref().update(change);
}

/** A word to everyone: kept (the app shows it once) and pushed to every phone that has the app. */
async function announce(text, by) {
  const body = String(text || "").trim().slice(0, 1000);
  if (!body) throw new Error("empty");
  await db.ref("announcements").push({ text: body, at: admin.database.ServerValue.TIMESTAMP, by });
  await admin.messaging().send({ topic: "releases", data: { type: "announce", body }, android: { priority: "high", ttl: 3 * 24 * 3600 * 1000 } });
}

/** Counts for the panel: each account's sign-up and last sign-in, how many conversations it has, the server's disk. */
async function writeStats() {
  const users = {};
  let page;
  do {
    const list = await admin.auth().listUsers(1000, page);
    for (const u of list.users) {
      users[u.uid] = {
        created: Date.parse(u.metadata.creationTime) || 0,
        lastSignIn: Date.parse(u.metadata.lastRefreshTime || u.metadata.lastSignInTime) || 0,
        chats: 0,
      };
    }
    page = list.pageToken;
  } while (page);
  // Only how many: the conversations themselves are not looked at.
  const lists = (await db.ref("userChats").get()).val() || {};
  for (const [uid, chats] of Object.entries(lists)) {
    if (users[uid]) users[uid].chats = Object.keys(chats || {}).length;
  }
  let diskFree = 0, diskTotal = 0;
  try {
    const s = fs.statfsSync("/var/lib/qeyf-media");
    diskFree = s.bavail * s.bsize;
    diskTotal = s.blocks * s.bsize;
  } catch (_) {}
  await db.ref("adminStats").set({
    at: admin.database.ServerValue.TIMESTAMP,
    users,
    mediaBytes: await du("/var/lib/qeyf-media"),
    diskFree,
    diskTotal,
    storage: await storageByChat(),
  });
  await writeDaily(users);
}

// ------------------------------------------------------------------ admin: storage

const MEDIA_ROOT = "/var/lib/qeyf-media";
const DM_ID = /^[A-Za-z0-9]+_[A-Za-z0-9]+$/;

/** How much each conversation's files take on the server (sizes only: no file is opened), the largest first. */
async function storageByChat() {
  let dirs = [];
  try {
    dirs = fs.readdirSync(MEDIA_ROOT, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name);
  } catch (_) {
    return [];
  }
  const out = [];
  for (const name of dirs) {
    let files = 0;
    try {
      files = fs.readdirSync(path.join(MEDIA_ROOT, name)).length;
    } catch (_) {}
    out.push({ id: name, bytes: await du(path.join(MEDIA_ROOT, name)), files });
  }
  return out.sort((a, b) => b.bytes - a.bytes).slice(0, 40);
}

/** Deletes a conversation's files (its messages stay, showing the file as gone). Profile folders are not touched. */
async function deleteMedia(chatId) {
  if (!(DM_ID.test(chatId) || GROUP.test(chatId))) throw new Error("not a conversation");
  fs.rmSync(path.join(MEDIA_ROOT, chatId), { recursive: true, force: true });
  await writeStats();
}

// ------------------------------------------------------------------ admin: the day's numbers

/** The day ("2026-11-19") in Turkey's time. */
const dayKey = (ms) => new Date(ms + 3 * 3600 * 1000).toISOString().slice(0, 10);
const dayStart = (key) => Date.parse(key + "T00:00:00Z") - 3 * 3600 * 1000;

/**
 * adminDaily/{day} = {active, newUsers, total, messages}: who showed up that day, who joined, how many there were, how
 * many messages went (counted as they go; never what they say). Today's is written again each time; the joins and the
 * totals of the past 30 days are filled in once from the sign-up dates.
 */
async function writeDaily(users) {
  const now = Date.now();
  const today = dayKey(now);
  const start = dayStart(today);
  const [presence, info] = await Promise.all([db.ref("presence").get(), db.ref("appInfo").get()]);
  const pres = presence.val() || {};
  const inf = info.val() || {};
  const created = Object.values(users).map((u) => u.created);
  let active = 0;
  for (const [uid, u] of Object.entries(users)) {
    const p = pres[uid] || {};
    const i = inf[uid] || {};
    const last = Math.max(Number(p.lastSeen) || 0, Number(i.at) || 0, u.lastSignIn || 0);
    if (p.online === true || last >= start) active++;
  }
  const change = {
    [`adminDaily/${today}/active`]: active,
    [`adminDaily/${today}/newUsers`]: created.filter((c) => c >= start).length,
    [`adminDaily/${today}/total`]: created.length,
  };
  const past = (await db.ref("adminDaily").orderByKey().limitToLast(40).get()).val() || {};
  for (let d = 1; d <= 30; d++) {
    const key = dayKey(now - d * 86400000);
    if (past[key] && past[key].total != null) continue;
    const from = dayStart(key);
    const to = from + 86400000;
    change[`adminDaily/${key}/newUsers`] = created.filter((c) => c >= from && c < to).length;
    change[`adminDaily/${key}/total`] = created.filter((c) => c < to).length;
  }
  await db.ref().update(change);
}

/** A message went: today's count goes up by one. */
function countMessage() {
  db.ref(`adminDaily/${dayKey(Date.now())}/messages`).set(admin.database.ServerValue.increment(1)).catch(() => {});
}

// ------------------------------------------------------------------ admin: Nuvelo's own account

const NUVELO = "nuvelo";

/** Nuvelo's account (the one that writes to people for the admins), made once. */
async function ensureNuvelo() {
  const ref = db.ref(`users/${NUVELO}`);
  if ((await ref.child("name").get()).exists()) return;
  const taken = (await db.ref(`usernames/${NUVELO}`).get()).val();
  await ref.set({ name: "Nuvelo", username: taken && taken !== NUVELO ? "nuvelo.app" : NUVELO, about: "Nuvelo ekibi" });
  if (!taken) await db.ref(`usernames/${NUVELO}`).set(NUVELO);
}

/** A message to someone from Nuvelo's account: it comes into their chats as from "Nuvelo", with a notification. */
async function messageFromNuvelo(uid, text) {
  const body = String(text || "").trim().slice(0, 2000);
  if (!body) throw new Error("empty");
  if (!(await db.ref(`users/${uid}/name`).get()).exists()) throw new Error("no such user");
  await ensureNuvelo();
  const chat = [uid, NUVELO].sort().join("_");
  const id = db.ref("x").push().key;
  const TS = admin.database.ServerValue.TIMESTAMP;
  await db.ref().update({
    [`chats/${chat}/messages/${id}`]: { from: NUVELO, text: body, at: TS },
    [`userChats/${uid}/${chat}/other`]: NUVELO,
    [`userChats/${uid}/${chat}/lastText`]: body.slice(0, 120),
    [`userChats/${uid}/${chat}/lastAt`]: TS,
    [`userChats/${uid}/${chat}/lastFrom`]: NUVELO,
    [`userChats/${uid}/${chat}/unread`]: admin.database.ServerValue.increment(1),
    [`userChats/${NUVELO}/${chat}/other`]: uid,
    [`userChats/${NUVELO}/${chat}/lastText`]: body.slice(0, 120),
    [`userChats/${NUVELO}/${chat}/lastAt`]: TS,
    [`notify/${db.ref("notify").push().key}`]: { from: NUVELO, to: uid, chatId: chat, type: "chat", body: body.slice(0, 200), at: TS },
  });
}

/** Something for the founder, in their Claude chat (with a notification). */
async function tellFounder(text, push) {
  const chat = [FOUNDER, "claudebot"].sort().join("_");
  const id = db.ref("x").push().key;
  const TS = admin.database.ServerValue.TIMESTAMP;
  await db.ref().update({
    [`chats/${chat}/messages/${id}`]: { from: "claudebot", text, at: TS },
    [`userChats/${FOUNDER}/${chat}/lastText`]: text.slice(0, 120),
    [`userChats/${FOUNDER}/${chat}/lastAt`]: TS,
    [`userChats/${FOUNDER}/${chat}/lastFrom`]: "claudebot",
    [`userChats/${FOUNDER}/${chat}/unread`]: admin.database.ServerValue.increment(1),
    [`notify/${db.ref("notify").push().key}`]: { from: "claudebot", to: FOUNDER, chatId: chat, type: "chat", body: push, at: TS },
  });
}

// ------------------------------------------------------------------ admin: spam

/** appConfig/spam = {on, limit}: messages a minute above which someone is muted for an hour by themselves. */
let spamConfig = { on: true, limit: 40 };
db.ref("appConfig/spam").on("value", (snap) => {
  const v = snap.val() || {};
  spamConfig = { on: v.on !== false, limit: Math.max(10, Math.min(500, Number(v.limit) || 40)) };
}, () => {});
const sentTimes = new Map();

/** Each message counts against its sender for a minute; too many, and they are muted for an hour (the founder hears). */
async function watchSpam(from) {
  if (!spamConfig.on || from === "claudebot" || from === NUVELO) return;
  const now = Date.now();
  const times = (sentTimes.get(from) || []).filter((t) => now - t < 60000);
  times.push(now);
  sentTimes.set(from, times);
  if (times.length <= spamConfig.limit) return;
  sentTimes.delete(from);
  if (await isAdmin(from)) return;
  const current = (await db.ref(`bans/${from}/until`).get()).val();
  if (typeof current === "number" && current > now) return;
  const TS = admin.database.ServerValue.TIMESTAMP;
  await db.ref(`bans/${from}`).set({ kind: "mute", until: now + 3600000, reason: "Çok hızlı mesaj gönderimi (otomatik)", by: "auto", at: TS });
  await db.ref("adminActions").push({ kind: "autoMute", target: from, value: String(times.length), by: "auto", at: TS, done: true });
  const name = String(((await db.ref(`users/${from}/name`).get()).val()) || from);
  await tellFounder(`Spam koruması: ${name} bir dakikada ${times.length} mesaj gönderdi, 1 saat susturuldu. Yönetim panelinden kaldırabilirsin.`, "Spam koruması: " + name + " susturuldu");
  log("auto-muted", from);
}

// ------------------------------------------------------------------ admin: deleting an account

/** Removes an account and what is its own (profile, lists, keys, statuses, files); others keep their copies of chats. */
async function deleteAccount(uid) {
  if (uid === FOUNDER || uid === NUVELO || uid === "claudebot" || (await isAdmin(uid))) throw new Error("protected");
  const user = (await db.ref(`users/${uid}`).get()).val();
  if (!user) throw new Error("no such user");
  const chats = (await db.ref(`userChats/${uid}`).get()).val() || {};
  const friends = (await db.ref(`friends/${uid}`).get()).val() || {};
  const gone = {};
  // Their messages, chat by chat.
  for (const chatId of Object.keys(chats)) {
    const mine = (await db.ref(`chats/${chatId}/messages`).orderByChild("from").equalTo(uid).get()).val() || {};
    for (const id of Object.keys(mine)) gone[`chats/${chatId}/messages/${id}`] = null;
    if (GROUP.test(chatId)) gone[`groups/${chatId}/members/${uid}`] = null;
  }
  for (const other of Object.keys(friends)) gone[`friends/${other}/${uid}`] = null;
  for (const node of ["users", "userChats", "friends", "friendRequests", "sentRequests", "presence", "pushTokens", "blocked",
    "privateProfile", "statuses", "statusViews", "statusSeen", "statusPrivacy", "statusMuted", "statusLikes", "publicKeys",
    "keyBackup", "keyLink", "secretSpace", "appInfo", "bans", "callLog", "chatPrefs", "starred", "hidden", "broadcasts",
    "claudeSettings", "claudeState", "claudeAccess", "badges", "listening", "incomingCall"]) gone[`${node}/${uid}`] = null;
  if (user.username) gone[`usernames/${user.username}`] = null;
  await db.ref().update(gone);
  fs.rmSync(path.join(MEDIA_ROOT, "u_" + uid), { recursive: true, force: true });
  await admin.auth().deleteUser(uid).catch(() => {});
}

ensureNuvelo().catch((e) => log("nuvelo account:", e.message));
setInterval(() => writeStats().catch((e) => log("stats failed:", e.message)), 3600 * 1000);

db.ref("adminActions").on("child_added", async (snap) => {
  const a = snap.val() || {};
  if (a.done || a.error) return;
  const finish = (error) => snap.ref.update(error ? { error: String(error).slice(0, 200) } : { done: true }).catch(() => {});
  try {
    if (!(await isAdmin(String(a.by || "")))) return finish("not an admin");
    const target = String(a.target || "");
    const value = String(a.value || "");
    switch (a.kind) {
      case "closeGroup": await closeGroup(target); break;
      case "rename": await renameUser(target, value); break;
      case "reset": await resetProfile(target, value); break;
      case "announce": await announce(value, a.by); break;
      case "stats": await writeStats(); break;
      case "message": await messageFromNuvelo(target, value); break;
      case "deleteMedia": await deleteMedia(target); break;
      case "deleteAccount": await deleteAccount(target); break;
      // Written by the app itself; kept here as the record.
      case "ban": case "mute": case "unban": case "minVersion": case "banName": case "report":
      case "maintenance": case "spam": case "badge": case "claude": case "crash": break;
      default: return finish("unknown");
    }
    log(`admin ${a.kind} ${target}`);
    await finish(null);
  } catch (e) {
    log("admin action failed:", a.kind, e.message);
    await finish(e.message);
  }
});

publishTurn();
setInterval(publishTurn, TURN_EVERY_MS);
sweepExpired();
setInterval(sweepExpired, 5 * 60 * 1000);

// Once a day: the media files no message points at any more go (see media-gc.js).
const { sweepMedia } = require("./media-gc");
const sweepMediaSafely = () => sweepMedia(db, { log }).catch((e) => log("media sweep failed:", e.message));
setTimeout(sweepMediaSafely, 10 * 60 * 1000);
setInterval(sweepMediaSafely, 24 * 3600 * 1000);
log("qmusic-push started");

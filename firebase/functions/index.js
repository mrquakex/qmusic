// QMusic messages: when a message is written, the other member's phones get a "new message" push.
//
// Deploy (from the firebase/ folder, once the project is on the Blaze plan):
//   npx firebase-tools login
//   npx firebase-tools deploy --only functions,database
const { onValueCreated } = require("firebase-functions/v2/database");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.database();

const DEAD_TOKEN = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

exports.chatPush = onValueCreated(
  {
    ref: "/chats/{chatId}/messages/{messageId}",
    instance: "qmusic-2552b-default-rtdb",
    region: "europe-west1",
  },
  async (event) => {
    const message = event.data.val();
    if (!message || !message.from) return;
    const chatId = event.params.chatId;
    const [a, b] = chatId.split("_");
    const to = message.from === a ? b : a;
    if (!to || to === message.from) return;

    const blocked = await db.ref(`blocked/${to}/${message.from}`).get();
    if (blocked.val() === true) return;

    const pushRef = db.ref(`pushTokens/${to}`);
    const tokensByKey = (await pushRef.get()).val() || {};
    const keys = Object.keys(tokensByKey);
    if (keys.length === 0) return;
    const tokens = keys.map((k) => tokensByKey[k]);

    const sender = (await db.ref(`users/${message.from}`).get()).val() || {};
    const share = message.share;
    const body = message.text || (share ? (share.kind === "SONG" ? "🎵 " : "🎬 ") + share.title : "");

    const result = await admin.messaging().sendEachForMulticast({
      tokens,
      data: {
        type: "chat",
        chatId,
        from: message.from,
        name: sender.name || "",
        username: sender.username || "",
        photo: sender.photo || "",
        body: body.slice(0, 300),
      },
      android: { priority: "high" },
    });

    // Phones that uninstalled the app or signed out: their addresses are dropped.
    const removals = {};
    result.responses.forEach((r, i) => {
      if (!r.success && r.error && DEAD_TOKEN.has(r.error.code)) removals[keys[i]] = null;
    });
    if (Object.keys(removals).length > 0) await pushRef.update(removals);
  },
);

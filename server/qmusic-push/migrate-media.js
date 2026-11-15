// One-off: copies the photos the app kept in Firebase (media/{chatId}/{id}, base64 JPEG) to the media server's disk as
// /var/lib/qeyf-media/{chatId}/{id}.jpg, where the new app looks for them first.
//   node migrate-media.js          copy only
//   node migrate-media.js --delete copy, then remove them from Firebase (once every phone has the new app)
const admin = require("firebase-admin");
const fs = require("fs");
const path = require("path");

const DATABASE_URL = "https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app";
const ROOT = "/var/lib/qeyf-media";
const remove = process.argv.includes("--delete");

admin.initializeApp({
  credential: admin.credential.cert(JSON.parse(fs.readFileSync("/etc/qmusic/firebase-key.json", "utf8"))),
  databaseURL: DATABASE_URL,
});

(async () => {
  const db = admin.database();
  const chats = (await db.ref("media").get()).val() || {};
  let copied = 0;
  let bytes = 0;
  for (const [chatId, items] of Object.entries(chats)) {
    const dir = path.join(ROOT, chatId);
    fs.mkdirSync(dir, { recursive: true });
    for (const [id, media] of Object.entries(items || {})) {
      if (!media || typeof media.data !== "string") continue;
      const data = Buffer.from(media.data, "base64");
      fs.writeFileSync(path.join(dir, `${id}.jpg`), data);
      copied++;
      bytes += data.length;
    }
  }
  console.log(`copied ${copied} photos, ${(bytes / 1048576).toFixed(1)} MB`);
  if (remove) {
    await db.ref("media").remove();
    console.log("removed media/ from Firebase");
  }
  process.exit(0);
})().catch((e) => {
  console.error(e.message);
  process.exit(1);
});

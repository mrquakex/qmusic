// qeyf-backup: the whole Realtime Database (profiles, conversations, messages, groups, friends) written to
// /var/backups/qeyf/rtdb-<date>.json.gz once a night; the last 30 are kept. Firebase's free plan keeps no backups.
// Restore: gunzip the file and import it in the Firebase console (Realtime Database → ⋮ → Import JSON), or with
// firebase-admin: db.ref("/").set(JSON.parse(...)).
const admin = require("firebase-admin");
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

const DATABASE_URL = "https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app";
const DIR = "/var/backups/qeyf";
const KEEP = 30;

admin.initializeApp({
  credential: admin.credential.cert(JSON.parse(fs.readFileSync("/etc/qmusic/firebase-key.json", "utf8"))),
  databaseURL: DATABASE_URL,
});

(async () => {
  const snap = await admin.database().ref("/").get();
  const day = new Date().toISOString().slice(0, 10);
  fs.mkdirSync(DIR, { recursive: true });
  const file = path.join(DIR, `rtdb-${day}.json.gz`);
  fs.writeFileSync(file + ".part", zlib.gzipSync(JSON.stringify(snap.val() || {})));
  fs.renameSync(file + ".part", file);
  const all = fs.readdirSync(DIR).filter((f) => /^rtdb-\d{4}-\d{2}-\d{2}\.json\.gz$/.test(f)).sort();
  for (const old of all.slice(0, Math.max(0, all.length - KEEP))) fs.rmSync(path.join(DIR, old));
  console.log(new Date().toISOString(), "backup", file, fs.statSync(file).size, "bytes,", Math.min(all.length, KEEP), "kept");
  process.exit(0);
})().catch((e) => {
  console.error("backup failed:", e.message);
  process.exit(1);
});

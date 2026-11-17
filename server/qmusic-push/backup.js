// qeyf-backup: the whole Realtime Database (profiles, conversations, messages, groups, friends) written to
// /var/backups/qeyf/rtdb-<date>.json.gz once a night; the last 30 are kept. Firebase's free plan keeps no backups.
// Restore: gunzip the file and import it in the Firebase console (Realtime Database → ⋮ → Import JSON), or with
// firebase-admin: db.ref("/").set(JSON.parse(...)).
// The key vault (/var/lib/qeyf-vault: what the PIN backups of end-to-end encryption need) goes beside it as
// vault-<date>.json.gz; restore: write each entry back as /var/lib/qeyf-vault/<name> (owner qmusic-push, mode 600).
// Both hold private things: readable by the service's own account only.
const admin = require("firebase-admin");
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

const DATABASE_URL = "https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app";
const DIR = "/var/backups/qeyf";
const KEEP = 30;
const VAULT = "/var/lib/qeyf-vault";

admin.initializeApp({
  credential: admin.credential.cert(JSON.parse(fs.readFileSync("/etc/qmusic/firebase-key.json", "utf8"))),
  databaseURL: DATABASE_URL,
});

(async () => {
  const snap = await admin.database().ref("/").get();
  const day = new Date().toISOString().slice(0, 10);
  fs.mkdirSync(DIR, { recursive: true });
  const file = path.join(DIR, `rtdb-${day}.json.gz`);
  fs.writeFileSync(file + ".part", zlib.gzipSync(JSON.stringify(snap.val() || {})), { mode: 0o600 });
  fs.renameSync(file + ".part", file);
  fs.chmodSync(file, 0o600);
  // The key vault: every person's entry, as it is on disk.
  const vault = {};
  for (const f of fs.existsSync(VAULT) ? fs.readdirSync(VAULT) : []) {
    if (f.endsWith(".json")) vault[f] = fs.readFileSync(path.join(VAULT, f), "utf8");
  }
  const vaultFile = path.join(DIR, `vault-${day}.json.gz`);
  fs.writeFileSync(vaultFile + ".part", zlib.gzipSync(JSON.stringify(vault)), { mode: 0o600 });
  fs.renameSync(vaultFile + ".part", vaultFile);
  fs.chmodSync(vaultFile, 0o600);
  for (const kind of ["rtdb", "vault"]) {
    const all = fs.readdirSync(DIR).filter((f) => f.startsWith(kind + "-") && /^[a-z]+-\d{4}-\d{2}-\d{2}\.json\.gz$/.test(f)).sort();
    for (const old of all.slice(0, Math.max(0, all.length - KEEP))) fs.rmSync(path.join(DIR, old));
  }
  for (const f of fs.readdirSync(DIR)) if (/\.json\.gz$/.test(f)) fs.chmodSync(path.join(DIR, f), 0o600);
  const all = fs.readdirSync(DIR).filter((f) => /^rtdb-/.test(f));
  console.log(new Date().toISOString(), "vault", Object.keys(vault).length, "entries");
  console.log(new Date().toISOString(), "backup", file, fs.statSync(file).size, "bytes,", Math.min(all.length, KEEP), "kept");
  process.exit(0);
})().catch((e) => {
  console.error("backup failed:", e.message);
  process.exit(1);
});

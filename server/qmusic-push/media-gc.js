// Media no message points at any more: removed from /var/lib/qeyf-media once a day (by index.js), so a photo of a
// message deleted for everyone, a disappearing message, a self-deleting group that ended or a deleted account does
// not stay on the server.
//   - a conversation's folder whose conversation is gone (no chat and no group): removed whole;
//   - in the others, a file whose name appears nowhere in the database: removed, but only when older than two days
//     (an upload is in the database once its message is written) and never a sealed one (.bin: an end-to-end
//     encrypted file, whose address is inside the sealed message where the server cannot see it).
// Run by hand to see what it would do:   node media-gc.js --dry
const fs = require("fs");
const path = require("path");

const ROOT = "/var/lib/qeyf-media";
const DM = /^[A-Za-z0-9]{20,40}_[A-Za-z0-9]{6,40}$/;
const GROUP = /^grp[A-Za-z0-9]{20}$/;
const FILE = /^([0-9a-f]{32})\.[a-z0-9]{1,5}$/;
const MIN_AGE_MS = 2 * 24 * 3600 * 1000;

async function sweepMedia(db, { dry = false, log = console.log } = {}) {
  if (!fs.existsSync(ROOT)) return { files: 0, bytes: 0 };
  // Everything the database holds, as text: a file is in use when its name is somewhere in it.
  const everything = JSON.stringify((await db.ref("/").get()).val() || {});
  const now = Date.now();
  let files = 0;
  let bytes = 0;
  const remove = (p, size) => {
    files++;
    bytes += size;
    if (!dry) fs.rmSync(p, { force: true });
  };
  for (const dir of fs.readdirSync(ROOT)) {
    const full = path.join(ROOT, dir);
    if (!fs.statSync(full).isDirectory()) continue;
    const conversation = DM.test(dir) || GROUP.test(dir);
    if (!conversation && !/^u_[A-Za-z0-9]{6,40}$/.test(dir)) continue;
    const entries = fs.readdirSync(full).map((name) => ({ name, p: path.join(full, name), st: fs.statSync(path.join(full, name)) }));
    const newest = entries.reduce((m, e) => Math.max(m, e.st.mtimeMs), 0);
    // The conversation itself is gone: all of it (once nothing in it is new).
    if (conversation && now - newest > MIN_AGE_MS) {
      const chatThere = (await db.ref(`chats/${dir}`).limitToFirst(1).get()).exists();
      const groupThere = GROUP.test(dir) && (await db.ref(`groups/${dir}/name`).get()).exists();
      if (!chatThere && !groupThere) {
        for (const e of entries) if (e.st.isFile()) remove(e.p, e.st.size);
        if (!dry) fs.rmSync(full, { recursive: true, force: true });
        continue;
      }
    }
    for (const e of entries) {
      if (!e.st.isFile()) continue;
      const m = e.name.match(FILE);
      if (!m || e.name.endsWith(".bin")) continue;
      if (now - e.st.mtimeMs < MIN_AGE_MS) continue;
      if (everything.includes(m[1])) continue;
      remove(e.p, e.st.size);
    }
  }
  log(`media sweep${dry ? " (dry)" : ""}: ${files} files, ${(bytes / 1048576).toFixed(1)} MB`);
  return { files, bytes };
}

module.exports = { sweepMedia };

if (require.main === module) {
  const admin = require("firebase-admin");
  admin.initializeApp({
    credential: admin.credential.cert(JSON.parse(fs.readFileSync("/etc/qmusic/firebase-key.json", "utf8"))),
    databaseURL: "https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app",
  });
  sweepMedia(admin.database(), { dry: process.argv.includes("--dry") }).then(() => process.exit(0), (e) => {
    console.error("failed:", e.message);
    process.exit(1);
  });
}

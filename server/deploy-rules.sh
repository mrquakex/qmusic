#!/bin/sh
# Publishes firebase/database.rules.json through the push service's key on QMusic's server (no Firebase CLI needed).
#   sh server/deploy-rules.sh
set -e
cd "$(dirname "$0")/.."
scp -q firebase/database.rules.json nakitpos:/tmp/qmusic-rules.json
ssh nakitpos 'cd /opt/qmusic-push && /usr/local/bin/node -e "
const admin = require(\"firebase-admin\"); const fs = require(\"fs\");
admin.initializeApp({ credential: admin.credential.cert(JSON.parse(fs.readFileSync(\"/etc/qmusic/firebase-key.json\", \"utf8\"))), databaseURL: \"https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app\" });
admin.database().setRules(fs.readFileSync(\"/tmp/qmusic-rules.json\", \"utf8\")).then(() => { console.log(\"rules published\"); process.exit(0); }, (e) => { console.log(\"rules failed: \" + e.message); process.exit(1); });
"; rm -f /tmp/qmusic-rules.json'

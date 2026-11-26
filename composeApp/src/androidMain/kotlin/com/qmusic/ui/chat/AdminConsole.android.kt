package com.qmusic.ui.chat

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * [AdminConsole] on Firebase. The person's own part (ban, least version, announcement) follows the signed-in account;
 * the panel's lists read nodes only admins may read (a refused read gives an empty list, never a crash).
 */
internal class FirebaseAdminConsole(app: Context, private val root: DatabaseReference) : AdminConsole {
    private val auth = FirebaseAuth.getInstance()
    private val prefs = app.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE)

    private val _isAdmin = MutableStateFlow(false)
    override val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()
    private val _myBan = MutableStateFlow<AdminBan?>(null)
    override val myBan: StateFlow<AdminBan?> = _myBan.asStateFlow()
    private val _minVersion = MutableStateFlow<String?>(null)
    override val minVersion: StateFlow<String?> = _minVersion.asStateFlow()
    private val _recommendedVersion = MutableStateFlow<String?>(null)
    override val recommendedVersion: StateFlow<String?> = _recommendedVersion.asStateFlow()
    private val _features = MutableStateFlow(FeatureFlags())
    override val features: StateFlow<FeatureFlags> = _features.asStateFlow()
    private val _maintenance = MutableStateFlow(Maintenance())
    override val maintenance: StateFlow<Maintenance> = _maintenance.asStateFlow()
    private val _spam = MutableStateFlow(SpamGuard())
    override val spam: StateFlow<SpamGuard> = _spam.asStateFlow()
    private val _latest = MutableStateFlow<Announcement?>(null)
    private val _announcement = MutableStateFlow<Announcement?>(null)
    override val announcement: StateFlow<Announcement?> = _announcement.asStateFlow()

    private val attached = mutableListOf<Pair<Query, ValueEventListener>>()

    init {
        // The least version, recommended version and feature switches are for everyone, signed in or not.
        root.child("appConfig").child("minVersion").addValueEventListener(listener { _minVersion.value = it?.getValue(String::class.java) })
        root.child("appConfig").child("recommendedVersion").addValueEventListener(listener { _recommendedVersion.value = it?.getValue(String::class.java) })
        root.child("appConfig").child("features").addValueEventListener(listener { s ->
            _features.value = FeatureFlags(
                games = s?.child("games")?.getValue(Boolean::class.java) != false,
                calls = s?.child("calls")?.getValue(Boolean::class.java) != false,
                statuses = s?.child("statuses")?.getValue(Boolean::class.java) != false,
                newChats = s?.child("newChats")?.getValue(Boolean::class.java) != false,
            )
        })
        root.child("appConfig").child("maintenance").addValueEventListener(listener { s ->
            _maintenance.value = Maintenance(
                on = s?.child("on")?.getValue(Boolean::class.java) == true,
                text = s?.child("text")?.getValue(String::class.java).orEmpty(),
            )
        })
        root.child("appConfig").child("spam").addValueEventListener(listener { s ->
            _spam.value = SpamGuard(
                on = s?.child("on")?.getValue(Boolean::class.java) != false,
                limit = s?.child("limit")?.getValue(Int::class.java) ?: 40,
            )
        })
        auth.addAuthStateListener { follow(it.currentUser?.uid) }
    }

    private fun listener(onValue: (DataSnapshot?) -> Unit) = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) = onValue(snapshot)
        override fun onCancelled(error: DatabaseError) = onValue(null)
    }

    private var following: String? = null

    /** The signed-in account changed: its admin mark, ban and announcements are listened to anew. */
    private fun follow(uid: String?) {
        if (uid == following) return
        following = uid
        attached.forEach { (q, l) -> q.removeEventListener(l) }
        attached.clear()
        _isAdmin.value = false
        _myBan.value = null
        _announcement.value = null
        if (uid == null) return
        fun watch(q: Query, onValue: (DataSnapshot?) -> Unit) {
            val l = listener(onValue)
            q.addValueEventListener(l)
            attached += q to l
        }
        watch(root.child("admins").child(uid)) { _isAdmin.value = it?.getValue(Boolean::class.java) == true }
        // A crash kept from before the sign-in goes now.
        pendingCrash?.let { sendCrash(it) }
        watch(root.child("bans").child(uid)) { _myBan.value = it?.let(::banOf) }
        watch(root.child("announcements").orderByChild("at").limitToLast(1)) { snap ->
            _latest.value = snap?.children?.lastOrNull()?.let { a ->
                Announcement(a.key.orEmpty(), a.child("text").getValue(String::class.java).orEmpty(), a.child("at").getValue(Long::class.java) ?: 0L)
            }
            refreshAnnouncement()
        }
    }

    private fun refreshAnnouncement() {
        val a = _latest.value
        _announcement.value = a?.takeIf {
            it.text.isNotBlank() && it.id != prefs.getString(KEY_SEEN, null) && it.at > System.currentTimeMillis() - ANNOUNCE_DAYS_MS
        }
    }

    override fun dismissAnnouncement(id: String) {
        prefs.edit().putString(KEY_SEEN, id).apply()
        refreshAnnouncement()
    }

    // ------------------------------------------------------------------ reading

    /** A node as it changes; null when it may not be read. */
    private fun snapshots(q: Query): Flow<DataSnapshot?> = callbackFlow {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot)
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(null)
            }
        }
        q.addValueEventListener(l)
        awaitClose { q.removeEventListener(l) }
    }

    private fun banOf(s: DataSnapshot): AdminBan? {
        if (!s.exists()) return null
        return AdminBan(
            kind = s.child("kind").getValue(String::class.java) ?: "ban",
            until = s.child("until").getValue(Long::class.java) ?: 0L,
            reason = s.child("reason").getValue(String::class.java).orEmpty(),
            at = s.child("at").getValue(Long::class.java) ?: 0L,
        )
    }

    override fun users(): Flow<List<AdminUser>> = combine(
        listOf(
            snapshots(root.child("users")),
            snapshots(root.child("presence")),
            snapshots(root.child("appInfo")),
            snapshots(root.child("bans")),
            snapshots(root.child("adminStats").child("users")),
            snapshots(root.child("badges")),
            snapshots(root.child("claudeAccess")),
        ),
    ) { all ->
        val (users, presence, info, bans, stats) = all
        val badges = all[5]
        val claude = all[6]
        users?.children?.mapNotNull { u ->
            val uid = u.key ?: return@mapNotNull null
            val name = u.child("name").getValue(String::class.java) ?: return@mapNotNull null
            val p = presence?.child(uid)
            val i = info?.child(uid)
            val st = stats?.child(uid)
            AdminUser(
                uid = uid,
                name = name,
                username = u.child("username").getValue(String::class.java).orEmpty(),
                photo = u.child("photo").getValue(String::class.java),
                online = p?.child("online")?.getValue(Boolean::class.java) == true,
                hidden = p?.child("hidden")?.getValue(Boolean::class.java) == true,
                lastSeen = p?.child("lastSeen")?.getValue(Long::class.java) ?: 0L,
                version = i?.child("v")?.getValue(String::class.java),
                lastOpen = i?.child("at")?.getValue(Long::class.java) ?: 0L,
                created = st?.child("created")?.getValue(Long::class.java) ?: 0L,
                lastSignIn = st?.child("lastSignIn")?.getValue(Long::class.java) ?: 0L,
                chats = st?.child("chats")?.getValue(Int::class.java) ?: 0,
                ban = bans?.child(uid)?.let(::banOf),
                developer = badges?.child(uid)?.getValue(String::class.java) == "developer",
                claude = claude?.child(uid)?.getValue(Boolean::class.java) == true,
            )
        }.orEmpty()
    }

    override fun reports(): Flow<List<AdminReport>> = snapshots(root.child("reports").orderByChild("at").limitToLast(300)).map { snap ->
        snap?.children?.map { r ->
            val recent = r.child("recent").children.map { m ->
                m.child("from").getValue(String::class.java).orEmpty() to m.child("text").getValue(String::class.java).orEmpty()
            }
            AdminReport(
                id = r.key.orEmpty(),
                by = r.child("by").getValue(String::class.java).orEmpty(),
                target = r.child("target").getValue(String::class.java).orEmpty(),
                chatId = r.child("chatId").getValue(String::class.java).orEmpty(),
                reason = r.child("reason").getValue(String::class.java).orEmpty(),
                at = r.child("at").getValue(Long::class.java) ?: 0L,
                recent = recent,
                handled = r.child("handled").getValue(String::class.java),
            )
        }.orEmpty().sortedByDescending { it.at }
    }

    override fun groups(): Flow<List<AdminGroup>> = snapshots(root.child("groups")).map { snap ->
        snap?.children?.mapNotNull { g ->
            val id = g.key ?: return@mapNotNull null
            val members = g.child("members").children.map { m -> m.key.orEmpty() to (m.child("name").getValue(String::class.java) ?: "?") }
            AdminGroup(
                id = id,
                name = g.child("name").getValue(String::class.java) ?: "?",
                photo = g.child("photo").getValue(String::class.java),
                members = members,
                admins = g.child("members").children.filter { it.child("role").getValue(String::class.java) == "admin" }.mapNotNull { it.key }.toSet(),
                createdAt = g.child("createdAt").getValue(Long::class.java) ?: 0L,
                createdBy = g.child("createdBy").getValue(String::class.java).orEmpty(),
                secret = g.child("secret").getValue(Boolean::class.java) == true,
            )
        }.orEmpty().sortedByDescending { it.createdAt }
    }

    override fun actions(): Flow<List<AdminActionEntry>> = snapshots(root.child("adminActions").orderByChild("at").limitToLast(300)).map { snap ->
        snap?.children?.map { a ->
            AdminActionEntry(
                id = a.key.orEmpty(),
                kind = a.child("kind").getValue(String::class.java).orEmpty(),
                target = a.child("target").getValue(String::class.java).orEmpty(),
                value = a.child("value").getValue(String::class.java).orEmpty(),
                by = a.child("by").getValue(String::class.java).orEmpty(),
                at = a.child("at").getValue(Long::class.java) ?: 0L,
                done = a.child("done").getValue(Boolean::class.java) == true,
                error = a.child("error").getValue(String::class.java),
                result = a.child("result").getValue(String::class.java),
            )
        }.orEmpty().sortedByDescending { it.at }
    }

    override fun announcements(): Flow<List<Announcement>> = snapshots(root.child("announcements").orderByChild("at").limitToLast(50)).map { snap ->
        snap?.children?.map { a ->
            Announcement(a.key.orEmpty(), a.child("text").getValue(String::class.java).orEmpty(), a.child("at").getValue(Long::class.java) ?: 0L)
        }.orEmpty().sortedByDescending { it.at }
    }

    override fun bannedNames(): Flow<List<String>> = snapshots(root.child("bannedNames")).map { snap ->
        snap?.children?.mapNotNull { it.key }.orEmpty().sorted()
    }

    override fun stats(): Flow<AdminStats?> = snapshots(root.child("adminStats")).map { s ->
        s?.takeIf { it.exists() }?.let {
            AdminStats(
                at = it.child("at").getValue(Long::class.java) ?: 0L,
                mediaBytes = it.child("mediaBytes").getValue(Long::class.java) ?: 0L,
                diskFree = it.child("diskFree").getValue(Long::class.java) ?: 0L,
                diskTotal = it.child("diskTotal").getValue(Long::class.java) ?: 0L,
                storage = it.child("storage").children.mapNotNull { e ->
                    StorageEntry(
                        id = e.child("id").getValue(String::class.java) ?: return@mapNotNull null,
                        bytes = e.child("bytes").getValue(Long::class.java) ?: 0L,
                        files = e.child("files").getValue(Int::class.java) ?: 0,
                    )
                },
                uptimeSec = it.child("uptimeSec").getValue(Long::class.java) ?: 0L,
                memUsedMb = it.child("memUsedMb").getValue(Long::class.java) ?: 0L,
                memTotalMb = it.child("memTotalMb").getValue(Long::class.java) ?: 0L,
                nodeRssMb = it.child("nodeRssMb").getValue(Long::class.java) ?: 0L,
                backupAt = it.child("backupAt").getValue(Long::class.java) ?: 0L,
                backupBytes = it.child("backupBytes").getValue(Long::class.java) ?: 0L,
                backupCount = it.child("backupCount").getValue(Int::class.java) ?: 0,
                gamesTotal = it.child("gamesTotal").getValue(Long::class.java) ?: 0L,
                gamesActive = it.child("gamesActive").getValue(Int::class.java) ?: 0,
                gamesByKind = it.child("gamesByKind").children.mapNotNull { g ->
                    val k = g.key ?: return@mapNotNull null
                    val v = g.getValue(Int::class.java) ?: return@mapNotNull null
                    k to v
                }.toMap(),
            )
        }
    }

    override fun daily(): Flow<List<DayStat>> = snapshots(root.child("adminDaily").orderByKey().limitToLast(30)).map { snap ->
        snap?.children?.map { d ->
            fun n(key: String) = d.child(key).getValue(Int::class.java) ?: 0
            DayStat(d.key.orEmpty(), n("active"), n("newUsers"), n("total"), n("messages"))
        }.orEmpty().sortedBy { it.day }
    }

    override fun crashes(): Flow<List<CrashEntry>> = snapshots(root.child("crashes").orderByChild("at").limitToLast(200)).map { snap ->
        snap?.children?.map { c ->
            CrashEntry(
                id = c.key.orEmpty(),
                uid = c.child("uid").getValue(String::class.java).orEmpty(),
                version = c.child("v").getValue(String::class.java).orEmpty(),
                device = c.child("device").getValue(String::class.java).orEmpty(),
                at = c.child("at").getValue(Long::class.java) ?: 0L,
                trace = c.child("trace").getValue(String::class.java).orEmpty(),
            )
        }.orEmpty().sortedByDescending { it.at }
    }

    override fun callDebug(): Flow<List<CallDebugEntry>> = snapshots(root.child("callDebug")).map { snap ->
        buildList {
            snap?.children?.forEach { userSnap ->
                val uid = userSnap.key ?: return@forEach
                userSnap.children.forEach { c ->
                    val id = c.key ?: return@forEach
                    add(
                        CallDebugEntry(
                            id = id,
                            uid = uid,
                            at = c.child("at").getValue(Long::class.java) ?: 0L,
                            peer = c.child("peer").getValue(String::class.java).orEmpty(),
                            caller = c.child("caller").getValue(Boolean::class.java) == true,
                            video = c.child("video").getValue(Boolean::class.java) == true,
                            reason = c.child("reason").getValue(String::class.java).orEmpty(),
                            phase = c.child("phase").getValue(String::class.java).orEmpty(),
                            connectedOnce = c.child("connectedOnce").getValue(Boolean::class.java) == true,
                            model = c.child("model").getValue(String::class.java).orEmpty(),
                            androidSdk = c.child("android").getValue(Int::class.java) ?: 0,
                            trail = c.child("trail").children.mapNotNull { it.getValue(String::class.java) },
                        ),
                    )
                }
            }
        }.sortedByDescending { it.at }.take(120)
    }

    // ------------------------------------------------------------------ the crash of this phone

    @Volatile private var pendingCrash: String? = null

    override fun reportCrash(text: String) {
        val key = text.hashCode().toString()
        if (prefs.getString(KEY_CRASH_SENT, null) == key) return
        pendingCrash = text
        if (auth.currentUser != null) sendCrash(text)
    }

    private fun sendCrash(text: String) {
        val uid = auth.currentUser?.uid ?: return
        pendingCrash = null
        // The first line is "QMusic <version> on <maker> <model>, Android <n>" (see FileCrashReports).
        val head = text.lineSequence().firstOrNull().orEmpty()
        val version = head.substringAfter("QMusic ", "").substringBefore(" on ").take(20)
        val device = head.substringAfter(" on ", "").take(100)
        val trace = text.lineSequence().drop(1).dropWhile { it.startsWith("Thread:") }.joinToString("\n").take(7_900)
        root.child("crashes").push().setValue(
            mapOf("uid" to uid, "v" to version, "device" to device, "trace" to trace, "at" to ServerValue.TIMESTAMP),
        ).addOnSuccessListener { prefs.edit().putString(KEY_CRASH_SENT, text.hashCode().toString()).apply() }
    }

    // ------------------------------------------------------------------ doing

    private fun me(): String = auth.currentUser?.uid ?: error("signed out")

    /** Every step is kept in adminActions (the server does the ones the rules leave to it, and marks them done). */
    private suspend fun record(kind: String, target: String, value: String) {
        root.child("adminActions").push().setValue(
            mapOf("kind" to kind, "target" to target, "value" to value.take(2000), "by" to me(), "at" to ServerValue.TIMESTAMP),
        ).await()
    }

    override suspend fun ban(uid: String, kind: String, until: Long, reason: String): Result<Unit> = runCatching {
        require(kind == "ban" || kind == "mute")
        require(uid != me()) { "self" }
        root.child("bans").child(uid).setValue(
            mapOf("kind" to kind, "until" to until, "reason" to reason.trim().take(200), "by" to me(), "at" to ServerValue.TIMESTAMP),
        ).await()
        record(kind, uid, reason.trim())
    }

    override suspend fun unban(uid: String): Result<Unit> = runCatching {
        root.child("bans").child(uid).removeValue().await()
        record("unban", uid, "")
    }

    override suspend fun request(kind: String, target: String, value: String): Result<Unit> = runCatching {
        record(kind, target, value)
    }

    override suspend fun markReport(id: String, handled: String?): Result<Unit> = runCatching {
        root.child("reports").child(id).child("handled").setValue(handled).await()
        if (handled != null) record("report", id, handled)
    }

    override suspend fun deleteReport(id: String): Result<Unit> = runCatching {
        root.child("reports").child(id).removeValue().await()
    }

    override suspend fun setMinVersion(version: String?): Result<Unit> = runCatching {
        val v = version?.trim()?.takeIf { it.isNotEmpty() }
        root.child("appConfig").child("minVersion").setValue(v).await()
        record("minVersion", "", v.orEmpty())
    }

    override suspend fun setRecommendedVersion(version: String?): Result<Unit> = runCatching {
        val v = version?.trim()?.takeIf { it.isNotEmpty() }
        root.child("appConfig").child("recommendedVersion").setValue(v).await()
        record("recVersion", "", v.orEmpty())
    }

    override suspend fun setFeatures(features: FeatureFlags): Result<Unit> = runCatching {
        root.child("appConfig").child("features").setValue(
            mapOf(
                "games" to features.games,
                "calls" to features.calls,
                "statuses" to features.statuses,
                "newChats" to features.newChats,
            ),
        ).await()
        val off = buildList {
            if (!features.games) add("games")
            if (!features.calls) add("calls")
            if (!features.statuses) add("statuses")
            if (!features.newChats) add("newChats")
        }
        record("features", "", if (off.isEmpty()) "all on" else "off: " + off.joinToString(","))
    }

    override suspend fun setMaintenance(on: Boolean, text: String): Result<Unit> = runCatching {
        root.child("appConfig").child("maintenance").setValue(mapOf("on" to on, "text" to text.trim().take(300))).await()
        record("maintenance", "", if (on) "on" else "off")
    }

    override suspend fun setSpam(on: Boolean, limit: Int): Result<Unit> = runCatching {
        root.child("appConfig").child("spam").setValue(mapOf("on" to on, "limit" to limit.coerceIn(10, 500))).await()
        record("spam", "", if (on) limit.toString() else "off")
    }

    override suspend fun setBadge(uid: String, developer: Boolean): Result<Unit> = runCatching {
        root.child("badges").child(uid).setValue(if (developer) "developer" else null).await()
        record("badge", uid, if (developer) "on" else "off")
    }

    override suspend fun setClaude(uid: String, allowed: Boolean): Result<Unit> = runCatching {
        root.child("claudeAccess").child(uid).setValue(if (allowed) true else null).await()
        record("claude", uid, if (allowed) "on" else "off")
    }

    override suspend fun deleteCrash(id: String): Result<Unit> = runCatching {
        root.child("crashes").child(id).removeValue().await()
    }

    override suspend fun deleteAnnouncement(id: String): Result<Unit> = runCatching {
        require(id.isNotBlank())
        root.child("announcements").child(id).removeValue().await()
        record("delAnnounce", id, "")
    }

    override suspend fun deleteCallDebug(uid: String, callId: String): Result<Unit> = runCatching {
        require(uid.isNotBlank() && callId.isNotBlank())
        root.child("callDebug").child(uid).child(callId).removeValue().await()
    }

    override suspend fun banName(name: String, banned: Boolean): Result<Unit> = runCatching {
        val n = name.trim().lowercase().removePrefix("@")
        require(Regex("^[a-z0-9._]{3,20}$").matches(n)) { "name" }
        root.child("bannedNames").child(n).setValue(if (banned) true else null).await()
        record("banName", n, if (banned) "on" else "off")
    }

    private companion object {
        const val KEY_CRASH_SENT = "crash_sent"
        const val KEY_SEEN = "announcement_seen"
        const val ANNOUNCE_DAYS_MS = 7L * 24 * 3600 * 1000
    }
}

/** For the app: the last crash goes to the admins' panel (once; when an account is signed in). */
object AdminCrashes {
    fun report(context: Context, text: String) {
        runCatching { FirebaseChatBackend.get(context.applicationContext)?.admin?.reportCrash(text) }
    }
}

/** Signs in with a Firebase custom token (made only with the server's private key): the test account on an emulator. */
object TestSignIn {
    fun withToken(token: String) {
        runCatching { FirebaseAuth.getInstance().signInWithCustomToken(token) }
    }
}

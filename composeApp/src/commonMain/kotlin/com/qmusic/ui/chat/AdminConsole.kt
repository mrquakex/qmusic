package com.qmusic.ui.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Running the app: bans and mutes, reports, closing groups, setting profiles right, announcements, the least version
 * that may be used, barred user names, and counts. Everyone has the part about themselves (their ban, the least
 * version, the latest announcement); the rest works only for admins (admins/{uid} = true, set on the server, never from
 * a phone) and the database rules and the server check every step again. Nobody's messages are read: only what someone
 * chose to send in a report.
 */
interface AdminConsole {
    /** This account may use the panel. */
    val isAdmin: StateFlow<Boolean>

    /** This account's ban or mute, while one is set (it may have ended already: see [AdminBan.activeAt]). */
    val myBan: StateFlow<AdminBan?>

    /** The least version that may be used (older ones are asked to update before going on); null: any. */
    val minVersion: StateFlow<String?>

    /** Soft recommended version (older ones see a dismissable banner, never blocked); null: none. */
    val recommendedVersion: StateFlow<String?> get() = MutableStateFlow(null)

    /** Emergency feature kill switches (admins bypass these so they can still test). */
    val features: StateFlow<FeatureFlags> get() = MutableStateFlow(FeatureFlags())

    /** Admin-managed film/video catalog sources (live domain updates without an APK rebuild). */
    val movieSources: StateFlow<List<MovieSource>> get() = MutableStateFlow(emptyList())

    /** "Under maintenance": while on, the messages show a maintenance page to everyone but the admins. */
    val maintenance: StateFlow<Maintenance>

    /** The automatic spam guard (messages a minute above which someone is muted for an hour). */
    val spam: StateFlow<SpamGuard>

    /** Sends the last crash of this phone to the admins (once per crash; needs a signed-in account). */
    fun reportCrash(text: String)

    /** The newest announcement this person has not seen yet (a week at most), or null. */
    val announcement: StateFlow<Announcement?>
    fun dismissAnnouncement(id: String)

    fun users(): Flow<List<AdminUser>>
    fun reports(): Flow<List<AdminReport>>
    fun groups(): Flow<List<AdminGroup>>
    fun actions(): Flow<List<AdminActionEntry>>
    fun announcements(): Flow<List<Announcement>>
    fun bannedNames(): Flow<List<String>>
    fun stats(): Flow<AdminStats?>

    /** The last 30 days, oldest first. */
    fun daily(): Flow<List<DayStat>>
    fun crashes(): Flow<List<CrashEntry>>
    fun callDebug(): Flow<List<CallDebugEntry>> = flowOf(emptyList())

    /** [kind]: "ban" (the app is closed to them) or "mute" (they can read, not write); [until]: [AdminBan.FOREVER] for good. */
    suspend fun ban(uid: String, kind: String, until: Long, reason: String): Result<Unit>
    suspend fun unban(uid: String): Result<Unit>

    /** What the server does: "closeGroup", "rename" (value: the new name), "reset" (value: photo/name/about/status), "announce", "stats", "backupNow", "gcMedia", "testPush", "msgOldVer". */
    suspend fun request(kind: String, target: String = "", value: String = ""): Result<Unit>
    suspend fun markReport(id: String, handled: String?): Result<Unit>
    suspend fun deleteReport(id: String): Result<Unit>
    suspend fun setMinVersion(version: String?): Result<Unit>
    suspend fun setRecommendedVersion(version: String?): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun setFeatures(features: FeatureFlags): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun saveMovieSource(source: MovieSource): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun deleteMovieSource(id: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun banName(name: String, banned: Boolean): Result<Unit>
    suspend fun setMaintenance(on: Boolean, text: String): Result<Unit>
    suspend fun setSpam(on: Boolean, limit: Int): Result<Unit>
    suspend fun setBadge(uid: String, developer: Boolean): Result<Unit>
    suspend fun setClaude(uid: String, allowed: Boolean): Result<Unit>
    suspend fun setAntigravity(uid: String, allowed: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun deleteCrash(id: String): Result<Unit>
    suspend fun deleteAnnouncement(id: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun deleteCallDebug(uid: String, callId: String): Result<Unit> = Result.failure(UnsupportedOperationException())
}

data class Maintenance(val on: Boolean = false, val text: String = "")

data class SpamGuard(val on: Boolean = true, val limit: Int = 40)

data class FeatureFlags(
    val games: Boolean = true,
    val calls: Boolean = true,
    val statuses: Boolean = true,
    val newChats: Boolean = true,
)

/** One admin-configured film/video source whose domain can be updated live from the Admin Panel. */
data class MovieSource(
    val id: String,
    val name: String,
    val baseUrl: String,
    val searchPath: String = "/?s={query}",
    val catalogPath: String = "/",
    val headers: String = "",
    val enabled: Boolean = true,
    val order: Int = 0,
) {
    /** Normalized base URL with no trailing slash. */
    val normalizedBaseUrl: String get() = baseUrl.trim().trimEnd('/')
}

/** Built-in default film & dizi sources so the Cinema tab is never empty on launch. */
val DEFAULT_MOVIE_SOURCES = listOf(
    MovieSource(
        id = "filmmodu",
        name = "Filmmodu",
        baseUrl = "https://filmmodu.cc",
        catalogPath = "/",
        searchPath = "/arama/oneri",
        enabled = true,
        order = 0,
    ),
    MovieSource(
        id = "dizimom",
        name = "Dizimom",
        baseUrl = "https://www.dizidad.com",
        catalogPath = "/1-dizi-arsivi/",
        searchPath = "/?s={query}",
        enabled = true,
        order = 1,
    ),
    MovieSource(
        id = "hdfilmcehennemi",
        name = "HDFilmCehennemi",
        baseUrl = "https://hdfilmcehennemi.vip",
        catalogPath = "/",
        searchPath = "/api/search?q={query}",
        enabled = true,
        order = 2,
    ),
    MovieSource(
        id = "diziyou",
        name = "Diziyou",
        baseUrl = "https://diziyou.net",
        catalogPath = "/diziler/",
        searchPath = "/?s={query}",
        enabled = true,
        order = 3,
    ),
)

/** Diagnostic trail of a finished call uploaded by a phone to callDebug/{uid}/{call}. */
data class CallDebugEntry(
    val id: String,
    val uid: String,
    val at: Long,
    val peer: String,
    val caller: Boolean,
    val video: Boolean,
    val reason: String,
    val phase: String,
    val connectedOnce: Boolean,
    val model: String,
    val androidSdk: Int,
    val trail: List<String>,
) {
    val hasIssue: Boolean get() = reason == "FAILED" || reason == "UNREACHABLE" || reason == "NO_ANSWER" || !connectedOnce
}

/** One day of [AdminConsole.daily]: who showed up, who joined, how many accounts there were, how many messages went. */
data class DayStat(val day: String, val active: Int, val newUsers: Int, val total: Int, val messages: Int)

/** A crash sent by a phone: who, which version, which phone, and the stack trace. */
data class CrashEntry(val id: String, val uid: String, val version: String, val device: String, val at: Long, val trace: String) {
    /** The line that says what went wrong (the first of the trace), for grouping the same crash together. */
    val headline: String get() = trace.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160).orEmpty()
}

/** A conversation's files on the server: how much they take (no file is opened to count them). */
data class StorageEntry(val id: String, val bytes: Long, val files: Int)

data class AdminBan(val kind: String, val until: Long, val reason: String, val at: Long) {
    val forever: Boolean get() = until >= FOREVER
    val isBan: Boolean get() = kind == "ban"
    fun activeAt(now: Long): Boolean = until > now

    companion object {
        /** "For good": 1 January 2100. */
        const val FOREVER = 4_102_444_800_000L
    }
}

data class AdminUser(
    val uid: String,
    val name: String,
    val username: String,
    val photo: String?,
    val online: Boolean,
    val hidden: Boolean,
    val lastSeen: Long,
    /** The version of the app they opened last, and when (0: not known; it is written from 2.13.8 on). */
    val version: String?,
    val lastOpen: Long,
    /** From the server's counts (0 until they were made once). */
    val created: Long,
    val lastSignIn: Long,
    val chats: Int,
    val ban: AdminBan?,
    /** The "developer" badge, and leave to talk to Claude. */
    val developer: Boolean = false,
    val claude: Boolean = false,
    /** Leave to talk to Antigravity (Gemini). */
    val antigravity: Boolean = false,
) {
    /** The latest sign of life there is. */
    val lastActive: Long get() = maxOf(lastSeen, lastOpen, lastSignIn)
}

data class AdminReport(
    val id: String,
    val by: String,
    val target: String,
    val chatId: String,
    val reason: String,
    val at: Long,
    /** The messages the person who reported chose to send with it: (writer uid, text). */
    val recent: List<Pair<String, String>>,
    /** null: open; else what was done ("ok", "banned", ...). */
    val handled: String?,
)

data class AdminGroup(
    val id: String,
    val name: String,
    val photo: String?,
    /** (uid, name) of each member. */
    val members: List<Pair<String, String>>,
    val admins: Set<String>,
    val createdAt: Long,
    val createdBy: String,
    val secret: Boolean,
)

data class AdminActionEntry(
    val id: String,
    val kind: String,
    val target: String,
    val value: String,
    val by: String,
    val at: Long,
    val done: Boolean,
    val error: String?,
    val result: String? = null,
)

data class AdminStats(
    val at: Long,
    val mediaBytes: Long,
    val diskFree: Long,
    val diskTotal: Long,
    val storage: List<StorageEntry> = emptyList(),
    val uptimeSec: Long = 0L,
    val memUsedMb: Long = 0L,
    val memTotalMb: Long = 0L,
    val nodeRssMb: Long = 0L,
    val backupAt: Long = 0L,
    val backupBytes: Long = 0L,
    val backupCount: Int = 0,
    val gamesTotal: Long = 0L,
    val gamesActive: Int = 0,
    val gamesByKind: Map<String, Int> = emptyMap(),
)

data class Announcement(val id: String, val text: String, val at: Long)

/** A tap on "update" on the page that asks for a newer version: the app (which has the updater) listens. */
object ForcedUpdate {
    val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

/** True when [pick] is enabled (or there is no admin console, or the signed-in user is an admin). */
internal fun AdminConsole?.featureEnabled(pick: (FeatureFlags) -> Boolean): Boolean =
    this == null || isAdmin.value || pick(features.value)

internal enum class PeopleFilter { ALL, ONLINE, RESTRICTED, OUTDATED, DEVELOPER, CLAUDE, ANTIGRAVITY }

internal enum class LogCategory { ALL, MODERATION, SPAM, ANNOUNCE, ACCOUNT, SETTINGS }

internal fun logCategoryOf(kind: String): LogCategory = when (kind) {
    "ban", "mute", "unban", "report" -> LogCategory.MODERATION
    "autoMute", "spam" -> LogCategory.SPAM
    "announce", "delAnnounce", "message", "msgOldVer", "testPush" -> LogCategory.ANNOUNCE
    "closeGroup", "rename", "reset", "badge", "claude", "antigravity", "deleteAccount", "deleteMedia" -> LogCategory.ACCOUNT
    "minVersion", "recVersion", "maintenance", "features", "movieSource", "delMovieSrc", "banName", "backupNow", "gcMedia" -> LogCategory.SETTINGS
    else -> LogCategory.SETTINGS
}

/** Optional admin note written on a handled report ("ok: <note>" or "muted: <note>"). */
internal fun reportNote(handled: String?): String? {
    val idx = handled?.indexOf(':') ?: return null
    if (idx < 0) return null
    return handled.substring(idx + 1).trim().takeIf { it.isNotEmpty() }
}

/** Filters people in the admin panel by search query, quick category filter, and optional clicked version. */
internal fun filterAdminUsers(
    users: List<AdminUser>,
    query: String,
    filter: PeopleFilter,
    versionFilter: String?,
    latestVersion: String,
    now: Long,
): List<AdminUser> {
    val q = query.trim().lowercase().removePrefix("@")
    return users.filter { u ->
        val matchesQuery = q.isEmpty() ||
            u.name.lowercase().contains(q) ||
            u.username.lowercase().contains(q) ||
            u.uid.lowercase().contains(q)
        val matchesVersion = when (versionFilter) {
            null -> true
            "" -> u.version == null
            else -> u.version == versionFilter
        }
        val matchesFilter = when (filter) {
            PeopleFilter.ALL -> true
            PeopleFilter.ONLINE -> u.online
            PeopleFilter.RESTRICTED -> u.ban?.activeAt(now) == true
            PeopleFilter.OUTDATED -> !isAiBot(u.uid) && u.uid != "nuvelo" && u.version != null && versionOlder(u.version, latestVersion)
            PeopleFilter.DEVELOPER -> u.developer
            PeopleFilter.CLAUDE -> u.claude
            PeopleFilter.ANTIGRAVITY -> u.antigravity
        }
        matchesQuery && matchesVersion && matchesFilter
    }
}

/** True when [version] ("2.13.7") is older than [least] ("2.13.8"), number by number. */
internal fun versionOlder(version: String, least: String): Boolean {
    val a = version.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val b = least.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}

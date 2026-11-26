package com.qmusic.ui

import com.qmusic.ui.chat.AdminActionEntry
import com.qmusic.ui.chat.AdminBan
import com.qmusic.ui.chat.AdminConsole
import com.qmusic.ui.chat.AdminGroup
import com.qmusic.ui.chat.AdminReport
import com.qmusic.ui.chat.AdminStats
import com.qmusic.ui.chat.AdminUser
import com.qmusic.ui.chat.Announcement
import com.qmusic.ui.chat.CallDebugEntry
import com.qmusic.ui.chat.CrashEntry
import com.qmusic.ui.chat.DayStat
import com.qmusic.ui.chat.FeatureFlags
import com.qmusic.ui.chat.LogCategory
import com.qmusic.ui.chat.Maintenance
import com.qmusic.ui.chat.PeopleFilter
import com.qmusic.ui.chat.SpamGuard
import com.qmusic.ui.chat.featureEnabled
import com.qmusic.ui.chat.filterAdminUsers
import com.qmusic.ui.chat.logCategoryOf
import com.qmusic.ui.chat.reportNote
import com.qmusic.ui.chat.versionOlder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminPolishTest {

    private class FakeConsole(
        admin: Boolean,
        flags: FeatureFlags,
    ) : AdminConsole {
        override val isAdmin: StateFlow<Boolean> = MutableStateFlow(admin)
        override val myBan: StateFlow<AdminBan?> = MutableStateFlow(null)
        override val minVersion: StateFlow<String?> = MutableStateFlow(null)
        override val features: StateFlow<FeatureFlags> = MutableStateFlow(flags)
        override val maintenance: StateFlow<Maintenance> = MutableStateFlow(Maintenance())
        override val spam: StateFlow<SpamGuard> = MutableStateFlow(SpamGuard())
        override val announcement: StateFlow<Announcement?> = MutableStateFlow(null)
        override fun reportCrash(text: String) {}
        override fun dismissAnnouncement(id: String) {}
        override fun users(): Flow<List<AdminUser>> = flowOf(emptyList())
        override fun reports(): Flow<List<AdminReport>> = flowOf(emptyList())
        override fun groups(): Flow<List<AdminGroup>> = flowOf(emptyList())
        override fun actions(): Flow<List<AdminActionEntry>> = flowOf(emptyList())
        override fun announcements(): Flow<List<Announcement>> = flowOf(emptyList())
        override fun bannedNames(): Flow<List<String>> = flowOf(emptyList())
        override fun stats(): Flow<AdminStats?> = flowOf(null)
        override fun daily(): Flow<List<DayStat>> = flowOf(emptyList())
        override fun crashes(): Flow<List<CrashEntry>> = flowOf(emptyList())
        override suspend fun ban(uid: String, kind: String, until: Long, reason: String) = Result.success(Unit)
        override suspend fun unban(uid: String) = Result.success(Unit)
        override suspend fun request(kind: String, target: String, value: String) = Result.success(Unit)
        override suspend fun markReport(id: String, handled: String?) = Result.success(Unit)
        override suspend fun deleteReport(id: String) = Result.success(Unit)
        override suspend fun setMinVersion(version: String?) = Result.success(Unit)
        override suspend fun banName(name: String, banned: Boolean) = Result.success(Unit)
        override suspend fun setMaintenance(on: Boolean, text: String) = Result.success(Unit)
        override suspend fun setSpam(on: Boolean, limit: Int) = Result.success(Unit)
        override suspend fun setBadge(uid: String, developer: Boolean) = Result.success(Unit)
        override suspend fun setClaude(uid: String, allowed: Boolean) = Result.success(Unit)
        override suspend fun deleteCrash(id: String) = Result.success(Unit)
    }

    private fun user(
        uid: String,
        name: String,
        username: String = uid,
        online: Boolean = false,
        version: String? = "2.13.17",
        ban: AdminBan? = null,
        dev: Boolean = false,
        claude: Boolean = false,
    ) = AdminUser(
        uid = uid,
        name = name,
        username = username,
        photo = null,
        online = online,
        hidden = false,
        lastSeen = 1_000L,
        version = version,
        lastOpen = 1_000L,
        created = 500L,
        lastSignIn = 1_000L,
        chats = 2,
        ban = ban,
        developer = dev,
        claude = claude,
    )

    @Test
    fun featureFlagsBypassedForAdminsAndNullConsole() {
        val nullConsole: AdminConsole? = null
        assertTrue(nullConsole.featureEnabled { it.games })

        val disabled = FeatureFlags(games = false, calls = false, statuses = false, newChats = false)
        val regular = FakeConsole(admin = false, flags = disabled)
        assertFalse(regular.featureEnabled { it.games })
        assertFalse(regular.featureEnabled { it.calls })
        assertFalse(regular.featureEnabled { it.statuses })
        assertFalse(regular.featureEnabled { it.newChats })

        val admin = FakeConsole(admin = true, flags = disabled)
        assertTrue(admin.featureEnabled { it.games })
        assertTrue(admin.featureEnabled { it.calls })
        assertTrue(admin.featureEnabled { it.statuses })
        assertTrue(admin.featureEnabled { it.newChats })
    }

    @Test
    fun versionOlderComparesNumericSegments() {
        assertTrue(versionOlder("2.13.9", "2.13.17"))
        assertTrue(versionOlder("2.13.16", "2.13.17"))
        assertFalse(versionOlder("2.13.17", "2.13.17"))
        assertFalse(versionOlder("2.14.0", "2.13.17"))
        assertTrue(versionOlder("2.13", "2.13.1"))
    }

    @Test
    fun filterAdminUsersHandlesCategoriesVersionsAndUidQuery() {
        val now = 10_000L
        val activeMute = AdminBan("mute", until = 20_000L, reason = "spam", at = 9_000L)
        val expiredMute = AdminBan("mute", until = 5_000L, reason = "old", at = 1_000L)
        val list = listOf(
            user("u1", "Ayşe", online = true, version = "2.13.17", dev = true),
            user("u2", "Ali", online = false, version = "2.13.12", ban = activeMute, claude = true),
            user("u3", "Veli", online = false, version = null, ban = expiredMute),
        )

        assertEquals(listOf("u1"), filterAdminUsers(list, "", PeopleFilter.ONLINE, null, "2.13.17", now).map { it.uid })
        assertEquals(listOf("u2"), filterAdminUsers(list, "", PeopleFilter.RESTRICTED, null, "2.13.17", now).map { it.uid })
        assertEquals(listOf("u2"), filterAdminUsers(list, "", PeopleFilter.OUTDATED, null, "2.13.17", now).map { it.uid })
        assertEquals(listOf("u1"), filterAdminUsers(list, "", PeopleFilter.DEVELOPER, null, "2.13.17", now).map { it.uid })
        assertEquals(listOf("u2"), filterAdminUsers(list, "", PeopleFilter.CLAUDE, null, "2.13.17", now).map { it.uid })
        assertEquals(listOf("u3"), filterAdminUsers(list, "", PeopleFilter.ALL, "", "2.13.17", now).map { it.uid })
        assertEquals(listOf("u2"), filterAdminUsers(list, "", PeopleFilter.ALL, "2.13.12", "2.13.17", now).map { it.uid })
        assertEquals(listOf("u3"), filterAdminUsers(list, "u3", PeopleFilter.ALL, null, "2.13.17", now).map { it.uid })
    }

    @Test
    fun reportNoteAndLogCategoriesWork() {
        assertNull(reportNote(null))
        assertNull(reportNote("ok"))
        assertNull(reportNote("ok:   "))
        assertEquals("Uyarıldı", reportNote("ok: Uyarıldı"))
        assertEquals("spam için", reportNote("muted: spam için"))

        assertEquals(LogCategory.MODERATION, logCategoryOf("ban"))
        assertEquals(LogCategory.SPAM, logCategoryOf("autoMute"))
        assertEquals(LogCategory.ANNOUNCE, logCategoryOf("msgOldVer"))
        assertEquals(LogCategory.ANNOUNCE, logCategoryOf("testPush"))
        assertEquals(LogCategory.ACCOUNT, logCategoryOf("deleteAccount"))
        assertEquals(LogCategory.SETTINGS, logCategoryOf("features"))
        assertEquals(LogCategory.SETTINGS, logCategoryOf("backupNow"))
    }

    @Test
    fun callDebugIssueDetectionAndStatsDefaults() {
        val okCall = CallDebugEntry("c1", "u1", 100L, "Ali", true, false, "HUNG_UP", "ACTIVE", true, "Pixel", 35, listOf("connected"))
        val dropped = CallDebugEntry("c2", "u1", 200L, "Veli", true, true, "FAILED", "CONNECTING", false, "Pixel", 35, listOf("ice failed"))
        assertFalse(okCall.hasIssue)
        assertTrue(dropped.hasIssue)

        val stats = AdminStats(at = 1L, mediaBytes = 0L, diskFree = 10L, diskTotal = 20L, gamesTotal = 12L, gamesActive = 2, gamesByKind = mapOf("xox" to 5))
        assertEquals(12L, stats.gamesTotal)
        assertEquals(2, stats.gamesActive)
        assertEquals(5, stats.gamesByKind["xox"])
        assertEquals(0L, stats.uptimeSec)
        assertEquals(0, stats.backupCount)
    }
}

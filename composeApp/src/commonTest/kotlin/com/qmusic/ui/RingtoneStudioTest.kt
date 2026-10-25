package com.qmusic.ui

import com.qmusic.engine.models.Artist
import com.qmusic.engine.models.SongItem
import com.qmusic.player.NoOpPlayerController
import com.qmusic.player.ringtone.RingtoneMath
import com.qmusic.ringtone.ApplyResult
import com.qmusic.ringtone.ContactResult
import com.qmusic.ringtone.MadeRingtone
import com.qmusic.ringtone.RingtoneMaker
import com.qmusic.ringtone.RingtonePhase
import com.qmusic.ringtone.RingtoneRequest
import com.qmusic.ringtone.RingtoneSource
import com.qmusic.ringtone.RingtoneStep
import com.qmusic.ringtone.RingtoneStudio
import com.qmusic.ringtone.RingtoneTarget
import com.qmusic.settings.InMemoryAppSettings
import com.qmusic.ui.strings.TurkishStrings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the ringtone editor does, with a pretend phone underneath. */
@OptIn(ExperimentalCoroutinesApi::class)
class RingtoneStudioTest {

    private class FakeMaker : RingtoneMaker {
        override val available = true
        override val previewPosition: MutableStateFlow<Long?> = MutableStateFlow(null)
        override val playingSaved: MutableStateFlow<String?> = MutableStateFlow(null)

        /** One value per 50 ms: 3 minutes, quiet, with a loud stretch from 1:00 to 1:30. */
        val energy = FloatArray(3600) { if (it in 1200 until 1800) 0.5f else 0.05f }
        var failLoad: Throwable? = null
        var applyResult: ApplyResult = ApplyResult.Done
        var contactResult: ContactResult = ContactResult.Done("Ayşe")
        var deleteResult = true
        val existing = HashSet<String>()
        val setAs = HashMap<String, MutableSet<RingtoneTarget>>()
        var previews = 0
        var lastPreview: List<Any>? = null
        var stopped = 0
        var released = ArrayList<String>()
        var lastRequest: RingtoneRequest? = null

        override suspend fun load(song: SongItem, onProgress: (RingtoneStep, Float) -> Unit): Result<RingtoneSource> {
            onProgress(RingtoneStep.FETCH, 1f)
            onProgress(RingtoneStep.ANALYZE, 1f)
            return failLoad?.let { Result.failure(it) } ?: Result.success(RingtoneSource(song.id, 180_000L, energy))
        }

        override fun release(songId: String) {
            released += songId
        }

        override suspend fun save(song: SongItem, request: RingtoneRequest, onProgress: (Float) -> Unit): Result<MadeRingtone> {
            lastRequest = request
            onProgress(0.5f)
            val made = MadeRingtone("content://media/${existing.size + 1}", request.name, request.artist, request.endMs - request.startMs, 1L, song.id)
            existing += made.uri
            return Result.success(made)
        }

        override fun startPreview(songId: String, startMs: Long, endMs: Long, fadeIn: Boolean, fadeOut: Boolean) {
            previews++
            lastPreview = listOf(startMs, endMs, fadeIn, fadeOut)
            previewPosition.value = startMs
        }

        override fun updatePreview(startMs: Long, endMs: Long, fadeIn: Boolean, fadeOut: Boolean) {
            lastPreview = listOf(startMs, endMs, fadeIn, fadeOut)
        }

        override fun playSaved(ringtone: MadeRingtone) {
            playingSaved.value = ringtone.uri
        }

        override fun stopPreview() {
            stopped++
            previewPosition.value = null
            playingSaved.value = null
        }

        override suspend fun apply(ringtone: MadeRingtone, target: RingtoneTarget): ApplyResult {
            if (applyResult == ApplyResult.Done) setAs.getOrPut(ringtone.uri) { HashSet() } += target
            return applyResult
        }

        override suspend fun applyToContact(ringtone: MadeRingtone): ContactResult = contactResult

        override fun inUse(ringtone: MadeRingtone): Set<RingtoneTarget> = setAs[ringtone.uri].orEmpty()

        override suspend fun exists(ringtone: MadeRingtone): Boolean = ringtone.uri in existing

        override suspend fun delete(ringtone: MadeRingtone): Boolean {
            existing -= ringtone.uri
            return deleteResult
        }
    }

    private val testScope = TestScope(StandardTestDispatcher())
    private val maker = FakeMaker()
    private val player = NoOpPlayerController()
    private val settings = InMemoryAppSettings()
    private val messages = ArrayList<String>()
    private var online = true

    private fun studio(scope: kotlinx.coroutines.CoroutineScope) =
        RingtoneStudio(maker, player, settings, scope, { TurkishStrings }, { messages += it }, { online })

    private fun song(duration: Int? = 180) = SongItem("s1", "Dracula", listOf(Artist("Tame Impala")), duration = duration)

    @Test
    fun openingLoadsTheSongAndStartsOnItsLoudestPart() = testScope.runTest {
        val studio = studio(backgroundScope)
        studio.open(song())
        runCurrent()

        val d = studio.draft.value!!
        assertEquals(RingtonePhase.READY, d.phase)
        assertEquals(180_000L, d.durationMs)
        assertEquals("Dracula", d.name)
        assertEquals(RingtoneStudio.BAR_COUNT, d.bars.size)
        // The first clip is thirty seconds long and sits on the loud stretch (1:00 - 1:30).
        assertEquals(30_000L, d.range.lengthMs)
        assertTrue(d.range.startMs in 58_000L..61_000L, "start was ${d.range.startMs}")
    }

    @Test
    fun aSongThatIsTooLongIsRefusedBeforeAnythingIsFetched() = testScope.runTest {
        val studio = studio(backgroundScope)
        studio.open(song(duration = 3 * 3600))
        runCurrent()

        assertEquals(RingtonePhase.FAILED, studio.draft.value!!.phase)
        assertEquals(TurkishStrings.ringtoneErrorTooLong, studio.draft.value!!.error)
    }

    @Test
    fun withoutAConnectionTheErrorSaysToDownloadFirst() = testScope.runTest {
        online = false
        maker.failLoad = java.io.IOException("No stream")
        val studio = studio(backgroundScope)
        studio.open(song())
        runCurrent()

        assertEquals(RingtonePhase.FAILED, studio.draft.value!!.phase)
        assertEquals(TurkishStrings.ringtoneErrorOffline, studio.draft.value!!.error)

        // Online again: another reason gives the general message, and trying again loads.
        online = true
        studio.retry()
        runCurrent()
        assertEquals(TurkishStrings.ringtoneErrorGeneric, studio.draft.value!!.error)
        maker.failLoad = null
        studio.retry()
        runCurrent()
        assertEquals(RingtonePhase.READY, studio.draft.value!!.phase)
    }

    @Test
    fun editingKeepsTheClipInsideItsLimits() = testScope.runTest {
        val studio = studio(backgroundScope)
        studio.open(song())
        runCurrent()

        studio.setStart(10_000L)
        val stretched = studio.draft.value!!.range
        assertEquals(10_000L, stretched.startMs)
        assertEquals(RingtoneMath.MAX_LENGTH_MS, stretched.lengthMs)

        studio.setEnd(11_000L)
        assertEquals(RingtoneMath.MIN_LENGTH_MS, studio.draft.value!!.range.lengthMs)

        studio.moveClip(500_000L)
        assertEquals(180_000L, studio.draft.value!!.range.endMs)

        studio.setStart(60_000L)
        studio.nudgeStart(500L)
        assertEquals(60_500L, studio.draft.value!!.range.startMs)
        studio.nudgeEnd(-500L)
        assertEquals(99_500L, studio.draft.value!!.range.endMs)

        studio.setName("a".repeat(200))
        assertEquals(RingtoneStudio.MAX_NAME, studio.draft.value!!.name.length)

        // Asking for the best part again puts the loud stretch (1:00 - 1:30) inside the clip.
        studio.suggest()
        val best = studio.draft.value!!.range
        assertTrue(best.startMs <= 60_000L && best.endMs >= 90_000L, "range was $best")
    }

    @Test
    fun thePreviewPausesTheMusicAndFollowsEdits() = testScope.runTest {
        val studio = studio(backgroundScope)
        studio.open(song())
        runCurrent()
        player.resume()

        studio.togglePreview()
        assertTrue(studio.draft.value!!.previewing)
        assertFalse(player.isPlaying.value)
        assertEquals(1, maker.previews)

        // Moving the part while it plays changes what loops, without starting over.
        studio.setStart(100_000L)
        assertEquals(1, maker.previews)
        assertEquals(100_000L, maker.lastPreview!![0])
        studio.setFadeOut(false)
        assertEquals(false, maker.lastPreview!![3])

        studio.togglePreview()
        assertFalse(studio.draft.value!!.previewing)

        // Leaving the editor gives the song's file back and the music carries on.
        studio.close()
        assertEquals(listOf("s1"), maker.released)
        assertTrue(player.isPlaying.value)
        assertEquals(null, studio.draft.value)
    }

    @Test
    fun savingKeepsTheRingtoneInTheListAndSettingIt() = testScope.runTest {
        val studio = studio(backgroundScope)
        studio.open(song())
        runCurrent()
        studio.setName("  Zil  ")
        studio.setLouder(false)

        studio.save()
        runCurrent()

        val d = studio.draft.value!!
        assertEquals(RingtonePhase.SAVED, d.phase)
        assertEquals("Zil", maker.lastRequest!!.name)
        assertEquals("Tame Impala", maker.lastRequest!!.artist)
        assertFalse(maker.lastRequest!!.louder)
        assertEquals(listOf(d.made), studio.saved.value)

        studio.apply(RingtoneTarget.PHONE)
        studio.apply(RingtoneTarget.ALARM)
        runCurrent()
        assertEquals(setOf(RingtoneTarget.PHONE, RingtoneTarget.ALARM), studio.draft.value!!.applied)
        assertEquals(setOf(RingtoneTarget.PHONE, RingtoneTarget.ALARM), studio.inUse.value[d.made!!.uri])
        assertTrue(messages.contains(TurkishStrings.ringtonePhoneSet))
        assertTrue(messages.contains(TurkishStrings.ringtoneAlarmSet))

        studio.applyToContact()
        runCurrent()
        assertEquals("Ayşe", studio.draft.value!!.contactName)
        assertTrue(messages.contains(TurkishStrings.ringtoneContactSet("Ayşe")))

        // Back to editing makes a new one from the same song; both stay in the list.
        studio.editAgain()
        assertEquals(RingtonePhase.READY, studio.draft.value!!.phase)
        studio.save()
        runCurrent()
        assertEquals(2, studio.saved.value.size)
    }

    @Test
    fun aRefusedSettingIsSaidSoAndNotMarkedDone() = testScope.runTest {
        val studio = studio(backgroundScope)
        studio.open(song())
        runCurrent()
        studio.save()
        runCurrent()

        maker.applyResult = ApplyResult.NeedsPermission
        studio.apply(RingtoneTarget.NOTIFICATION)
        runCurrent()
        assertTrue(studio.draft.value!!.applied.isEmpty())
        assertEquals(TurkishStrings.ringtoneNeedsPermission, messages.last())

        maker.applyResult = ApplyResult.Failed("kept the old sound")
        studio.apply(RingtoneTarget.NOTIFICATION)
        runCurrent()
        assertTrue(studio.draft.value!!.applied.isEmpty())
        assertEquals(TurkishStrings.ringtoneNotAccepted, messages.last())

        maker.contactResult = ContactResult.NeedsPermission
        studio.applyToContact()
        runCurrent()
        assertEquals(TurkishStrings.ringtoneContactsPermission, messages.last())

        maker.contactResult = ContactResult.Cancelled
        val before = messages.size
        studio.applyToContact()
        runCurrent()
        assertEquals(before, messages.size)
        assertEquals(null, studio.draft.value!!.contactName)
    }

    @Test
    fun theListDropsWhatThePhoneNoLongerHasAndDeletesOnRequest() = testScope.runTest {
        val studio = studio(backgroundScope)
        studio.open(song())
        runCurrent()
        studio.save()
        runCurrent()
        studio.editAgain()
        studio.save()
        runCurrent()
        val (newer, older) = studio.saved.value
        assertNotNull(newer)

        // The older one was deleted from the phone by something else.
        maker.existing -= older.uri
        studio.refreshList()
        runCurrent()
        assertEquals(listOf(newer), studio.saved.value)

        studio.togglePlay(newer)
        assertEquals(newer.uri, studio.playingSaved.value)
        studio.togglePlay(newer)
        assertEquals(null, studio.playingSaved.value)

        studio.delete(newer)
        runCurrent()
        assertTrue(studio.saved.value.isEmpty())
        assertEquals(TurkishStrings.ringtoneDeleted, messages.last())

        // The phone kept the file (it was not ours to delete any more): said so, and the list is still cleaned.
        studio.editAgain()
        studio.save()
        runCurrent()
        maker.deleteResult = false
        studio.delete(studio.saved.value.single())
        runCurrent()
        assertTrue(studio.saved.value.isEmpty())
        assertEquals(TurkishStrings.ringtoneDeleteKept, messages.last())
    }
}

package com.qmusic.ui.games

import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.games.GameAction
import com.qmusic.ui.chat.games.GameConfig
import com.qmusic.ui.chat.games.GameKind
import com.qmusic.ui.chat.games.GameStatus
import com.qmusic.ui.chat.games.KeyMark
import com.qmusic.ui.chat.games.Sha256
import com.qmusic.ui.chat.games.TurkishDictApi
import com.qmusic.ui.chat.games.TurkishText
import com.qmusic.ui.chat.games.TurkishWords
import com.qmusic.ui.chat.games.WordChainEngine
import com.qmusic.ui.chat.games.WordDuelEngine
import com.qmusic.ui.chat.games.XoxEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ALI = ChatUser("uid_ali", "Ali", "ali")
private val VELI = ChatUser("uid_veli", "Veli", "veli")

class ChatGamesTest {

    @Test
    fun turkishCasingPreservesDottedAndDotlessI() {
        assertEquals("İSTANBUL", TurkishText.trUpper("istanbul"))
        assertEquals("istanbul", TurkishText.trLower("İSTANBUL"))
        assertEquals("IĞDIR", TurkishText.trUpper("ığdır"))
        assertEquals("ığdır", TurkishText.trLower("IĞDIR"))
        assertEquals("ÇİÇEK", TurkishText.trNormalize("  çiçek! "))
        assertEquals("ŞEKER", TurkishText.trUpper("şeker"))
    }

    @Test
    fun sha256MatchesNistVectorAndCommitVerify() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.sha256Hex("abc"),
        )
        val salt = "0123456789abcdef"
        val hash = Sha256.commitHash("g1", 1, ALI.uid, "ROCK", salt)
        assertTrue(Sha256.verifyCommit(hash, "g1", 1, ALI.uid, "ROCK", salt))
        assertFalse(Sha256.verifyCommit(hash, "g1", 1, ALI.uid, "PAPER", salt))
    }

    @Test
    fun wordDuelTwoPassDuplicateLetterEvaluationIsExact() {
        // Target ARABA (3 A's at 0,2,4), Guess ABABA -> A(0)=E, B(1)=A (only 1 B at idx 3 which is exact!), A(2)=E, B(3)=E, A(4)=E
        assertEquals(
            listOf(KeyMark.EXACT, KeyMark.ABSENT, KeyMark.EXACT, KeyMark.EXACT, KeyMark.EXACT),
            WordDuelEngine.evaluateGuess("ARABA", "ABABA"),
        )
        // Target KAKAO (K at 0,2; A at 1,3; O at 4), Guess KKKAA -> K0=E, K1=P (matches K2), K2=E? Wait: K2 is EXACT!
        // Pass 1: K0=E, K2=E (so 0 K's left!), A3=E (1 A left at idx 1). Pass 2: K1=A, A4=P (matches A1).
        assertEquals(
            listOf(KeyMark.EXACT, KeyMark.ABSENT, KeyMark.EXACT, KeyMark.EXACT, KeyMark.PRESENT),
            WordDuelEngine.evaluateGuess("KAKAO", "KKKAA"),
        )
        // Turkish İ vs I distinction
        assertEquals(
            listOf(KeyMark.EXACT, KeyMark.EXACT, KeyMark.EXACT, KeyMark.EXACT, KeyMark.EXACT),
            WordDuelEngine.evaluateGuess("ÇİÇEK", "çiçek"),
        )
    }

    @Test
    fun wordDuelRaceResolvesWinnerByFewestGuesses() {
        val g0 = WordDuelEngine.createInitial("g_wd", "ali_veli", ALI, VELI, solo = false, GameConfig(GameKind.WORD_DUEL, mode = "race", maxRounds = 1), 1000L)
        val g1 = WordDuelEngine.join(g0, VELI, 2000L)!!
        val target = TurkishWords.pickTarget5("g_wd:1")
        // Ali misses once, then solves in 2
        val wrong = TurkishWords.wordleTargets.first { it != target }
        val g2 = WordDuelEngine.applyAction(g1, ALI.uid, GameAction("guess", wrong), 3000L)!!
        val g3 = WordDuelEngine.applyAction(g2, ALI.uid, GameAction("guess", target), 4000L)!!
        assertEquals(GameStatus.PLAYING, g3.status) // waiting for Veli
        // Veli solves in 1
        val g4 = WordDuelEngine.applyAction(g3, VELI.uid, GameAction("guess", target), 5000L)!!
        assertEquals(GameStatus.FINISHED, g4.status)
        assertEquals(VELI.uid, g4.winnerUid)
    }

    @Test
    fun tdkDictApiEncodesTurkishAndParsesResponses() {
        assertEquals("a%C4%9Fa%C3%A7", TurkishDictApi.encodeUtf8Query("ağaç"))
        assertEquals("%C5%9Feker", TurkishDictApi.encodeUtf8Query("şeker"))
        val ok = TurkishDictApi.parseTdkResponse("""[{"madde":"kitap","anlamlarListe":[{"anlam":"Basılı yaprakların bütünü"}]}]""")
        assertNotNull(ok)
        assertTrue(ok.valid)
        assertEquals("Basılı yaprakların bütünü", ok.meaning)
        val err = TurkishDictApi.parseTdkResponse("""{"error":"Sonuç bulunamadı"}""")
        assertNotNull(err)
        assertFalse(err.valid)
    }

    @Test
    fun wordChainSoftGRuleDuplicateGuardAndTimeoutElimination() {
        assertEquals('A' to true, WordChainEngine.requiredStartLetter("DAĞ"))
        assertEquals('P' to false, WordChainEngine.requiredStartLetter("KİTAP"))

        val g0 = WordChainEngine.createInitial("g_wc", "ali_veli", ALI, VELI, solo = false, GameConfig(GameKind.WORD_CHAIN, turnSeconds = 15, extra = "K"), 1000L)
        var g = WordChainEngine.join(g0, VELI, 2000L)!!
        assertEquals(GameStatus.PLAYING, g.status)
        assertEquals(VELI.uid, g.turn)

        // Start letter 'K' -> find a valid word starting with 'K'
        val req = WordChainEngine.currentRequirement(g).first
        assertEquals('K', req)
        val valid = TurkishWords.allChainWords.first { it.first() == req && it.length >= 4 }
        assertNull(WordChainEngine.validateWord(g, valid, english = false))
        g = WordChainEngine.applyAction(g, VELI.uid, GameAction("word", "$valid|Test anlam"), 4000L)!!
        assertEquals(ALI.uid, g.turn)
        // Repeating the same word is rejected
        assertNotNull(WordChainEngine.validateWord(g, valid, english = false))

        // Three timeouts eliminate the player cleanly
        var now = 4000L
        var loops = 0
        while (g.status == GameStatus.PLAYING && loops < 10) {
            now = g.turnDeadlineAt + 100L
            // Keep timing out Ali; Veli responds or also times out
            if (g.turn == ALI.uid) {
                g = WordChainEngine.applyAction(g, VELI.uid, GameAction("timeout"), now)!!
            } else {
                // Veli plays valid word or times out; let's time out Veli once too, Ali loses 3 first
                g = WordChainEngine.applyAction(g, ALI.uid, GameAction("timeout"), now)!!
            }
            loops++
        }
        assertEquals(GameStatus.FINISHED, g.status)
        assertNotNull(g.winnerUid)
    }

    @Test
    fun xoxInfiniteModeRemovesOldestPieceOnFourthMove() {
        val g0 = XoxEngine.createInitial("g_xox", "ali_veli", ALI, VELI, solo = false, GameConfig(GameKind.XOX, mode = "infinite", maxRounds = 1), 1000L)
        var g = XoxEngine.join(g0, VELI, 2000L)!!
        // Ali (X): 0, 1, 8 (not a win); Veli (O): 3, 4, 6 (not a win)
        g = XoxEngine.applyAction(g, ALI.uid, GameAction("move", "0"), 3000L)!!
        g = XoxEngine.applyAction(g, VELI.uid, GameAction("move", "3"), 4000L)!!
        g = XoxEngine.applyAction(g, ALI.uid, GameAction("move", "1"), 5000L)!!
        g = XoxEngine.applyAction(g, VELI.uid, GameAction("move", "4"), 6000L)!!
        g = XoxEngine.applyAction(g, ALI.uid, GameAction("move", "8"), 7000L)!!
        g = XoxEngine.applyAction(g, VELI.uid, GameAction("move", "6"), 8000L)!!
        assertEquals(GameStatus.PLAYING, g.status)
        // Ali's 4th move at cell 2 removes oldest cell 0! So Ali has [1, 8, 2] -> NOT winning [0,1,2]!
        g = XoxEngine.applyAction(g, ALI.uid, GameAction("move", "2"), 9000L)!!
        assertEquals(GameStatus.PLAYING, g.status)
        // Now cell 0 is free again! Veli plays cell 5 -> [3, 4, 6] drops 3 -> [4, 6, 5]; then Veli plays 0 next or Ali wins on [2,5,8]
        val afterVeli = XoxEngine.applyAction(g, VELI.uid, GameAction("move", "0"), 10000L)!!
        assertEquals(GameStatus.PLAYING, afterVeli.status)
        // Ali plays cell 5 -> [1, 8, 2] drops 1 -> [8, 2, 5] = RIGHT COLUMN WIN [2, 5, 8]!
        val win = XoxEngine.applyAction(afterVeli, ALI.uid, GameAction("move", "5"), 11000L)!!
        assertEquals(GameStatus.FINISHED, win.status)
        assertEquals(ALI.uid, win.winnerUid)
    }
}

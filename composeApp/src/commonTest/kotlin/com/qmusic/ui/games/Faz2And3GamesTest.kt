package com.qmusic.ui.games

import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.games.BattleshipEngine
import com.qmusic.ui.chat.games.Connect4Engine
import com.qmusic.ui.chat.games.GameAction
import com.qmusic.ui.chat.games.GameConfig
import com.qmusic.ui.chat.games.GameJson
import com.qmusic.ui.chat.games.GameJson.arr
import com.qmusic.ui.chat.games.GameJson.str
import com.qmusic.ui.chat.games.GameJson.strList
import com.qmusic.ui.chat.games.GameKind
import com.qmusic.ui.chat.games.GameRegistry
import com.qmusic.ui.chat.games.GameStatus
import com.qmusic.ui.chat.games.HangmanEngine
import com.qmusic.ui.chat.games.MastermindEngine
import com.qmusic.ui.chat.games.NameCityEngine
import com.qmusic.ui.chat.games.RpsEngine
import com.qmusic.ui.chat.games.RpsMove
import com.qmusic.ui.chat.games.Sha256
import com.qmusic.ui.chat.games.SongQuizEngine
import com.qmusic.ui.chat.games.TabooCard
import com.qmusic.ui.chat.games.TabooDeck
import com.qmusic.ui.chat.games.TabooEngine
import com.qmusic.ui.chat.games.TriviaBank
import com.qmusic.ui.chat.games.TriviaEngine
import com.qmusic.ui.chat.games.GameSlashResult
import com.qmusic.ui.chat.games.parseGameCommand
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Faz2And3GamesTest {
    private val ali = ChatUser("u1", "Ali Yılmaz", "ali")
    private val veli = ChatUser("u2", "Veli Kaya", "veli")
    private val now = 1_764_000_000_000L

    @Test
    fun registryHasAllTwelveGamesAndSlashCommandsWork() {
        assertEquals(12, GameRegistry.implemented.size)
        GameKind.entries.forEach { kind ->
            assertNotNull(GameRegistry.of(kind), "Missing engine for $kind")
        }
        assertEquals(GameSlashResult.OpenPicker, parseGameCommand("/oyna"))
        assertEquals(GameSlashResult.OpenPicker, parseGameCommand("/play"))
        assertEquals(GameSlashResult.QuickStart(GameKind.WORD_DUEL), parseGameCommand("/kelime"))
        assertEquals(GameSlashResult.QuickStart(GameKind.XOX), parseGameCommand("/oyna xox"))
        assertEquals(GameSlashResult.QuickStart(GameKind.SONG_QUIZ), parseGameCommand("/şarkı"))
        assertEquals(GameSlashResult.QuickStart(GameKind.TABOO), parseGameCommand("/tabu"))
        assertEquals(GameSlashResult.QuickStart(GameKind.TRIVIA), parseGameCommand("/bilgi"))
        assertNull(parseGameCommand("normal mesaj"))
    }

    @Test
    fun hangmanCustomWordFlowWorks() {
        val cfg = GameConfig(GameKind.HANGMAN, mode = "custom", maxRounds = 1)
        var s = HangmanEngine.createInitial("hm1", "u1_u2", ali, veli, solo = false, config = cfg, nowMs = now)
        assertEquals(GameStatus.PLAYING, s.status)
        s = HangmanEngine.applyAction(s, ali.uid, GameAction("set_word", "KEDİ|Hayvan"), now + 1000)!!
        assertEquals(veli.uid, s.turn)
        for (ch in listOf("K", "E", "D", "İ")) {
            s = HangmanEngine.applyAction(s, veli.uid, GameAction("guess", ch), now + 2000)!!
        }
        assertEquals(GameStatus.FINISHED, s.status)
        assertEquals(veli.uid, s.winnerUid)
    }

    @Test
    fun connect4VerticalWinDetected() {
        var s = Connect4Engine.createInitial("c4", "u1_u2", ali, veli, solo = false, config = GameConfig(GameKind.CONNECT4), nowMs = now)
        // Ali drops in col 0 four times; Veli drops in col 1 three times
        for (turn in 0 until 3) {
            s = Connect4Engine.applyAction(s, ali.uid, GameAction("drop", "0"), now + turn * 100)!!
            s = Connect4Engine.applyAction(s, veli.uid, GameAction("drop", "1"), now + turn * 100 + 50)!!
        }
        s = Connect4Engine.applyAction(s, ali.uid, GameAction("drop", "0"), now + 1000)!!
        assertEquals(GameStatus.FINISHED, s.status)
        assertEquals(ali.uid, s.winnerUid)
    }

    @Test
    fun rpsCommitRevealDetectsTamperingAndAwardsWinner() {
        val cfg = GameConfig(GameKind.RPS, mode = "bo3", maxRounds = 3)
        var s = RpsEngine.createInitial("rps1", "u1_u2", ali, veli, solo = false, config = cfg, nowMs = now)
        val saltA = "11112222333344445555666677778888"
        val saltB = "aaaabbbbccccddddeeeeffff00001111"
        val hashA = Sha256.commitHash("rps1", 1, ali.uid, RpsMove.ROCK.wire, saltA)
        val hashB = Sha256.commitHash("rps1", 1, veli.uid, RpsMove.SCISSORS.wire, saltB)

        s = RpsEngine.applyAction(s, ali.uid, GameAction("commit", clientHash = hashA), now + 100)!!
        s = RpsEngine.applyAction(s, veli.uid, GameAction("commit", clientHash = hashB), now + 200)!!

        // Tampered reveal must be rejected!
        assertNull(RpsEngine.applyAction(s, veli.uid, GameAction("reveal", "PAPER|$saltB"), now + 250))

        // Valid reveals
        s = RpsEngine.applyAction(s, ali.uid, GameAction("reveal", "ROCK|$saltA"), now + 300)!!
        s = RpsEngine.applyAction(s, veli.uid, GameAction("reveal", "SCISSORS|$saltB"), now + 400)!!
        assertEquals(1, s.players[ali.uid]?.score)
        assertEquals(0, s.players[veli.uid]?.score)
    }

    @Test
    fun nameCityOfficialScoringRules() {
        val scores = NameCityEngine.scoreRound(
            letter = "A",
            order = listOf("u1", "u2"),
            answers = mapOf(
                // cat 0: same ("Ali") -> 5 each
                // cat 1: unique ("Ankara" vs "Antalya") -> 10 each
                // cat 2: only u1 valid ("Aslan" vs "") -> 15 for u1, 0 for u2
                // cat 3: wrong letter ("Biber") -> 0, valid ("Ardıç") -> 15 for u2
                // cat 4: rejected u1 ("Ayna"), empty u2 -> 0 each
                "u1" to listOf("Ali", "Ankara", "Aslan", "Biber", "Ayna"),
                "u2" to listOf("ali", "Antalya", "", "Ardıç", ""),
            ),
            rejects = mapOf("u1:4" to listOf("u2")),
        )
        assertEquals(5 + 10 + 15 + 0 + 0, scores["u1"])
        assertEquals(5 + 10 + 0 + 15 + 0, scores["u2"])
    }

    @Test
    fun mastermindCodeValidationAndEvaluation() {
        assertFalse(MastermindEngine.isValidCode("0123"))
        assertFalse(MastermindEngine.isValidCode("1123"))
        assertTrue(MastermindEngine.isValidCode("4827"))
        assertEquals(2 to 1, MastermindEngine.evaluateCode("4827", "4871"))
        assertEquals(4 to 0, MastermindEngine.evaluateCode("4827", "4827"))

        // Solo mode flow
        var s = MastermindEngine.createInitial("mm1", "u1_u1", ali, null, solo = true, config = GameConfig(GameKind.MASTERMIND), nowMs = now)
        val secret = MastermindEngine.generateDeterministicCode("mm1")
        assertTrue(MastermindEngine.isValidCode(secret))
        s = MastermindEngine.applyAction(s, ali.uid, GameAction("guess", secret), now + 500)!!
        assertEquals(GameStatus.FINISHED, s.status)
        assertEquals(ali.uid, s.winnerUid)
    }

    @Test
    fun battleshipFleetValidationCommitAndSinkingWin() {
        val fleetA = BattleshipEngine.generateRandomFleet("seedA")
        val fleetB = BattleshipEngine.generateRandomFleet("seedB")
        assertTrue(BattleshipEngine.validateFleet(fleetA))
        assertTrue(BattleshipEngine.validateFleet(fleetB))

        var s = BattleshipEngine.createInitial("bs1", "u1_u2", ali, veli, solo = false, config = GameConfig(GameKind.BATTLESHIP), nowMs = now)
        val saltA = "1234567890abcdef1234567890abcdef"
        val saltB = "fedcba0987654321fedcba0987654321"
        val hashA = Sha256.commitHash("bs1", 1, ali.uid, fleetA, saltA)
        val hashB = Sha256.commitHash("bs1", 1, veli.uid, fleetB, saltB)

        s = BattleshipEngine.applyAction(s, ali.uid, GameAction("place", fleetA, hashA), now + 100)!!
        s = BattleshipEngine.applyAction(s, veli.uid, GameAction("place", fleetB, hashB), now + 200)!!
        assertEquals(ali.uid, s.turn)

        // Ali hits all 11 ship segments of Veli (hit keeps turn!)
        val shipIndices = fleetB.indices.filter { fleetB[it] == 'S' }
        assertEquals(11, shipIndices.size)
        for (idx in shipIndices) {
            s = BattleshipEngine.applyAction(s, ali.uid, GameAction("shoot", idx.toString()), now + 300 + idx)!!
        }
        assertEquals(GameStatus.FINISHED, s.status)
        assertEquals(ali.uid, s.winnerUid)
        assertTrue(Sha256.verifyCommit(s.commits[veli.uid]!!, "bs1", 1, veli.uid, fleetB, saltB))
    }

    @Test
    fun tabooDeckSizeAndForbiddenFilter() {
        assertTrue(TabooDeck.cards.size >= 145, "Expected >=145 taboo cards, got ${TabooDeck.cards.size}")
        val card = TabooCard("KAHVE", listOf("FİNCAN", "TÜRK", "SÜT", "KAFEİN", "FAL"))
        assertNotNull(TabooEngine.violatedWord("Sabah fincanla içilir", card))
        assertNotNull(TabooEngine.violatedWord("Sütlü sıcak içecek", card))
        assertNull(TabooEngine.violatedWord("Sabahları uyanmak için içilen koyu sıcak içecek", card))

        var s = TabooEngine.createInitial("tb1", "u1_u2", ali, veli, solo = false, config = GameConfig(GameKind.TABOO, maxRounds = 2, turnSeconds = 60), nowMs = now)
        val activeCard = TabooDeck.pick("tb1", 0)
        // Forbidden clue rejected
        assertNull(TabooEngine.applyAction(s, ali.uid, GameAction("clue", activeCard.forbidden.first()), now + 500))
        // Clean clue accepted
        s = TabooEngine.applyAction(s, ali.uid, GameAction("clue", "Günlük hayatta sık görürsün"), now + 600)!!
        // Correct guess scores +1 for both!
        s = TabooEngine.applyAction(s, veli.uid, GameAction("guess", activeCard.target), now + 700)!!
        assertEquals(1, s.players[ali.uid]?.score)
        assertEquals(1, s.players[veli.uid]?.score)
    }

    @Test
    fun triviaBankAndSongQuizSpeedScoring() {
        assertTrue(TriviaBank.all.size >= 150, "Expected >=150 trivia questions, got ${TriviaBank.all.size}")
        assertEquals(100, SongQuizEngine.speedPoints(1500))
        assertEquals(75, SongQuizEngine.speedPoints(4500))
        assertEquals(50, SongQuizEngine.speedPoints(9000))

        // Trivia solo 5 rounds
        var t = TriviaEngine.createInitial("tr1", "u1_u1", ali, null, solo = true, config = GameConfig(GameKind.TRIVIA, mode = "science", maxRounds = 5, turnSeconds = 15), nowMs = now)
        val st = GameJson.parseObject(t.stateJson)
        val qIds = st.arr("qIds").strList().mapNotNull { it.toIntOrNull() }
        // qIds stored as ints; verify answer flow on round 1
        assertEquals(GameStatus.PLAYING, t.status)
        t = TriviaEngine.applyAction(t, ali.uid, GameAction("answer", "1|2000"), now + 2000)!!
        assertEquals("reveal", GameJson.parseObject(t.stateJson).str("phase"))

        // SongQuiz with synthetic 1-round payload
        val extra = buildJsonObject {
            put("genre", "pop")
            put("questions", buildJsonArray {
                add(buildJsonObject {
                    put("songId", "abc123")
                    put("title", "Kuzu Kuzu")
                    put("artist", "Tarkan")
                    put("startMs", 30_000L)
                    put("correct", 2)
                    put("choices", buildJsonArray {
                        add(JsonPrimitive("A"))
                        add(JsonPrimitive("B"))
                        add(JsonPrimitive("Kuzu Kuzu — Tarkan"))
                        add(JsonPrimitive("D"))
                    })
                })
            })
        }.toString()
        var sq = SongQuizEngine.createInitial("sq1", "u1_u2", ali, veli, solo = false, config = GameConfig(GameKind.SONG_QUIZ, mode = "pop", maxRounds = 1, turnSeconds = 15, extra = extra), nowMs = now)
        sq = SongQuizEngine.applyAction(sq, ali.uid, GameAction("answer", "2|1800"), now + 1800)!!
        sq = SongQuizEngine.applyAction(sq, veli.uid, GameAction("answer", "0|2200"), now + 2200)!!
        assertEquals(GameStatus.FINISHED, sq.status)
        assertEquals(ali.uid, sq.winnerUid)
        assertEquals(100, sq.players[ali.uid]?.score)
        assertEquals(0, sq.players[veli.uid]?.score)
    }
}

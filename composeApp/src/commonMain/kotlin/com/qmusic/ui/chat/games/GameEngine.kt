package com.qmusic.ui.chat.games

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qmusic.ui.chat.ChatController
import com.qmusic.ui.chat.ChatStrings
import com.qmusic.ui.chat.ChatUser

/**
 * Common contract implemented by every /oyna chat game.
 * Pure Kotlin state transitions (testable in commonTest) + monochrome Compose arena UI.
 */
interface GameEngine {
    val kind: GameKind

    fun title(s: ChatStrings): String
    fun subtitle(s: ChatStrings): String
    fun rules(s: ChatStrings): String

    fun createInitial(
        gameId: String,
        chatId: String,
        creator: ChatUser,
        opponent: ChatUser?,
        solo: Boolean,
        config: GameConfig,
        nowMs: Long,
    ): GameSession

    /** Adds a joining player to a WAITING game (or returns null if cannot join). */
    fun join(session: GameSession, player: ChatUser, nowMs: Long): GameSession?

    /** Applies a validated player action and returns the next GameSession, or null to abort transaction. */
    fun applyAction(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession?

    @Composable
    fun ArenaContent(
        session: GameSession,
        me: ChatUser,
        other: ChatUser,
        controller: ChatController,
        s: ChatStrings,
        onAction: (GameAction) -> Unit,
        modifier: Modifier,
    )
}

/** Central registry of all 12 implemented /oyna chat games (Faz 1 + Faz 2 + Faz 3). */
object GameRegistry {
    val engines: Map<GameKind, GameEngine> = linkedMapOf(
        GameKind.WORD_DUEL to WordDuelEngine,
        GameKind.WORD_CHAIN to WordChainEngine,
        GameKind.XOX to XoxEngine,
        GameKind.HANGMAN to HangmanEngine,
        GameKind.CONNECT4 to Connect4Engine,
        GameKind.RPS to RpsEngine,
        GameKind.NAME_CITY to NameCityEngine,
        GameKind.MASTERMIND to MastermindEngine,
        GameKind.SONG_QUIZ to SongQuizEngine,
        GameKind.BATTLESHIP to BattleshipEngine,
        GameKind.TABOO to TabooEngine,
        GameKind.TRIVIA to TriviaEngine,
    )

    val implemented: List<GameKind> get() = engines.keys.toList()

    fun of(kind: GameKind): GameEngine? = engines[kind]

    fun defaultConfig(kind: GameKind): GameConfig = when (kind) {
        GameKind.WORD_DUEL -> GameConfig(kind, mode = "race", maxRounds = 1, turnSeconds = 0)
        GameKind.WORD_CHAIN -> GameConfig(kind, mode = "classic", maxRounds = 1, turnSeconds = 20)
        GameKind.XOX -> GameConfig(kind, mode = "infinite", maxRounds = 3, turnSeconds = 0)
        GameKind.HANGMAN -> GameConfig(kind, mode = "pool", maxRounds = 3, turnSeconds = 0, extra = "mixed")
        GameKind.CONNECT4 -> GameConfig(kind, mode = "classic", maxRounds = 1, turnSeconds = 0)
        GameKind.RPS -> GameConfig(kind, mode = "bo3", maxRounds = 3, turnSeconds = 0)
        GameKind.NAME_CITY -> GameConfig(kind, mode = "classic", maxRounds = 3, turnSeconds = 60)
        GameKind.MASTERMIND -> GameConfig(kind, mode = "duel", maxRounds = 1, turnSeconds = 0)
        GameKind.SONG_QUIZ -> GameConfig(kind, mode = "mixed", maxRounds = 5, turnSeconds = 15)
        GameKind.BATTLESHIP -> GameConfig(kind, mode = "fast8", maxRounds = 1, turnSeconds = 0)
        GameKind.TABOO -> GameConfig(kind, mode = "classic", maxRounds = 4, turnSeconds = 60)
        GameKind.TRIVIA -> GameConfig(kind, mode = "mixed", maxRounds = 5, turnSeconds = 15)
    }
}

/** Shared helpers reused by game engines for forfeit, cancellation, and player ordering. */
internal object GameRulesHelper {
    fun handleCommonActions(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession? {
        if (uid !in session.players) return null
        return when (action.type) {
            "cancel" -> {
                if (session.status != GameStatus.WAITING || session.createdBy != uid) return null
                session.copy(
                    status = GameStatus.CANCELLED,
                    turn = null,
                    summary = "Oyun iptal edildi",
                    updatedAt = nowMs,
                )
            }
            "forfeit" -> {
                if (session.status != GameStatus.PLAYING) return null
                val remaining = session.order.filter { it != uid && (session.players[it]?.lives ?: 1) > 0 }
                val winner = remaining.maxByOrNull { session.players[it]?.score ?: 0 }
                val loserName = session.players[uid]?.name?.substringBefore(' ').orEmpty()
                val winnerName = winner?.let { session.players[it]?.name?.substringBefore(' ') }.orEmpty()
                session.copy(
                    status = GameStatus.FINISHED,
                    turn = null,
                    winnerUid = winner,
                    summary = if (winnerName.isNotEmpty()) "$winnerName kazandı ($loserName çekildi)" else "Oyun sonlandı",
                    updatedAt = nowMs,
                )
            }
            else -> null
        }
    }

    fun nextInOrder(order: List<String>, currentUid: String?, alivePredicate: (String) -> Boolean = { true }): String? {
        val alive = order.filter(alivePredicate)
        if (alive.isEmpty()) return null
        val idx = currentUid?.let { alive.indexOf(it) } ?: -1
        return alive[(idx + 1) % alive.size]
    }
}

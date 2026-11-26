package com.qmusic.ui.chat.games

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** All games available through /oyna (both 1-1 and group chats where supported). */
enum class GameKind(val wire: String, val groupReady: Boolean, val soloReady: Boolean, val category: GameCategory) {
    WORD_DUEL("word_duel", groupReady = true, soloReady = true, category = GameCategory.WORD),
    WORD_CHAIN("word_chain", groupReady = true, soloReady = false, category = GameCategory.WORD),
    XOX("xox", groupReady = false, soloReady = true, category = GameCategory.CLASSIC),
    HANGMAN("hangman", groupReady = true, soloReady = true, category = GameCategory.WORD),
    CONNECT4("connect4", groupReady = false, soloReady = false, category = GameCategory.CLASSIC),
    RPS("rps", groupReady = false, soloReady = false, category = GameCategory.CLASSIC),
    NAME_CITY("name_city", groupReady = true, soloReady = false, category = GameCategory.WORD),
    MASTERMIND("mastermind", groupReady = false, soloReady = true, category = GameCategory.CLASSIC),
    SONG_QUIZ("song_quiz", groupReady = true, soloReady = true, category = GameCategory.PARTY),
    BATTLESHIP("battleship", groupReady = false, soloReady = false, category = GameCategory.CLASSIC),
    TABOO("taboo", groupReady = true, soloReady = false, category = GameCategory.WORD),
    TRIVIA("trivia", groupReady = true, soloReady = true, category = GameCategory.PARTY);

    companion object {
        fun fromWire(wire: String?): GameKind? = entries.firstOrNull { it.wire == wire }
    }
}

enum class GameCategory { ALL, WORD, CLASSIC, PARTY }

enum class GameStatus(val wire: String) {
    WAITING("waiting"),
    PLAYING("playing"),
    FINISHED("finished"),
    CANCELLED("cancelled");

    companion object {
        fun fromWire(wire: String?): GameStatus = entries.firstOrNull { it.wire == wire } ?: CANCELLED
    }
}

/** Lightweight reference stored inside a chat message (full live state lives under games/{chatId}/{id}). */
data class ChatGameRef(
    val id: String,
    val kind: GameKind,
    val title: String,
)

data class GamePlayer(
    val uid: String,
    val name: String,
    val score: Int = 0,
    val joinedAt: Long = 0L,
    val lives: Int = 3,
    val ready: Boolean = false,
)

data class GameConfig(
    val kind: GameKind,
    val mode: String = "default",
    val maxRounds: Int = 1,
    val turnSeconds: Int = 0,
    val extra: String = "",
)

data class GameSession(
    val id: String,
    val chatId: String,
    val kind: GameKind,
    val status: GameStatus,
    val createdBy: String,
    val players: Map<String, GamePlayer>,
    val order: List<String>,
    val turn: String?,
    val round: Int = 1,
    val maxRounds: Int = 1,
    val turnSeconds: Int = 0,
    val turnDeadlineAt: Long = 0L,
    val version: Long = 0L,
    val stateJson: String = "{}",
    val commits: Map<String, String> = emptyMap(),
    val winnerUid: String? = null,
    val summary: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val expireAt: Long = 0L,
) {
    fun isExpired(nowMs: Long): Boolean = expireAt > 0L && nowMs > expireAt
    fun isActive(nowMs: Long): Boolean = (status == GameStatus.WAITING || status == GameStatus.PLAYING) && !isExpired(nowMs)
    fun opponentOf(uid: String): GamePlayer? = order.firstOrNull { it != uid }?.let { players[it] } ?: players.values.firstOrNull { it.uid != uid }
}

data class ChatGameStats(
    val wins: Map<String, Int> = emptyMap(),
    val totalPlayed: Int = 0,
)

data class GameAction(
    val type: String,
    val payload: String = "",
    val clientHash: String? = null,
)

const val GAME_TTL_MS: Long = 7L * 24L * 3600_000L
const val BOT_UID: String = "__nuvelo_bot__"

/** Manual JSON helpers (no @Serializable needed, so web/ Wasm compiles cleanly without plugin.serialization). */
internal object GameJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseObject(raw: String?): JsonObject =
        runCatching { if (raw.isNullOrBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(raw).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))

    fun parseArray(raw: String?): JsonArray =
        runCatching { if (raw.isNullOrBlank()) JsonArray(emptyList()) else json.parseToJsonElement(raw).jsonArray }
            .getOrDefault(JsonArray(emptyList()))

    fun JsonObject.str(key: String, default: String = ""): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: default

    fun JsonObject.int(key: String, default: Int = 0): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: default

    fun JsonObject.long(key: String, default: Long = 0L): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: default

    fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

    fun JsonObject.obj(key: String): JsonObject =
        (this[key] as? JsonObject) ?: JsonObject(emptyMap())

    fun JsonObject.arr(key: String): JsonArray =
        (this[key] as? JsonArray) ?: JsonArray(emptyList())

    fun JsonArray.strList(): List<String> = mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    fun JsonArray.intList(): List<Int> = mapNotNull { (it as? JsonPrimitive)?.intOrNull }
}

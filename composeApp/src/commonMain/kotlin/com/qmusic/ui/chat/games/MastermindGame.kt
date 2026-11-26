package com.qmusic.ui.chat.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.chat.ChatController
import com.qmusic.ui.chat.ChatStrings
import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.games.GameJson.arr
import com.qmusic.ui.chat.games.GameJson.int
import com.qmusic.ui.chat.games.GameJson.obj
import com.qmusic.ui.chat.games.GameJson.str
import com.qmusic.ui.theme.BorderHover
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val MM_MAX_GUESSES = 10

object MastermindEngine : GameEngine {
    override val kind: GameKind = GameKind.MASTERMIND

    override fun title(s: ChatStrings): String = if (s.english) "Number Crack (4-Digit)" else "Sayı Tahmin (4 Haneli)"
    override fun subtitle(s: ChatStrings): String =
        if (s.english) "Crack the unique 4-digit secret with + / - clues" else "Rakamları farklı 4 haneli sayıyı + / - ipuçlarıyla bul"

    override fun rules(s: ChatStrings): String = if (s.english) {
        "Each player picks a secret 4-digit number with all unique digits (cannot start with 0). Take turns guessing the opponent's number: +A means right digit in the right spot, -B means right digit in the wrong spot. First to +4 wins!"
    } else {
        "Her oyuncu rakamları birbirinden farklı, 0 ile başlamayan 4 haneli bir sayı belirler. Sırayla rakibin sayısını tahmin edin: +A doğru rakam ve doğru yer, -B doğru rakam ama yanlış yer demektir. İlk +4 yapan kazanır!"
    }

    internal fun isValidCode(code: String): Boolean {
        if (code.length != 4 || code[0] == '0') return false
        if (!code.all { it in '0'..'9' }) return false
        return code.toSet().size == 4
    }

    internal fun evaluateCode(secret: String, guess: String): Pair<Int, Int> {
        var exact = 0
        var misplaced = 0
        for (i in 0 until 4) {
            if (guess[i] == secret[i]) exact++
            else if (guess[i] in secret) misplaced++
        }
        return exact to misplaced
    }

    internal fun generateDeterministicCode(seed: String): String {
        val digits = ('0'..'9').toMutableList()
        val out = StringBuilder(4)
        for (pos in 0 until 4) {
            val candidates = if (pos == 0) digits.filter { it != '0' } else digits
            val pick = candidates[Sha256.deterministicIndex("$seed|d$pos", candidates.size)]
            out.append(pick)
            digits.remove(pick)
        }
        return out.toString()
    }

    override fun createInitial(
        gameId: String,
        chatId: String,
        creator: ChatUser,
        opponent: ChatUser?,
        solo: Boolean,
        config: GameConfig,
        nowMs: Long,
    ): GameSession {
        val players = linkedMapOf(
            creator.uid to GamePlayer(creator.uid, creator.name, score = 0, joinedAt = nowMs, ready = true),
        )
        val order = mutableListOf(creator.uid)
        if (solo) {
            val secret = generateDeterministicCode(gameId)
            return GameSession(
                id = gameId,
                chatId = chatId,
                kind = GameKind.MASTERMIND,
                status = GameStatus.PLAYING,
                createdBy = creator.uid,
                players = players,
                order = order,
                turn = creator.uid,
                round = 1,
                maxRounds = 1,
                version = 1L,
                stateJson = encodeState(
                    mode = "solo",
                    phase = "play",
                    secrets = mapOf(BOT_UID to secret),
                    guesses = emptyMap(),
                ),
                summary = "Tek kişilik · 4 haneli sayıyı çöz",
                createdAt = nowMs,
                updatedAt = nowMs,
                expireAt = nowMs + GAME_TTL_MS,
            )
        }
        val status = if (opponent != null && opponent.uid != creator.uid) {
            players[opponent.uid] = GamePlayer(opponent.uid, opponent.name, score = 0, joinedAt = nowMs, ready = true)
            order.add(opponent.uid)
            GameStatus.PLAYING
        } else {
            GameStatus.WAITING
        }
        return GameSession(
            id = gameId,
            chatId = chatId,
            kind = GameKind.MASTERMIND,
            status = status,
            createdBy = creator.uid,
            players = players,
            order = order,
            turn = null,
            round = 1,
            maxRounds = 1,
            version = 1L,
            stateJson = encodeState(mode = "duel", phase = "setup", secrets = emptyMap(), guesses = emptyMap()),
            summary = if (status == GameStatus.PLAYING) "Gizli 4 haneli sayını belirle" else "Oyuncu bekleniyor",
            createdAt = nowMs,
            updatedAt = nowMs,
            expireAt = nowMs + GAME_TTL_MS,
        )
    }

    override fun join(session: GameSession, player: ChatUser, nowMs: Long): GameSession? {
        if (session.status != GameStatus.WAITING || session.order.size >= 2) return null
        if (player.uid in session.players) return session
        val players = session.players + (player.uid to GamePlayer(player.uid, player.name, joinedAt = nowMs, ready = true))
        val order = session.order + player.uid
        return session.copy(
            status = GameStatus.PLAYING,
            players = players,
            order = order,
            summary = "İki oyuncu da gizli sayısını belirlesin",
            updatedAt = nowMs,
        )
    }

    override fun applyAction(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession? {
        GameRulesHelper.handleCommonActions(session, uid, action, nowMs)?.let { return it }
        if (session.status != GameStatus.PLAYING || uid !in session.players) return null

        val st = GameJson.parseObject(session.stateJson)
        val mode = st.str("mode", "duel")
        val phase = st.str("phase", "setup")
        val secrets = parseStringMap(st.obj("secrets")).toMutableMap()
        val guesses = parseGuesses(st.obj("guesses")).toMutableMap()

        when (action.type) {
            "set_secret" -> {
                if (phase != "setup" || uid in secrets) return null
                val code = action.payload.trim()
                if (!isValidCode(code)) return null
                secrets[uid] = code
                val hash = action.clientHash?.takeIf { it.length == 64 } ?: Sha256.sha256Hex("${session.id}|$uid|$code")
                val nextCommits = session.commits + (uid to hash)
                val allSet = session.order.all { it in secrets }
                val first = if (allSet) session.order.first() else null
                val firstName = first?.let { session.players[it]?.name?.substringBefore(' ') }.orEmpty()
                return session.copy(
                    turn = first,
                    commits = nextCommits,
                    stateJson = encodeState(mode, if (allSet) "play" else "setup", secrets, guesses),
                    summary = if (allSet) "Sıra $firstName · Tahmine başla" else "${session.players[uid]?.name?.substringBefore(' ')} sayısını kilitledi",
                    updatedAt = nowMs,
                )
            }
            "guess" -> {
                if (phase != "play") return null
                if (session.turn != null && session.turn != uid) return null
                val guess = action.payload.trim()
                if (!isValidCode(guess)) return null
                val targetKey = if (mode == "solo") BOT_UID else session.order.firstOrNull { it != uid } ?: return null
                val secret = secrets[targetKey] ?: return null
                val myList = guesses[uid].orEmpty().toMutableList()
                if (myList.size >= MM_MAX_GUESSES) return null

                val (exact, misplaced) = evaluateCode(secret, guess)
                myList.add(buildJsonObject {
                    put("g", guess)
                    put("e", exact)
                    put("m", misplaced)
                })
                guesses[uid] = myList

                if (exact == 4) {
                    val players = session.players.toMutableMap()
                    players[uid]?.let { players[uid] = it.copy(score = it.score + 1) }
                    val name = players[uid]?.name?.substringBefore(' ').orEmpty()
                    return session.copy(
                        status = GameStatus.FINISHED,
                        players = players,
                        turn = null,
                        winnerUid = uid,
                        stateJson = encodeState(mode, "done", secrets, guesses),
                        summary = "$name ${myList.size}. tahminde buldu ($secret)!",
                        updatedAt = nowMs,
                    )
                }

                val allOut = session.order.all { (guesses[it]?.size ?: 0) >= MM_MAX_GUESSES }
                if (allOut) {
                    return session.copy(
                        status = GameStatus.FINISHED,
                        turn = null,
                        winnerUid = null,
                        stateJson = encodeState(mode, "done", secrets, guesses),
                        summary = if (mode == "solo") "Bilemedin · Sayı: $secret" else "Kimse bulamadı · Berabere",
                        updatedAt = nowMs,
                    )
                }

                val nextTurn = if (mode == "solo") uid else GameRulesHelper.nextInOrder(session.order, uid)
                val nextName = nextTurn?.let { session.players[it]?.name?.substringBefore(' ') }.orEmpty()
                return session.copy(
                    turn = nextTurn,
                    stateJson = encodeState(mode, "play", secrets, guesses),
                    summary = if (mode == "solo") "$guess → +$exact -$misplaced" else "Sıra $nextName · Son: +$exact -$misplaced",
                    updatedAt = nowMs,
                )
            }
            else -> return null
        }
    }

    private fun parseStringMap(obj: JsonObject): Map<String, String> = buildMap {
        obj.forEach { (k, v) -> (v as? JsonPrimitive)?.content?.let { put(k, it) } }
    }

    private fun parseGuesses(obj: JsonObject): Map<String, List<JsonObject>> = buildMap {
        obj.forEach { (k, v) ->
            val arr = (v as? JsonArray) ?: return@forEach
            put(k, arr.mapNotNull { runCatching { it.jsonObject }.getOrNull() })
        }
    }

    private fun encodeState(
        mode: String,
        phase: String,
        secrets: Map<String, String>,
        guesses: Map<String, List<JsonObject>>,
    ): String = buildJsonObject {
        put("mode", mode)
        put("phase", phase)
        put("secrets", buildJsonObject { secrets.forEach { (k, v) -> put(k, v) } })
        put("guesses", buildJsonObject { guesses.forEach { (k, list) -> put(k, buildJsonArray { list.forEach { add(it) } }) } })
    }.toString()

    @Composable
    override fun ArenaContent(
        session: GameSession,
        me: ChatUser,
        other: ChatUser,
        controller: ChatController,
        s: ChatStrings,
        onAction: (GameAction) -> Unit,
        modifier: Modifier,
    ) {
        val st = remember(session.stateJson) { GameJson.parseObject(session.stateJson) }
        val mode = st.str("mode", "duel")
        val phase = st.str("phase", "setup")
        val secrets = remember(session.stateJson) { parseStringMap(st.obj("secrets")) }
        val guesses = remember(session.stateJson) { parseGuesses(st.obj("guesses")) }
        val tr = !s.english
        var draft by remember(session.id, phase, guesses[me.uid]?.size) { mutableStateOf("") }

        val mySet = me.uid in secrets
        val myTurn = session.status == GameStatus.PLAYING && (phase == "setup" && !mySet || phase == "play" && session.turn == me.uid)

        Column(
            modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (phase == "setup") {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (mySet) {
                        if (tr) "Sayın kilitlendi (${secrets[me.uid]}) · Rakip bekleniyor..." else "Code locked (${secrets[me.uid]}) · Waiting for opponent..."
                    } else {
                        if (tr) "Rakamları farklı 4 haneli gizli sayını belirle" else "Pick your 4-digit secret (unique digits, no leading 0)"
                    },
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(18.dp))
                DigitSlots(code = if (mySet) secrets[me.uid].orEmpty() else draft)
                Spacer(Modifier.weight(1f))
                if (!mySet) {
                    DigitKeypad(
                        draft = draft,
                        tr = tr,
                        onDigit = { d ->
                            if (draft.length < 4 && d !in draft && !(draft.isEmpty() && d == '0')) {
                                draft += d
                            }
                        },
                        onDelete = { if (draft.isNotEmpty()) draft = draft.dropLast(1) },
                        onSubmit = {
                            if (isValidCode(draft)) {
                                val salt = Sha256.randomSaltHex()
                                val hash = Sha256.commitHash(session.id, 1, me.uid, draft, salt)
                                onAction(GameAction("set_secret", payload = draft, clientHash = hash))
                                draft = ""
                            }
                        },
                    )
                }
                return@Column
            }

            // Play / Done view
            val myGuesses = guesses[me.uid].orEmpty()
            val rivalUid = session.order.firstOrNull { it != me.uid }
            val rivalGuesses = rivalUid?.let { guesses[it] }.orEmpty()

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (tr) "Tahminlerin (${myGuesses.size}/$MM_MAX_GUESSES)" else "Your guesses (${myGuesses.size}/$MM_MAX_GUESSES)",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (mode == "duel" && rivalUid != null) {
                    val rName = session.players[rivalUid]?.name?.substringBefore(' ').orEmpty()
                    val lastRival = rivalGuesses.lastOrNull()
                    Text(
                        if (lastRival != null) "$rName: +${lastRival.int("e")} -${lastRival.int("m")}" else "$rName: ${rivalGuesses.size}/$MM_MAX_GUESSES",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (myGuesses.isEmpty()) {
                    Text(
                        if (tr) "Henüz tahmin yok — rakamları farklı 4 haneli sayı gir." else "No guesses yet — enter 4 unique digits.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                myGuesses.forEachIndexed { idx, row ->
                    val g = row.str("g")
                    val e = row.int("e")
                    val m = row.int("m")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurface)
                            .border(1.dp, if (e == 4) Ink else BorderSubtle, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${idx + 1}.", color = TextMuted, fontSize = 12.sp, modifier = Modifier.width(24.dp))
                        Text(g.toCharArray().joinToString(" "), color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (e > 0) Ink else DarkSurfaceVariant)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text("+$e", color = if (e > 0) InkInverse else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(DarkSurfaceVariant)
                                .border(1.dp, if (m > 0) Ink else BorderSubtle, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text("-$m", color = if (m > 0) TextPrimary else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (session.status == GameStatus.PLAYING) {
                Spacer(Modifier.height(10.dp))
                DigitSlots(code = draft)
                Spacer(Modifier.height(10.dp))
                if (myTurn) {
                    DigitKeypad(
                        draft = draft,
                        tr = tr,
                        onDigit = { d ->
                            if (draft.length < 4 && d !in draft && !(draft.isEmpty() && d == '0')) {
                                draft += d
                            }
                        },
                        onDelete = { if (draft.isNotEmpty()) draft = draft.dropLast(1) },
                        onSubmit = {
                            if (isValidCode(draft)) {
                                onAction(GameAction("guess", payload = draft))
                                draft = ""
                            }
                        },
                    )
                } else {
                    Text(
                        if (tr) "Rakibin tahmin sırası..." else "Opponent's turn...",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DigitSlots(code: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until 4) {
            val ch = code.getOrNull(i)
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (ch != null) DarkSurfaceVariant else DarkSurface)
                    .border(1.5.dp, if (ch != null) Ink else BorderSubtle, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(ch?.toString() ?: "•", color = if (ch != null) TextPrimary else TextMuted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DigitKeypad(
    draft: String,
    tr: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onSubmit: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3", "4", "5"),
        listOf("6", "7", "8", "9", "0"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    val ch = key[0]
                    val disabled = ch in draft || (draft.isEmpty() && ch == '0') || draft.length >= 4
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (disabled) DarkSurface else DarkSurfaceVariant)
                            .border(1.dp, if (disabled) BorderSubtle else BorderHover, RoundedCornerShape(10.dp))
                            .clickable(enabled = !disabled) { onDigit(ch) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(key, color = if (disabled) TextMuted else TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurface)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    .clickable(enabled = draft.isNotEmpty(), onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (tr) "Sil" else "Del", color = if (draft.isNotEmpty()) TextPrimary else TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            val ready = MastermindEngine.isValidCode(draft)
            Box(
                modifier = Modifier
                    .weight(2f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (ready) Ink else DarkSurfaceVariant)
                    .clickable(enabled = ready, onClick = onSubmit),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (tr) "Gönder" else "Submit", color = if (ready) InkInverse else TextMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

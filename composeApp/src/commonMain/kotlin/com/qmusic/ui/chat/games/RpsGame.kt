package com.qmusic.ui.chat.games

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.chat.ChatController
import com.qmusic.ui.chat.ChatStrings
import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.games.GameJson.arr
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

enum class RpsMove(val wire: String) {
    ROCK("ROCK"),
    PAPER("PAPER"),
    SCISSORS("SCISSORS");

    fun beats(other: RpsMove): Boolean = when (this) {
        ROCK -> other == SCISSORS
        PAPER -> other == ROCK
        SCISSORS -> other == PAPER
    }

    fun label(tr: Boolean): String = when (this) {
        ROCK -> if (tr) "Taş" else "Rock"
        PAPER -> if (tr) "Kağıt" else "Paper"
        SCISSORS -> if (tr) "Makas" else "Scissors"
    }

    companion object {
        fun fromWire(raw: String?): RpsMove? = entries.firstOrNull { it.wire == raw }
    }
}

object RpsEngine : GameEngine {
    override val kind: GameKind = GameKind.RPS

    override fun title(s: ChatStrings): String = if (s.english) "Rock · Paper · Scissors" else "Taş · Kağıt · Makas"
    override fun subtitle(s: ChatStrings): String =
        if (s.english) "Best of 3 or 5 with anti-cheat Commit-Reveal" else "3'lü veya 5'li seri · hilesiz Commit-Reveal"

    override fun rules(s: ChatStrings): String = if (s.english) {
        "Both players lock their move simultaneously using SHA-256 Commit-Reveal so nobody can peek. Once both lock, choices open together. Rock beats Scissors, Scissors beats Paper, Paper beats Rock."
    } else {
        "İki oyuncu da seçimini SHA-256 Commit-Reveal ile aynı anda kilitler; kimse rakibin seçimini göremez. İkisi de kilitleyince hamleler aynı anda açılır. Taş makası, makas kağıdı, kağıt taşı yener."
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
        val maxRounds = if (config.maxRounds == 5 || config.mode == "bo5") 5 else 3
        val players = linkedMapOf(
            creator.uid to GamePlayer(creator.uid, creator.name, score = 0, joinedAt = nowMs, ready = true),
        )
        val order = mutableListOf(creator.uid)
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
            kind = GameKind.RPS,
            status = status,
            createdBy = creator.uid,
            players = players,
            order = order,
            turn = null,
            round = 1,
            maxRounds = maxRounds,
            version = 1L,
            stateJson = encodeState(
                mode = if (maxRounds == 5) "bo5" else "bo3",
                phase = "commit",
                reveals = emptyMap(),
                history = emptyList(),
            ),
            summary = if (status == GameStatus.PLAYING) "Tur 1 · Seçimini kilitle" else "Oyuncu bekleniyor",
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
            summary = "Tur 1 · İki oyuncu da seçimini kilitlesin",
            updatedAt = nowMs,
        )
    }

    override fun applyAction(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession? {
        GameRulesHelper.handleCommonActions(session, uid, action, nowMs)?.let { return it }
        if (session.status != GameStatus.PLAYING || uid !in session.players || session.order.size < 2) return null

        val st = GameJson.parseObject(session.stateJson)
        val mode = st.str("mode", "bo3")
        val phase = st.str("phase", "commit")
        val reveals = parseStringMap(st.obj("reveals"))
        val history = st.arr("history").mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        val targetWins = (session.maxRounds / 2) + 1

        when (action.type) {
            "commit" -> {
                if (phase != "commit") return null
                if (uid in session.commits) return null
                val hash = action.clientHash?.trim().orEmpty()
                if (hash.length != 64) return null
                val nextCommits = session.commits + (uid to hash)
                val bothCommitted = session.order.all { it in nextCommits }
                val nextPhase = if (bothCommitted) "reveal" else "commit"
                val who = session.players[uid]?.name?.substringBefore(' ').orEmpty()
                return session.copy(
                    commits = nextCommits,
                    stateJson = encodeState(mode, nextPhase, reveals, history),
                    summary = if (bothCommitted) "İki taraf kilitledi · Açılıyor..." else "$who seçimini kilitledi",
                    updatedAt = nowMs,
                )
            }
            "reveal" -> {
                if (phase != "reveal" || uid in reveals) return null
                val parts = action.payload.split('|', limit = 2)
                if (parts.size != 2) return null
                val move = RpsMove.fromWire(parts[0]) ?: return null
                val salt = parts[1]
                val expectedHash = session.commits[uid] ?: return null
                if (!Sha256.verifyCommit(expectedHash, session.id, session.round, uid, move.wire, salt)) {
                    return null
                }
                val nextReveals = reveals + (uid to move.wire)
                if (!session.order.all { it in nextReveals }) {
                    return session.copy(
                        stateJson = encodeState(mode, "reveal", nextReveals, history),
                        summary = "Hamleler açılıyor...",
                        updatedAt = nowMs,
                    )
                }

                // Both revealed: evaluate round!
                val p1 = session.order[0]
                val p2 = session.order[1]
                val m1 = RpsMove.fromWire(nextReveals[p1]) ?: return null
                val m2 = RpsMove.fromWire(nextReveals[p2]) ?: return null
                val roundWinner: String? = when {
                    m1 == m2 -> null
                    m1.beats(m2) -> p1
                    else -> p2
                }
                val players = session.players.toMutableMap()
                if (roundWinner != null) {
                    players[roundWinner]?.let { players[roundWinner] = it.copy(score = it.score + 1) }
                }
                val roundEntry = buildJsonObject {
                    put("r", session.round)
                    put("m1", m1.wire)
                    put("m2", m2.wire)
                    put("w", roundWinner ?: "")
                }
                val nextHistory = history + roundEntry
                val s1 = players[p1]?.score ?: 0
                val s2 = players[p2]?.score ?: 0
                val matchWinner = when {
                    s1 >= targetWins -> p1
                    s2 >= targetWins -> p2
                    else -> null
                }
                val sum = when {
                    matchWinner != null -> "${players[matchWinner]?.name?.substringBefore(' ')} kazandı ($s1-$s2)"
                    roundWinner != null -> "${players[roundWinner]?.name?.substringBefore(' ')} turu aldı ($s1-$s2)"
                    else -> "Tur berabere (${m1.label(true)} - ${m2.label(true)})"
                }
                return session.copy(
                    status = if (matchWinner != null) GameStatus.FINISHED else GameStatus.PLAYING,
                    players = players,
                    winnerUid = matchWinner,
                    stateJson = encodeState(mode, "round_over", nextReveals, nextHistory),
                    summary = sum,
                    updatedAt = nowMs,
                )
            }
            "next_round" -> {
                if (phase != "round_over") return null
                val nextRound = session.round + 1
                return session.copy(
                    round = nextRound,
                    commits = emptyMap(),
                    stateJson = encodeState(mode, "commit", emptyMap(), history),
                    summary = "Tur $nextRound · Seçimini kilitle",
                    updatedAt = nowMs,
                )
            }
            else -> return null
        }
    }

    private fun parseStringMap(obj: JsonObject): Map<String, String> = buildMap {
        obj.forEach { (k, v) ->
            (v as? JsonPrimitive)?.content?.let { put(k, it) }
        }
    }

    private fun encodeState(
        mode: String,
        phase: String,
        reveals: Map<String, String>,
        history: List<JsonObject>,
    ): String = buildJsonObject {
        put("mode", mode)
        put("phase", phase)
        put("reveals", buildJsonObject { reveals.forEach { (k, v) -> put(k, v) } })
        put("history", buildJsonArray { history.forEach { add(it) } })
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
        val phase = st.str("phase", "commit")
        val reveals = remember(session.stateJson) { parseStringMap(st.obj("reveals")) }
        val history = remember(session.stateJson) { st.arr("history").mapNotNull { runCatching { it.jsonObject }.getOrNull() } }
        val tr = !s.english
        val targetWins = (session.maxRounds / 2) + 1
        val saltKey = GameSaltStore.keyOf(session.id, session.round, me.uid)

        // Auto-reveal once both players committed
        LaunchedEffect(phase, session.round, session.commits.size, reveals.size) {
            if (phase == "reveal" && me.uid !in reveals) {
                val saved = GameSaltStore.get(saltKey)
                if (saved != null) {
                    onAction(GameAction("reveal", payload = saved))
                }
            }
            if (phase == "round_over") {
                GameSaltStore.remove(saltKey)
            }
        }

        val myCommitted = me.uid in session.commits
        val rivalUid = session.order.firstOrNull { it != me.uid }
        val rivalCommitted = rivalUid != null && rivalUid in session.commits
        val myMove = RpsMove.fromWire(reveals[me.uid])
        val rivalMove = RpsMove.fromWire(rivalUid?.let { reveals[it] })

        Column(
            modifier = modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Scoreboard
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (tr) "İlk $targetWins galibiyet kazanır" else "First to $targetWins wins",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    session.order.take(2).forEach { uid ->
                        val p = session.players[uid] ?: return@forEach
                        val locked = uid in session.commits && phase == "commit"
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(DarkSurface)
                                .border(1.dp, if (locked) Ink else BorderSubtle, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(if (uid == me.uid) s.you else p.name.substringBefore(' '), color = TextSecondary, fontSize = 12.sp)
                            Text(p.score.toString(), color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            if (phase == "commit") {
                                Text(
                                    if (locked) (if (tr) "Kilitlendi" else "Locked") else (if (tr) "Seçiyor..." else "Picking..."),
                                    color = if (locked) Ink else TextMuted,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }

            // Center duel display
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (phase == "round_over" && myMove != null && rivalMove != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RevealCard(s.you, myMove, tr, highlight = myMove.beats(rivalMove))
                        Text("VS", color = TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        RevealCard(rivalUid?.let { session.players[it]?.name?.substringBefore(' ') } ?: "?", rivalMove, tr, highlight = rivalMove.beats(myMove))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(session.summary, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    if (session.status == GameStatus.PLAYING) {
                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Ink)
                                .clickable { onAction(GameAction("next_round")) }
                                .padding(horizontal = 24.dp, vertical = 11.dp),
                        ) {
                            Text(if (tr) "Sonraki Tur" else "Next Round", color = InkInverse, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else if (phase == "reveal") {
                    Text(
                        if (tr) "İki oyuncu da kilitledi — SHA-256 doğrulaması yapılıyor..." else "Both locked — verifying SHA-256 commits...",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        when {
                            myCommitted && !rivalCommitted -> if (tr) "Seçimin kilitlendi! Rakip bekleniyor..." else "Locked! Waiting for opponent..."
                            else -> if (tr) "Hamleni seç ve kilitle" else "Choose and lock your move"
                        },
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Choice buttons — distinct Amber Rock / Sky Paper / Coral Scissors
            if (session.status == GameStatus.PLAYING && phase == "commit" && !myCommitted) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RpsMove.entries.forEach { move ->
                        val moveColor = rpsMoveColor(move)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .background(moveColor.copy(alpha = 0.13f))
                                .border(1.6.dp, moveColor.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                                .clickable {
                                    val salt = Sha256.randomSaltHex()
                                    val hash = Sha256.commitHash(session.id, session.round, me.uid, move.wire, salt)
                                    GameSaltStore.put(saltKey, "${move.wire}|$salt")
                                    onAction(GameAction("commit", clientHash = hash))
                                }
                                .padding(vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RpsIcon(move = move, tint = moveColor, modifier = Modifier.size(38.dp))
                            Text(move.label(tr), color = moveColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (history.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    history.takeLast(5).forEach { entry ->
                        val w = entry.str("w")
                        val mark = when (w) {
                            "" -> "="
                            me.uid -> "✓"
                            else -> "×"
                        }
                        val bg = when (w) {
                            me.uid -> GameColors.Emerald
                            "" -> GameColors.Amber.copy(alpha = 0.22f)
                            else -> GameColors.Crimson.copy(alpha = 0.22f)
                        }
                        val fg = when (w) {
                            me.uid -> GameColors.onAccent(GameColors.Emerald)
                            "" -> GameColors.Amber
                            else -> GameColors.Crimson
                        }
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(bg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(mark, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private fun rpsMoveColor(move: RpsMove): androidx.compose.ui.graphics.Color = when (move) {
    RpsMove.ROCK -> GameColors.Amber
    RpsMove.PAPER -> GameColors.Sky
    RpsMove.SCISSORS -> GameColors.Coral
}

@Composable
private fun RevealCard(name: String, move: RpsMove, tr: Boolean, highlight: Boolean) {
    val mc = rpsMoveColor(move)
    val bg = if (highlight) GameColors.Emerald else mc.copy(alpha = 0.12f)
    val fg = if (highlight) GameColors.onAccent(GameColors.Emerald) else mc
    Column(
        modifier = Modifier
            .width(128.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .border(1.8.dp, if (highlight) GameColors.Emerald else mc.copy(alpha = 0.50f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, color = if (highlight) fg.copy(alpha = 0.80f) else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        RpsIcon(move = move, tint = fg, modifier = Modifier.size(42.dp))
        Text(move.label(tr), color = if (highlight) fg else TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RpsIcon(move: RpsMove, tint: androidx.compose.ui.graphics.Color = Ink, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sw = 2.6.dp.toPx()
        val w = size.width
        val h = size.height
        when (move) {
            RpsMove.ROCK -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.14f, h * 0.22f),
                    size = Size(w * 0.72f, h * 0.58f),
                    cornerRadius = CornerRadius(w * 0.28f, w * 0.28f),
                    style = Stroke(sw),
                )
                drawLine(tint, Offset(w * 0.30f, h * 0.42f), Offset(w * 0.52f, h * 0.42f), sw, StrokeCap.Round)
            }
            RpsMove.PAPER -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.20f, h * 0.10f),
                    size = Size(w * 0.60f, h * 0.80f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                    style = Stroke(sw),
                )
                drawLine(tint, Offset(w * 0.34f, h * 0.36f), Offset(w * 0.66f, h * 0.36f), sw * 0.8f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, h * 0.54f), Offset(w * 0.66f, h * 0.54f), sw * 0.8f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, h * 0.72f), Offset(w * 0.54f, h * 0.72f), sw * 0.8f, StrokeCap.Round)
            }
            RpsMove.SCISSORS -> {
                drawLine(tint, Offset(w * 0.24f, h * 0.20f), Offset(w * 0.76f, h * 0.76f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.76f, h * 0.20f), Offset(w * 0.24f, h * 0.76f), sw, StrokeCap.Round)
                drawCircle(tint, radius = w * 0.11f, center = Offset(w * 0.28f, h * 0.80f), style = Stroke(sw))
                drawCircle(tint, radius = w * 0.11f, center = Offset(w * 0.72f, h * 0.80f), style = Stroke(sw))
            }
        }
    }
}

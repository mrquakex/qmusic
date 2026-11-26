package com.qmusic.ui.chat.games

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.chat.ChatController
import com.qmusic.ui.chat.ChatStrings
import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.games.GameJson.arr
import com.qmusic.ui.chat.games.GameJson.bool
import com.qmusic.ui.chat.games.GameJson.intList
import com.qmusic.ui.chat.games.GameJson.str
import com.qmusic.ui.motion.pressScale
import com.qmusic.ui.theme.BorderHover
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * XOX (Tic-Tac-Toe) with two modes:
 * - "infinite" (default): each player keeps at most 3 pieces on the 3x3 board. Placing a 4th piece removes their
 *   oldest piece (FIFO), highlighted beforehand at 35% alpha with a dashed border — NEVER draws!
 * - "classic": standard 3x3 series (1/3/5 rounds).
 * Supports 1-1 chats and solo practice against a smart monochrome bot.
 */
object XoxEngine : GameEngine {
    override val kind: GameKind = GameKind.XOX

    private val WIN_LINES = listOf(
        listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
        listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
        listOf(0, 4, 8), listOf(2, 4, 6),
    )

    override fun title(s: ChatStrings): String = if (s.english) "Tic-Tac-Toe" else "XOX"
    override fun subtitle(s: ChatStrings): String =
        if (s.english) "Infinite 3-piece mode (no draws!) or Classic 3×3" else "Sonsuz 3'lü mod (beraberlik yok!) veya Klasik 3×3"

    override fun rules(s: ChatStrings): String = if (s.english) {
        "Get 3 in a row! In Infinite mode each player has at most 3 marks — placing a 4th removes your oldest mark (shown faded). Classic mode plays standard 3×3 rounds."
    } else {
        "Üçlüyü ilk kuran kazanır! Sonsuz modda tahtada en fazla 3 taşın olur; 4. taşını koyunca en eski taşın (soluk gösterilen) silinir — asla berabere bitmez."
    }

    private data class State(
        val mode: String,         // "infinite" | "classic"
        val xCells: List<Int>,    // ordered oldest -> newest
        val oCells: List<Int>,    // ordered oldest -> newest
        val winLine: List<Int>,
        val roundOver: Boolean,
        val roundWinner: String?, // uid or null (draw)
        val solo: Boolean,
    )

    private fun decode(raw: String): State {
        val o = GameJson.parseObject(raw)
        return State(
            mode = o.str("mode", "infinite"),
            xCells = o.arr("x").intList(),
            oCells = o.arr("o").intList(),
            winLine = o.arr("win").intList(),
            roundOver = o.bool("roundOver"),
            roundWinner = o.str("roundWinner").ifBlank { null },
            solo = o.bool("solo"),
        )
    }

    private fun encode(st: State): String = buildJsonObject {
        put("mode", st.mode)
        put("x", buildJsonArray { st.xCells.forEach { add(JsonPrimitive(it)) } })
        put("o", buildJsonArray { st.oCells.forEach { add(JsonPrimitive(it)) } })
        put("win", buildJsonArray { st.winLine.forEach { add(JsonPrimitive(it)) } })
        put("roundOver", st.roundOver)
        if (st.roundWinner != null) put("roundWinner", st.roundWinner)
        put("solo", st.solo)
    }.toString()

    private fun winningLineOf(cells: List<Int>): List<Int>? {
        val set = cells.toSet()
        return WIN_LINES.firstOrNull { line -> line.all { it in set } }
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
        val mode = if (config.mode == "classic") "classic" else "infinite"
        val rounds = config.maxRounds.coerceIn(1, 5)
        val players = buildMap {
            put(creator.uid, GamePlayer(creator.uid, creator.name, joinedAt = nowMs, ready = true))
            if (solo) put(BOT_UID, GamePlayer(BOT_UID, "Nuvelo Bot", joinedAt = nowMs, ready = true))
        }
        val order = if (solo) listOf(creator.uid, BOT_UID) else listOf(creator.uid)
        val st = State(mode, emptyList(), emptyList(), emptyList(), roundOver = false, roundWinner = null, solo = solo)
        val modeTag = if (mode == "infinite") "Sonsuz 3'lü" else "Klasik"
        return GameSession(
            id = gameId,
            chatId = chatId,
            kind = GameKind.XOX,
            status = if (solo) GameStatus.PLAYING else GameStatus.WAITING,
            createdBy = creator.uid,
            players = players,
            order = order,
            turn = creator.uid,
            round = 1,
            maxRounds = rounds,
            stateJson = encode(st),
            summary = if (solo) "XOX ($modeTag) · Sıra sende" else "${creator.name.substringBefore(' ')} XOX ($modeTag) başlattı",
            createdAt = nowMs,
            updatedAt = nowMs,
            expireAt = nowMs + GAME_TTL_MS,
        )
    }

    override fun join(session: GameSession, player: ChatUser, nowMs: Long): GameSession? {
        if (session.status != GameStatus.WAITING || player.uid in session.players || session.players.size >= 2) return null
        val updatedPlayers = session.players + (player.uid to GamePlayer(player.uid, player.name, joinedAt = nowMs, ready = true))
        val updatedOrder = session.order + player.uid
        val first = updatedOrder.first()
        return session.copy(
            status = GameStatus.PLAYING,
            players = updatedPlayers,
            order = updatedOrder,
            turn = first,
            summary = "Tur 1/${session.maxRounds} · Sıra ${updatedPlayers[first]?.name?.substringBefore(' ')}",
            updatedAt = nowMs,
        )
    }

    private fun placePiece(st: State, isX: Boolean, cell: Int): Pair<List<Int>, List<Int>>? {
        if (cell !in 0..8) return null
        if (cell in st.xCells || cell in st.oCells) return null
        val mine = if (isX) st.xCells else st.oCells
        val nextMine = if (st.mode == "infinite" && mine.size >= 3) mine.drop(1) + cell else mine + cell
        return if (isX) nextMine to st.oCells else st.xCells to nextMine
    }

    private fun pickBotMove(st: State): Int? {
        val occupied = (st.xCells + st.oCells).toSet()
        val free = (0..8).filter { it !in occupied }
        if (free.isEmpty()) return null
        // 1. Win immediately
        for (c in free) {
            val (_, nextO) = placePiece(st, isX = false, c) ?: continue
            if (winningLineOf(nextO) != null) return c
        }
        // 2. Block player win
        for (c in free) {
            val (nextX, _) = placePiece(st, isX = true, c) ?: continue
            if (winningLineOf(nextX) != null) return c
        }
        // 3. Center, corners, edges
        return listOf(4, 0, 2, 6, 8, 1, 3, 5, 7).firstOrNull { it in free }
    }

    override fun applyAction(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession? {
        GameRulesHelper.handleCommonActions(session, uid, action, nowMs)?.let { return it }
        if (session.status != GameStatus.PLAYING || uid !in session.players) return null
        val st = decode(session.stateJson)

        if (action.type == "next_round") {
            if (!st.roundOver || session.round >= session.maxRounds) return null
            val nextRound = session.round + 1
            val starter = session.order[(nextRound - 1) % session.order.size]
            val reset = st.copy(xCells = emptyList(), oCells = emptyList(), winLine = emptyList(), roundOver = false, roundWinner = null)
            var nextSession = session.copy(
                round = nextRound,
                turn = starter,
                stateJson = encode(reset),
                summary = "Tur $nextRound/${session.maxRounds} · Sıra ${session.players[starter]?.name?.substringBefore(' ')}",
                updatedAt = nowMs,
            )
            if (st.solo && starter == BOT_UID) {
                val botCell = pickBotMove(reset)
                if (botCell != null) nextSession = applySingleMove(nextSession, reset, BOT_UID, botCell, nowMs) ?: nextSession
            }
            return nextSession
        }

        if (action.type != "move" || st.roundOver || session.turn != uid) return null
        val cell = action.payload.toIntOrNull() ?: return null
        var next = applySingleMove(session, st, uid, cell, nowMs) ?: return null
        if (st.solo && next.status == GameStatus.PLAYING && next.turn == BOT_UID) {
            val afterPlayer = decode(next.stateJson)
            if (!afterPlayer.roundOver) {
                val botCell = pickBotMove(afterPlayer)
                if (botCell != null) next = applySingleMove(next, afterPlayer, BOT_UID, botCell, nowMs) ?: next
            }
        }
        return next
    }

    private fun applySingleMove(session: GameSession, st: State, uid: String, cell: Int, nowMs: Long): GameSession? {
        val isX = session.order.firstOrNull() == uid
        val (nx, no) = placePiece(st, isX, cell) ?: return null
        val win = winningLineOf(if (isX) nx else no)
        val draw = win == null && st.mode == "classic" && (nx.size + no.size >= 9)
        if (win != null || draw) {
            val roundWinner = if (win != null) uid else null
            val updatedPlayers = if (roundWinner != null) {
                val wp = session.players.getValue(roundWinner)
                session.players + (roundWinner to wp.copy(score = wp.score + 1))
            } else {
                session.players
            }
            val neededToClinch = (session.maxRounds / 2) + 1
            val clinched = roundWinner != null && (updatedPlayers[roundWinner]?.score ?: 0) >= neededToClinch
            val gameOver = clinched || session.round >= session.maxRounds
            val finalWinner = if (gameOver) {
                val max = updatedPlayers.values.maxOfOrNull { it.score } ?: 0
                val leaders = updatedPlayers.values.filter { it.score == max }
                if (max > 0 && leaders.size == 1) leaders.first().uid else null
            } else null
            val nextState = st.copy(
                xCells = nx,
                oCells = no,
                winLine = win.orEmpty(),
                roundOver = true,
                roundWinner = roundWinner,
            )
            val scoresText = session.order.mapNotNull { updatedPlayers[it]?.score }.joinToString("–")
            val summary = when {
                gameOver && finalWinner != null -> "${updatedPlayers[finalWinner]?.name?.substringBefore(' ')} kazandı ($scoresText)"
                gameOver -> "Berabere ($scoresText)"
                roundWinner != null -> "Tur ${session.round}: ${updatedPlayers[roundWinner]?.name?.substringBefore(' ')} ($scoresText)"
                else -> "Tur ${session.round} berabere ($scoresText)"
            }
            return session.copy(
                status = if (gameOver) GameStatus.FINISHED else GameStatus.PLAYING,
                players = updatedPlayers,
                turn = null,
                winnerUid = finalWinner,
                stateJson = encode(nextState),
                summary = summary,
                updatedAt = nowMs,
            )
        }
        val nextTurn = GameRulesHelper.nextInOrder(session.order, uid)
        val nextState = st.copy(xCells = nx, oCells = no)
        return session.copy(
            turn = nextTurn,
            stateJson = encode(nextState),
            summary = "Tur ${session.round}/${session.maxRounds} · Sıra ${session.players[nextTurn]?.name?.substringBefore(' ')}",
            updatedAt = nowMs,
        )
    }

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
        val en = s.english
        val st = remember(session.stateJson) { decode(session.stateJson) }
        val xUid = session.order.firstOrNull()
        val oUid = session.order.getOrNull(1)
        val myTurn = session.status == GameStatus.PLAYING && !st.roundOver && session.turn == me.uid

        // Which cell will vanish next in infinite mode when the active player places their 4th mark.
        val vanishingCell: Int? = if (st.mode == "infinite" && !st.roundOver && session.status == GameStatus.PLAYING) {
            when (session.turn) {
                xUid -> if (st.xCells.size >= 3) st.xCells.first() else null
                oUid -> if (st.oCells.size >= 3) st.oCells.first() else null
                else -> null
            }
        } else null

        val strikeProgress = remember(st.winLine) { Animatable(0f) }
        LaunchedEffect(st.winLine) {
            if (st.winLine.size == 3) {
                strikeProgress.snapTo(0f)
                strikeProgress.animateTo(1f, tween(320))
            }
        }

        Column(
            modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Mode & Score Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkSurfaceVariant)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerScoreBadge("X", session.players[xUid], me.uid, session.turn == xUid && !st.roundOver, en)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (st.mode == "infinite") (if (en) "Infinite 3" else "Sonsuz 3'lü") else (if (en) "Classic" else "Klasik"),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${session.players[xUid]?.score ?: 0} – ${session.players[oUid]?.score ?: 0}",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                PlayerScoreBadge("O", session.players[oUid], me.uid, session.turn == oUid && !st.roundOver, en)
            }

            Spacer(Modifier.height(14.dp))
            Text(
                when {
                    st.roundOver && st.roundWinner != null ->
                        if (st.roundWinner == me.uid) (if (en) "You won the round!" else "Turu kazandın!")
                        else "${session.players[st.roundWinner]?.name?.substringBefore(' ')} " + if (en) "won the round" else "turu kazandı"
                    st.roundOver -> if (en) "Round draw" else "Tur berabere"
                    myTurn -> if (en) "Your turn" else "Sıra sende"
                    else -> "${session.players[session.turn]?.name?.substringBefore(' ').orEmpty()} " + if (en) "is thinking…" else "düşünüyor…"
                },
                color = if (myTurn) TextPrimary else TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(18.dp))

            // 3x3 Board with Coral Red X, Sky Blue O, Amber vanishing warning, and glowing win strike
            val xColor = GameColors.Coral
            val oColor = GameColors.Sky
            val vanishColor = GameColors.Amber
            val winColor = if (st.roundWinner == xUid) xColor else if (st.roundWinner == oUid) oColor else GameColors.Gold
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (r in 0 until 3) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (c in 0 until 3) {
                                val cell = r * 3 + c
                                val mark = when {
                                    cell in st.xCells -> 'X'
                                    cell in st.oCells -> 'O'
                                    else -> null
                                }
                                val isVanishing = cell == vanishingCell
                                val inWin = cell in st.winLine
                                val markColor = if (mark == 'X') xColor else oColor
                                val cellBg = when {
                                    inWin -> winColor.copy(alpha = 0.22f)
                                    isVanishing -> vanishColor.copy(alpha = 0.12f)
                                    mark != null -> markColor.copy(alpha = 0.08f)
                                    else -> DarkSurfaceVariant
                                }
                                val cellBorder = when {
                                    inWin -> winColor
                                    isVanishing -> vanishColor
                                    mark != null -> markColor.copy(alpha = 0.35f)
                                    else -> BorderSubtle
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                        .pressScale(0.95f)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(cellBg)
                                        .border(
                                            width = if (inWin) 2.2.dp else if (isVanishing) 1.6.dp else 1.dp,
                                            color = cellBorder,
                                            shape = RoundedCornerShape(18.dp),
                                        )
                                        .clickable(enabled = myTurn && mark == null) {
                                            onAction(GameAction("move", payload = cell.toString()))
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (mark != null) {
                                        val drawTint = if (isVanishing) vanishColor else markColor
                                        Canvas(
                                            Modifier
                                                .fillMaxSize()
                                                .padding(20.dp)
                                                .alpha(if (isVanishing) 0.55f else 1f),
                                        ) {
                                            val stroke = Stroke(
                                                width = 5.5.dp.toPx(),
                                                cap = StrokeCap.Round,
                                                pathEffect = if (isVanishing) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null,
                                            )
                                            if (mark == 'X') {
                                                drawLine(drawTint, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = stroke.width, cap = StrokeCap.Round, pathEffect = stroke.pathEffect)
                                                drawLine(drawTint, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = stroke.width, cap = StrokeCap.Round, pathEffect = stroke.pathEffect)
                                            } else {
                                                drawCircle(drawTint, radius = size.minDimension / 2f, style = stroke)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (st.winLine.size == 3) {
                    Canvas(Modifier.fillMaxSize()) {
                        val p0 = cellCenter(st.winLine.first(), size.width, size.height)
                        val p2 = cellCenter(st.winLine.last(), size.width, size.height)
                        val end = Offset(
                            p0.x + (p2.x - p0.x) * strikeProgress.value,
                            p0.y + (p2.y - p0.y) * strikeProgress.value,
                        )
                        drawLine(winColor, p0, end, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            }

            if (vanishingCell != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (en) "Amber dashed mark disappears on next move" else "Turuncu kesikli taş bir sonraki hamlede kalkar",
                    color = GameColors.Amber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (st.roundOver && session.status == GameStatus.PLAYING && session.round < session.maxRounds) {
                Spacer(Modifier.height(18.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(xColor)
                        .clickable { onAction(GameAction("next_round")) }
                        .padding(horizontal = 24.dp, vertical = 11.dp),
                ) {
                    Text(
                        if (en) "Next round (${session.round + 1}/${session.maxRounds})" else "Sonraki tur (${session.round + 1}/${session.maxRounds})",
                        color = GameColors.onAccent(xColor),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    private fun cellCenter(cell: Int, w: Float, h: Float): Offset {
        val r = cell / 3
        val c = cell % 3
        return Offset((c + 0.5f) * (w / 3f), (r + 0.5f) * (h / 3f))
    }
}

@Composable
private fun PlayerScoreBadge(symbol: String, player: GamePlayer?, myUid: String, active: Boolean, en: Boolean) {
    val accent = if (symbol == "X") GameColors.Coral else GameColors.Sky
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) accent else accent.copy(alpha = 0.12f))
            .border(1.2.dp, if (active) accent else accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(symbol, color = if (active) GameColors.onAccent(accent) else accent, fontSize = 15.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.size(6.dp))
        Text(
            when {
                player == null -> "?"
                player.uid == myUid -> if (en) "You" else "Sen"
                else -> player.name.substringBefore(' ')
            },
            color = if (active) GameColors.onAccent(accent) else TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

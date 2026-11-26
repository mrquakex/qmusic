package com.qmusic.ui.chat.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.qmusic.ui.chat.games.GameJson.intList
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val C4_ROWS = 6
private const val C4_COLS = 7

object Connect4Engine : GameEngine {
    override val kind: GameKind = GameKind.CONNECT4

    override fun title(s: ChatStrings): String = if (s.english) "Connect Four" else "Dörtlü Sıra"
    override fun subtitle(s: ChatStrings): String =
        if (s.english) "7×6 board — align 4 discs to win" else "7×6 tahtada 4 taşı yan yana getir"

    override fun rules(s: ChatStrings): String = if (s.english) {
        "Players take turns dropping a disc into one of the 7 columns. The disc falls to the lowest open slot. First to align 4 discs horizontally, vertically, or diagonally wins!"
    } else {
        "Sırayla 7 sütundan birine taş bırak. Taş en alttaki boş göze düşer. Yatay, dikey veya çapraz 4 taşı ilk yan yana getiren kazanır!"
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
            kind = GameKind.CONNECT4,
            status = status,
            createdBy = creator.uid,
            players = players,
            order = order,
            turn = if (status == GameStatus.PLAYING) creator.uid else null,
            round = 1,
            maxRounds = 1,
            version = 1L,
            stateJson = encodeBoard(".".repeat(C4_ROWS * C4_COLS), -1, emptyList()),
            summary = if (status == GameStatus.PLAYING) "Sıra ${creator.name.substringBefore(' ')}" else "Oyuncu bekleniyor",
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
            turn = order.first(),
            summary = "Sıra ${players[order.first()]?.name?.substringBefore(' ')}",
            updatedAt = nowMs,
        )
    }

    override fun applyAction(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession? {
        GameRulesHelper.handleCommonActions(session, uid, action, nowMs)?.let { return it }
        if (session.status != GameStatus.PLAYING || session.turn != uid) return null
        if (action.type != "drop") return null

        val col = action.payload.trim().toIntOrNull() ?: return null
        if (col !in 0 until C4_COLS) return null

        val st = GameJson.parseObject(session.stateJson)
        val boardStr = st.str("board", ".".repeat(C4_ROWS * C4_COLS))
        if (boardStr.length != C4_ROWS * C4_COLS) return null

        val cells = boardStr.toCharArray()
        var targetRow = -1
        for (r in C4_ROWS - 1 downTo 0) {
            if (cells[r * C4_COLS + col] == '.') {
                targetRow = r
                break
            }
        }
        if (targetRow < 0) return null

        val mark = if (session.order.firstOrNull() == uid) '1' else '2'
        val lastIdx = targetRow * C4_COLS + col
        cells[lastIdx] = mark

        val winLine = findWinLine(cells, targetRow, col, mark)
        val boardFull = cells.none { it == '.' }

        return when {
            winLine.isNotEmpty() -> {
                val players = session.players.toMutableMap()
                players[uid]?.let { players[uid] = it.copy(score = it.score + 1) }
                val winnerName = players[uid]?.name?.substringBefore(' ').orEmpty()
                session.copy(
                    status = GameStatus.FINISHED,
                    players = players,
                    turn = null,
                    winnerUid = uid,
                    stateJson = encodeBoard(cells.concatToString(), lastIdx, winLine),
                    summary = "$winnerName 4'lü sırayı tamamladı!",
                    updatedAt = nowMs,
                )
            }
            boardFull -> {
                session.copy(
                    status = GameStatus.FINISHED,
                    turn = null,
                    winnerUid = null,
                    stateJson = encodeBoard(cells.concatToString(), lastIdx, emptyList()),
                    summary = "Tahta doldu · Berabere",
                    updatedAt = nowMs,
                )
            }
            else -> {
                val next = GameRulesHelper.nextInOrder(session.order, uid)
                val nextName = next?.let { session.players[it]?.name?.substringBefore(' ') }.orEmpty()
                session.copy(
                    turn = next,
                    stateJson = encodeBoard(cells.concatToString(), lastIdx, emptyList()),
                    summary = "Sıra $nextName",
                    updatedAt = nowMs,
                )
            }
        }
    }

    internal fun findWinLine(cells: CharArray, row: Int, col: Int, mark: Char): List<Int> {
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for ((dr, dc) in dirs) {
            val line = mutableListOf(row * C4_COLS + col)
            var r = row + dr
            var c = col + dc
            while (r in 0 until C4_ROWS && c in 0 until C4_COLS && cells[r * C4_COLS + c] == mark) {
                line.add(r * C4_COLS + c)
                r += dr
                c += dc
            }
            r = row - dr
            c = col - dc
            while (r in 0 until C4_ROWS && c in 0 until C4_COLS && cells[r * C4_COLS + c] == mark) {
                line.add(r * C4_COLS + c)
                r -= dr
                c -= dc
            }
            if (line.size >= 4) return line
        }
        return emptyList()
    }

    private fun encodeBoard(board: String, lastIdx: Int, winLine: List<Int>): String = buildJsonObject {
        put("board", board)
        put("last", lastIdx)
        put("win", buildJsonArray { winLine.forEach { add(JsonPrimitive(it)) } })
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
        val board = st.str("board", ".".repeat(C4_ROWS * C4_COLS))
        val lastIdx = st.int("last", -1)
        val winSet = remember(session.stateJson) { st.arr("win").intList().toSet() }
        val myTurn = session.status == GameStatus.PLAYING && session.turn == me.uid
        val tr = !s.english

        val p1Color = GameColors.Crimson
        val p2Color = GameColors.Gold
        val frameColor = GameColors.Connect4Frame
        val slotColor = GameColors.Connect4Slot
        val myDiscColor = if (session.order.firstOrNull() == me.uid) p1Color else p2Color

        Column(
            modifier = modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Player disc legend (Crimson Red vs Golden Yellow)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                session.order.take(2).forEachIndexed { idx, uid ->
                    val p = session.players[uid] ?: return@forEachIndexed
                    val active = session.turn == uid && session.status == GameStatus.PLAYING
                    val accent = if (idx == 0) p1Color else p2Color
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) accent.copy(alpha = 0.16f) else DarkSurface)
                            .border(if (active) 1.8.dp else 1.dp, if (active) accent else BorderSubtle, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DiscIcon(isPlayerOne = idx == 0, sizeDp = 18)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (uid == me.uid) s.you else p.name.substringBefore(' '),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                when {
                    session.status != GameStatus.PLAYING -> session.summary
                    myTurn -> if (tr) "Bir sütuna dokun ve taşını bırak" else "Tap any column to drop your disc"
                    else -> if (tr) "Rakibin hamlesi bekleniyor..." else "Waiting for opponent..."
                },
                color = if (myTurn) myDiscColor else TextSecondary,
                fontSize = 13.sp,
                fontWeight = if (myTurn) FontWeight.Bold else FontWeight.Normal,
            )
            Spacer(Modifier.height(14.dp))

            // Iconic Royal-Blue 7x6 Connect Four Board with Red & Yellow Discs
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(frameColor)
                    .border(2.dp, GameColors.Cobalt, RoundedCornerShape(20.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until C4_COLS) {
                        val canDrop = myTurn && board.getOrNull(c) == '.'
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(24.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (canDrop) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable(enabled = canDrop) { onAction(GameAction("drop", c.toString())) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "▼",
                                color = if (canDrop) myDiscColor else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.20f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                            )
                        }
                    }
                }
                for (r in 0 until C4_ROWS) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in 0 until C4_COLS) {
                            val idx = r * C4_COLS + c
                            val ch = board.getOrNull(idx) ?: '.'
                            val isWin = idx in winSet
                            val isLast = idx == lastIdx
                            val canDrop = myTurn && board.getOrNull(c) == '.'
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(CircleShape)
                                    .background(slotColor)
                                    .border(
                                        width = if (isWin) 2.6.dp else if (isLast) 1.8.dp else 1.dp,
                                        color = when {
                                            isWin -> androidx.compose.ui.graphics.Color.White
                                            isLast -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f)
                                            else -> androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f)
                                        },
                                        shape = CircleShape,
                                    )
                                    .clickable(enabled = canDrop) { onAction(GameAction("drop", c.toString())) }
                                    .padding(3.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                when (ch) {
                                    '1' -> Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(CircleShape)
                                            .background(p1Color)
                                            .border(2.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.28f), CircleShape),
                                    )
                                    '2' -> Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(CircleShape)
                                            .background(p2Color)
                                            .border(2.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f), CircleShape),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscIcon(isPlayerOne: Boolean, sizeDp: Int) {
    val c = if (isPlayerOne) GameColors.Crimson else GameColors.Gold
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(c)
            .border(1.5.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.45f), CircleShape),
    )
}

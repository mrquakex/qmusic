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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.chat.ChatController
import com.qmusic.ui.chat.ChatStrings
import com.qmusic.ui.chat.ChatUser
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val BS_SIZE = 8
private val BS_SHIPS = listOf(4, 3, 2, 2) // 11 total ship cells

object BattleshipEngine : GameEngine {
    override val kind: GameKind = GameKind.BATTLESHIP

    override fun title(s: ChatStrings): String = if (s.english) "Battleship 8×8" else "Amiral Battı (8×8)"
    override fun subtitle(s: ChatStrings): String =
        if (s.english) "Fast 8×8 fleet duel with SHA-256 board seal" else "Hızlı 8×8 filo savaşı · SHA-256 doğrulamalı"

    override fun rules(s: ChatStrings): String = if (s.english) {
        "Place 4 ships (lengths 4, 3, 2, 2) on your 8×8 sea grid and seal your fleet with SHA-256. Take turns firing at enemy coordinates — a hit gives another shot! Sink all 4 ships (11 cells) to win."
    } else {
        "8×8 deniz tahtana 4 gemi (4, 3, 2 ve 2 karelik) yerleştir ve SHA-256 ile kilitle. Sırayla rakip karelere atış yap — isabette sıra sende kalır! 4 gemiyi (11 kare) ilk batıran kazanır."
    }

    internal fun validateFleet(board: String): Boolean {
        if (board.length != BS_SIZE * BS_SIZE) return false
        val expectedCells = BS_SHIPS.sum()
        if (board.count { it == 'S' } != expectedCells) return false
        return board.all { it == '.' || it == 'S' }
    }

    internal fun generateRandomFleet(seed: String): String {
        val cells = CharArray(BS_SIZE * BS_SIZE) { '.' }
        for ((shipIdx, len) in BS_SHIPS.withIndex()) {
            var placed = false
            for (attempt in 0 until 80) {
                val horiz = Sha256.deterministicIndex("$seed|s$shipIdx|a$attempt|h", 2) == 0
                val r = Sha256.deterministicIndex("$seed|s$shipIdx|a$attempt|r", if (horiz) BS_SIZE else BS_SIZE - len + 1)
                val c = Sha256.deterministicIndex("$seed|s$shipIdx|a$attempt|c", if (horiz) BS_SIZE - len + 1 else BS_SIZE)
                if (canPlace(cells, r, c, len, horiz)) {
                    for (k in 0 until len) {
                        val rr = if (horiz) r else r + k
                        val cc = if (horiz) c + k else c
                        cells[rr * BS_SIZE + cc] = 'S'
                    }
                    placed = true
                    break
                }
            }
            if (!placed) {
                // Deterministic fallback rows 0, 2, 4, 6
                val row = shipIdx * 2
                for (k in 0 until len) cells[row * BS_SIZE + k] = 'S'
            }
        }
        return cells.concatToString()
    }

    internal fun canPlace(cells: CharArray, r: Int, c: Int, len: Int, horizontal: Boolean): Boolean {
        for (k in 0 until len) {
            val rr = if (horizontal) r else r + k
            val cc = if (horizontal) c + k else c
            if (rr !in 0 until BS_SIZE || cc !in 0 until BS_SIZE) return false
            if (cells[rr * BS_SIZE + cc] != '.') return false
        }
        return true
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
            kind = GameKind.BATTLESHIP,
            status = status,
            createdBy = creator.uid,
            players = players,
            order = order,
            turn = null,
            round = 1,
            maxRounds = 1,
            version = 1L,
            stateJson = encodeState("place", emptyMap(), emptyMap(), emptyMap()),
            summary = if (status == GameStatus.PLAYING) "Filonu yerleştir (4 gemi)" else "Oyuncu bekleniyor",
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
            summary = "İki oyuncu da filosunu yerleştirsin",
            updatedAt = nowMs,
        )
    }

    override fun applyAction(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession? {
        GameRulesHelper.handleCommonActions(session, uid, action, nowMs)?.let { return it }
        if (uid !in session.players || session.order.size < 2) return null

        val st = GameJson.parseObject(session.stateJson)
        val phase = st.str("phase", "place")
        val boards = parseStringMap(st.obj("boards")).toMutableMap()
        val shots = parseStringMap(st.obj("shots")).toMutableMap()
        val salts = parseStringMap(st.obj("salts")).toMutableMap()

        when (action.type) {
            "place" -> {
                if (session.status != GameStatus.PLAYING || phase != "place" || uid in boards) return null
                val board = action.payload.trim()
                if (!validateFleet(board)) return null
                val hash = action.clientHash?.takeIf { it.length == 64 } ?: Sha256.sha256Hex("${session.id}|$uid|$board")
                boards[uid] = board
                shots[uid] = ".".repeat(BS_SIZE * BS_SIZE)
                val nextCommits = session.commits + (uid to hash)
                val allPlaced = session.order.all { it in boards }
                val first = if (allPlaced) session.order.first() else null
                val firstName = first?.let { session.players[it]?.name?.substringBefore(' ') }.orEmpty()
                return session.copy(
                    turn = first,
                    commits = nextCommits,
                    stateJson = encodeState(if (allPlaced) "battle" else "place", boards, shots, salts),
                    summary = if (allPlaced) "Savaş başladı · Sıra $firstName" else "${session.players[uid]?.name?.substringBefore(' ')} filosunu kilitledi",
                    updatedAt = nowMs,
                )
            }
            "shoot" -> {
                if (session.status != GameStatus.PLAYING || phase != "battle" || session.turn != uid) return null
                val idx = action.payload.trim().toIntOrNull() ?: return null
                if (idx !in 0 until BS_SIZE * BS_SIZE) return null
                val rival = session.order.firstOrNull { it != uid } ?: return null
                val rivalBoard = boards[rival] ?: return null
                val myShots = (shots[uid] ?: ".".repeat(BS_SIZE * BS_SIZE)).toCharArray()
                if (myShots[idx] != '.') return null

                val hit = rivalBoard[idx] == 'S'
                myShots[idx] = if (hit) 'H' else 'M'
                shots[uid] = myShots.concatToString()

                val totalHits = myShots.count { it == 'H' }
                val players = session.players.toMutableMap()
                players[uid]?.let { players[uid] = it.copy(score = totalHits) }

                if (totalHits >= BS_SHIPS.sum()) {
                    val winnerName = players[uid]?.name?.substringBefore(' ').orEmpty()
                    return session.copy(
                        status = GameStatus.FINISHED,
                        players = players,
                        turn = null,
                        winnerUid = uid,
                        stateJson = encodeState("done", boards, shots, salts),
                        summary = "$winnerName tüm filoyu batırdı!",
                        updatedAt = nowMs,
                    )
                }

                val nextTurn = if (hit) uid else rival
                val shooterName = players[uid]?.name?.substringBefore(' ').orEmpty()
                val nextName = players[nextTurn]?.name?.substringBefore(' ').orEmpty()
                return session.copy(
                    players = players,
                    turn = nextTurn,
                    stateJson = encodeState("battle", boards, shots, salts),
                    summary = if (hit) "$shooterName isabet! ($totalHits/${BS_SHIPS.sum()})" else "Karavana · Sıra $nextName",
                    updatedAt = nowMs,
                )
            }
            "reveal_salt" -> {
                if (session.status != GameStatus.FINISHED || uid in salts) return null
                val salt = action.payload.trim().take(64)
                if (salt.isEmpty()) return null
                salts[uid] = salt
                return session.copy(
                    stateJson = encodeState(phase, boards, shots, salts),
                    updatedAt = nowMs,
                )
            }
            else -> return null
        }
    }

    private fun parseStringMap(obj: JsonObject): Map<String, String> = buildMap {
        obj.forEach { (k, v) -> (v as? JsonPrimitive)?.content?.let { put(k, it) } }
    }

    private fun encodeState(
        phase: String,
        boards: Map<String, String>,
        shots: Map<String, String>,
        salts: Map<String, String>,
    ): String = buildJsonObject {
        put("phase", phase)
        put("boards", buildJsonObject { boards.forEach { (k, v) -> put(k, v) } })
        put("shots", buildJsonObject { shots.forEach { (k, v) -> put(k, v) } })
        put("salts", buildJsonObject { salts.forEach { (k, v) -> put(k, v) } })
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
        val phase = st.str("phase", "place")
        val boards = remember(session.stateJson) { parseStringMap(st.obj("boards")) }
        val shots = remember(session.stateJson) { parseStringMap(st.obj("shots")) }
        val salts = remember(session.stateJson) { parseStringMap(st.obj("salts")) }
        val tr = !s.english
        val saltKey = GameSaltStore.keyOf(session.id, 1, me.uid)

        // Reveal salt after finish so both clients can verify SHA-256 board integrity
        LaunchedEffect(session.status, salts.size) {
            if (session.status == GameStatus.FINISHED && me.uid !in salts) {
                GameSaltStore.get(saltKey)?.let { saved ->
                    onAction(GameAction("reveal_salt", saved))
                    GameSaltStore.remove(saltKey)
                }
            }
        }

        Column(
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (phase == "place") {
                val alreadyLocked = me.uid in boards
                if (alreadyLocked) {
                    Text(
                        if (tr) "Filon SHA-256 ile kilitlendi! Rakip bekleniyor..." else "Your fleet is sealed with SHA-256! Waiting for opponent...",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                    BattleshipGrid(
                        board = boards[me.uid].orEmpty(),
                        shots = null,
                        showShips = true,
                        interactive = false,
                        onTap = {},
                    )
                    return@Column
                }

                val draftCells = remember(session.id) { mutableStateListOf<Char>().apply { repeat(BS_SIZE * BS_SIZE) { add('.') } } }
                var shipIndex by remember(session.id) { mutableStateOf(0) }
                var horizontal by remember(session.id) { mutableStateOf(true) }
                val allPlaced = shipIndex >= BS_SHIPS.size

                Text(
                    if (allPlaced) {
                        if (tr) "Tüm gemiler yerleşti — kilitlemeye hazır" else "All ships placed — ready to lock"
                    } else {
                        if (tr) "Yerleştir: ${BS_SHIPS[shipIndex]} karelik gemi (${shipIndex + 1}/${BS_SHIPS.size})" else "Place ship of length ${BS_SHIPS[shipIndex]} (${shipIndex + 1}/${BS_SHIPS.size})"
                    },
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallChip(if (horizontal) (if (tr) "Yön: Yatay" else "Horizontal") else (if (tr) "Yön: Dikey" else "Vertical")) {
                        horizontal = !horizontal
                    }
                    SmallChip(if (tr) "Rastgele" else "Random") {
                        val rnd = generateRandomFleet("${session.id}|${me.uid}|${controller.serverNow()}")
                        for (i in 0 until BS_SIZE * BS_SIZE) draftCells[i] = rnd[i]
                        shipIndex = BS_SHIPS.size
                    }
                    SmallChip(if (tr) "Temizle" else "Clear") {
                        for (i in 0 until BS_SIZE * BS_SIZE) draftCells[i] = '.'
                        shipIndex = 0
                    }
                }

                BattleshipGrid(
                    board = draftCells.joinToString(""),
                    shots = null,
                    showShips = true,
                    interactive = !allPlaced,
                    onTap = { idx ->
                        if (shipIndex < BS_SHIPS.size) {
                            val r = idx / BS_SIZE
                            val c = idx % BS_SIZE
                            val len = BS_SHIPS[shipIndex]
                            val arr = draftCells.toCharArray()
                            if (canPlace(arr, r, c, len, horizontal)) {
                                for (k in 0 until len) {
                                    val rr = if (horizontal) r else r + k
                                    val cc = if (horizontal) c + k else c
                                    draftCells[rr * BS_SIZE + cc] = 'S'
                                }
                                shipIndex++
                            }
                        }
                    },
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (allPlaced) Ink else DarkSurfaceVariant)
                        .clickable(enabled = allPlaced) {
                            val boardStr = draftCells.joinToString("")
                            val salt = Sha256.randomSaltHex()
                            val hash = Sha256.commitHash(session.id, 1, me.uid, boardStr, salt)
                            GameSaltStore.put(saltKey, salt)
                            onAction(GameAction("place", payload = boardStr, clientHash = hash))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (tr) "Filoyu Kilitle" else "Lock Fleet", color = if (allPlaced) InkInverse else TextMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                return@Column
            }

            // Battle / Finished view
            val rivalUid = session.order.firstOrNull { it != me.uid }
            val myHits = shots[me.uid]?.count { it == 'H' } ?: 0
            val rivalHits = rivalUid?.let { shots[it]?.count { c -> c == 'H' } } ?: 0
            val myTurn = session.status == GameStatus.PLAYING && session.turn == me.uid
            var viewEnemy by remember { mutableStateOf(true) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (viewEnemy) Ink else DarkSurface)
                        .border(1.dp, if (viewEnemy) Ink else BorderSubtle, RoundedCornerShape(10.dp))
                        .clickable { viewEnemy = true }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (if (tr) "Rakip Denizi" else "Enemy Sea") + " ($myHits/${BS_SHIPS.sum()})",
                        color = if (viewEnemy) InkInverse else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (!viewEnemy) Ink else DarkSurface)
                        .border(1.dp, if (!viewEnemy) Ink else BorderSubtle, RoundedCornerShape(10.dp))
                        .clickable { viewEnemy = false }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (if (tr) "Benim Filom" else "My Fleet") + " ($rivalHits/${BS_SHIPS.sum()})",
                        color = if (!viewEnemy) InkInverse else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Text(
                when {
                    session.status == GameStatus.FINISHED -> session.summary
                    myTurn -> if (tr) "Sıra sende — rakip denizine ateş et!" else "Your turn — tap enemy sea to fire!"
                    else -> if (tr) "Rakibin atışı bekleniyor..." else "Waiting for enemy shot..."
                },
                color = if (myTurn) TextPrimary else TextSecondary,
                fontSize = 13.sp,
                fontWeight = if (myTurn) FontWeight.SemiBold else FontWeight.Normal,
            )

            if (viewEnemy) {
                BattleshipGrid(
                    board = rivalUid?.let { boards[it] }.orEmpty(),
                    shots = shots[me.uid],
                    showShips = session.status == GameStatus.FINISHED,
                    interactive = myTurn,
                    onTap = { idx -> onAction(GameAction("shoot", idx.toString())) },
                )
            } else {
                BattleshipGrid(
                    board = boards[me.uid].orEmpty(),
                    shots = rivalUid?.let { shots[it] },
                    showShips = true,
                    interactive = false,
                    onTap = {},
                )
            }

            if (session.status == GameStatus.FINISHED && rivalUid != null) {
                val rivalSalt = salts[rivalUid]
                val rivalBoard = boards[rivalUid]
                val rivalCommit = session.commits[rivalUid]
                if (rivalSalt != null && rivalBoard != null && rivalCommit != null) {
                    val ok = Sha256.verifyCommit(rivalCommit, session.id, 1, rivalUid, rivalBoard, rivalSalt)
                    Text(
                        if (ok) (if (tr) "SHA-256 filo doğrulaması: Hilesiz ✓" else "SHA-256 board verification: Valid ✓")
                        else (if (tr) "SHA-256 uyuşmazlığı!" else "SHA-256 mismatch!"),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceVariant)
            .border(1.dp, BorderHover, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BattleshipGrid(
    board: String,
    shots: String?,
    showShips: Boolean,
    interactive: Boolean,
    onTap: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .border(1.dp, BorderHover, RoundedCornerShape(14.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (r in 0 until BS_SIZE) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until BS_SIZE) {
                    val idx = r * BS_SIZE + c
                    val hasShip = showShips && board.getOrNull(idx) == 'S'
                    val shot = shots?.getOrNull(idx) ?: '.'
                    val bg = when {
                        shot == 'H' -> Ink
                        hasShip -> DarkSurfaceVariant
                        else -> DarkSurface
                    }
                    val borderC = when {
                        shot == 'H' || hasShip -> Ink
                        else -> BorderSubtle
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(5.dp))
                            .background(bg)
                            .border(if (hasShip || shot == 'H') 1.5.dp else 1.dp, borderC, RoundedCornerShape(5.dp))
                            .clickable(enabled = interactive && shot == '.') { onTap(idx) },
                        contentAlignment = Alignment.Center,
                    ) {
                        when (shot) {
                            'H' -> Text("×", color = InkInverse, fontSize = 14.sp, fontWeight = FontWeight.Black)
                            'M' -> Text("·", color = TextMuted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            else -> if (hasShip) Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(Ink))
                        }
                    }
                }
            }
        }
    }
}

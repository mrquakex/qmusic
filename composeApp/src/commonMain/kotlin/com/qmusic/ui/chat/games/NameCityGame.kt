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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.chat.ChatController
import com.qmusic.ui.chat.ChatStrings
import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.games.GameJson.arr
import com.qmusic.ui.chat.games.GameJson.obj
import com.qmusic.ui.chat.games.GameJson.str
import com.qmusic.ui.chat.games.GameJson.strList
import com.qmusic.ui.theme.BorderHover
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val NC_CATEGORIES_TR = listOf("İsim", "Şehir", "Hayvan", "Bitki", "Eşya")
private val NC_CATEGORIES_EN = listOf("Name", "City", "Animal", "Plant", "Object")

object NameCityEngine : GameEngine {
    override val kind: GameKind = GameKind.NAME_CITY

    override fun title(s: ChatStrings): String = if (s.english) "Name · City · Animal" else "İsim · Şehir · Hayvan"
    override fun subtitle(s: ChatStrings): String =
        if (s.english) "5 categories in 60s · mutual scoring" else "5 kategori · 60 sn · 0 / 5 / 10 / 15 puan"

    override fun rules(s: ChatStrings): String = if (s.english) {
        "Each round draws a Turkish letter. Fill in Name, City, Animal, Plant, and Object starting with that letter before time runs out. Then review answers together: 15 pts if only you answered a category, 10 pts for a unique valid answer, 5 pts if duplicated, 0 if rejected or wrong letter."
    } else {
        "Her turda 26 oynanabilir harften biri çekilir. Süre dolmadan o harfle başlayan İsim, Şehir, Hayvan, Bitki ve Eşya yazıp kilitle. Ardından cevaplar açılır ve birlikte onaylanır: kategoride tek doğru cevap 15, benzersiz doğru 10, aynı cevap 5, geçersiz 0 puan."
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
        val maxRounds = config.maxRounds.coerceIn(1, 5)
        val turnSec = config.turnSeconds.coerceIn(30, 120)
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
        val letter = pickLetter(gameId, 1, emptyList())
        return GameSession(
            id = gameId,
            chatId = chatId,
            kind = GameKind.NAME_CITY,
            status = status,
            createdBy = creator.uid,
            players = players,
            order = order,
            turn = null,
            round = 1,
            maxRounds = maxRounds,
            turnSeconds = turnSec,
            turnDeadlineAt = if (status == GameStatus.PLAYING) nowMs + turnSec * 1000L else 0L,
            version = 1L,
            stateJson = encodeState(
                letter = letter,
                usedLetters = listOf(letter),
                phase = "write",
                answers = emptyMap(),
                rejects = emptyMap(),
                approvals = emptyList(),
                roundPoints = emptyMap(),
            ),
            summary = if (status == GameStatus.PLAYING) "Tur 1/$maxRounds · Harf: $letter" else "Oyuncu bekleniyor",
            createdAt = nowMs,
            updatedAt = nowMs,
            expireAt = nowMs + GAME_TTL_MS,
        )
    }

    override fun join(session: GameSession, player: ChatUser, nowMs: Long): GameSession? {
        if (session.status != GameStatus.WAITING) return null
        if (player.uid in session.players) return session
        val players = session.players + (player.uid to GamePlayer(player.uid, player.name, joinedAt = nowMs, ready = true))
        val order = session.order + player.uid
        val st = GameJson.parseObject(session.stateJson)
        val letter = st.str("letter", "A")
        return session.copy(
            status = GameStatus.PLAYING,
            players = players,
            order = order,
            turnDeadlineAt = nowMs + session.turnSeconds.coerceAtLeast(30) * 1000L,
            summary = "Tur ${session.round}/${session.maxRounds} · Harf: $letter",
            updatedAt = nowMs,
        )
    }

    override fun applyAction(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession? {
        GameRulesHelper.handleCommonActions(session, uid, action, nowMs)?.let { return it }
        if (session.status != GameStatus.PLAYING || uid !in session.players) return null

        val st = GameJson.parseObject(session.stateJson)
        val letter = st.str("letter", "A")
        val usedLetters = st.arr("used").strList()
        val phase = st.str("phase", "write")
        val answers = parseAnswers(st.obj("answers")).toMutableMap()
        val rejects = parseRejects(st.obj("rejects")).toMutableMap()
        val approvals = st.arr("approvals").strList().toMutableList()
        val roundPoints = parseIntMap(st.obj("roundPoints"))

        when (action.type) {
            "submit" -> {
                if (phase != "write" && phase != "reveal") return null
                val raw = action.payload.split('|').take(5).map { it.trim().take(24) }
                val padded = List(5) { idx -> raw.getOrElse(idx) { "" } }
                answers[uid] = padded
                val allSubmitted = session.order.all { it in answers }
                val nextPhase = if (allSubmitted) "vote" else "write"
                return session.copy(
                    stateJson = encodeState(letter, usedLetters, nextPhase, answers, rejects, emptyList(), emptyMap()),
                    summary = if (allSubmitted) "Cevaplar açıldı · Onay bekleniyor" else "Harf: $letter · ${answers.size}/${session.order.size} tamamladı",
                    updatedAt = nowMs,
                )
            }
            "timeout" -> {
                if (phase != "write" || session.turnDeadlineAt <= 0L || nowMs < session.turnDeadlineAt) return null
                session.order.forEach { pid ->
                    if (pid !in answers) answers[pid] = List(5) { "" }
                }
                return session.copy(
                    stateJson = encodeState(letter, usedLetters, "vote", answers, rejects, emptyList(), emptyMap()),
                    summary = "Süre doldu · Cevapları onayla",
                    updatedAt = nowMs,
                )
            }
            "toggle_reject" -> {
                if (phase != "vote") return null
                val key = action.payload.trim() // "targetUid:catIdx"
                if (!key.contains(':')) return null
                val targetUid = key.substringBefore(':')
                if (targetUid == uid) return null // cannot reject own answer
                val current = rejects[key].orEmpty().toMutableSet()
                if (uid in current) current.remove(uid) else current.add(uid)
                if (current.isEmpty()) rejects.remove(key) else rejects[key] = current.toList()
                return session.copy(
                    stateJson = encodeState(letter, usedLetters, "vote", answers, rejects, emptyList(), emptyMap()),
                    updatedAt = nowMs,
                )
            }
            "approve" -> {
                if (phase != "vote") return null
                if (uid !in approvals) approvals.add(uid)
                val allApproved = session.order.all { it in approvals }
                if (!allApproved) {
                    return session.copy(
                        stateJson = encodeState(letter, usedLetters, "vote", answers, rejects, approvals, emptyMap()),
                        summary = "Onay ${approvals.size}/${session.order.size}",
                        updatedAt = nowMs,
                    )
                }

                // Calculate official 0 / 5 / 10 / 15 scoring
                val pts = scoreRound(letter, session.order, answers, rejects)
                val players = session.players.toMutableMap()
                pts.forEach { (pid, add) ->
                    players[pid]?.let { players[pid] = it.copy(score = it.score + add) }
                }
                val isLast = session.round >= session.maxRounds
                val sorted = players.values.sortedByDescending { it.score }
                val winner = if (isLast && sorted.size >= 2 && sorted[0].score > sorted[1].score) sorted[0].uid else null
                val sum = if (isLast) {
                    winner?.let { "${players[it]?.name?.substringBefore(' ')} kazandı!" } ?: "Berabere!"
                } else {
                    "Tur ${session.round} puanlandı · Harf: $letter"
                }
                return session.copy(
                    status = if (isLast) GameStatus.FINISHED else GameStatus.PLAYING,
                    players = players,
                    winnerUid = winner,
                    stateJson = encodeState(letter, usedLetters, "round_over", answers, rejects, approvals, pts),
                    summary = sum,
                    updatedAt = nowMs,
                )
            }
            "next_round" -> {
                if (phase != "round_over" || session.round >= session.maxRounds) return null
                val nextRound = session.round + 1
                val nextLetter = pickLetter(session.id, nextRound, usedLetters)
                val nextUsed = usedLetters + nextLetter
                return session.copy(
                    round = nextRound,
                    turnDeadlineAt = nowMs + session.turnSeconds.coerceAtLeast(30) * 1000L,
                    stateJson = encodeState(nextLetter, nextUsed, "write", emptyMap(), emptyMap(), emptyList(), emptyMap()),
                    summary = "Tur $nextRound/${session.maxRounds} · Harf: $nextLetter",
                    updatedAt = nowMs,
                )
            }
            else -> return null
        }
    }

    internal fun scoreRound(
        letter: String,
        order: List<String>,
        answers: Map<String, List<String>>,
        rejects: Map<String, List<String>>,
    ): Map<String, Int> {
        val targetCh = TurkishText.trUpper(letter).firstOrNull() ?: 'A'
        val totals = order.associateWith { 0 }.toMutableMap()
        val otherCount = (order.size - 1).coerceAtLeast(1)

        for (cat in 0 until 5) {
            val validNormalized = LinkedHashMap<String, String>()
            order.forEach { pid ->
                val raw = answers[pid]?.getOrNull(cat).orEmpty().trim()
                val norm = TurkishText.trUpper(raw).filter { TurkishText.isTurkishLetter(it) }
                val rejCount = rejects["$pid:$cat"]?.size ?: 0
                val rejected = rejCount * 2 >= otherCount && rejCount > 0
                if (!rejected && norm.length >= 2 && norm.first() == targetCh) {
                    validNormalized[pid] = norm
                }
            }
            if (validNormalized.size == 1 && order.size >= 2) {
                val soloPid = validNormalized.keys.first()
                totals[soloPid] = (totals[soloPid] ?: 0) + 15
            } else {
                val freq = validNormalized.values.groupingBy { it }.eachCount()
                validNormalized.forEach { (pid, norm) ->
                    val add = if ((freq[norm] ?: 0) > 1) 5 else 10
                    totals[pid] = (totals[pid] ?: 0) + add
                }
            }
        }
        return totals
    }

    private fun pickLetter(gameId: String, round: Int, used: List<String>): String {
        val remaining = TurkishText.PLAYABLE_NAME_CITY_LETTERS.filter { it.toString() !in used }.toList()
        val pool = remaining.ifEmpty { TurkishText.PLAYABLE_NAME_CITY_LETTERS.toList() }
        return pool[Sha256.deterministicIndex("$gameId|nc|$round", pool.size)].toString()
    }

    private fun parseAnswers(obj: JsonObject): Map<String, List<String>> = buildMap {
        obj.forEach { (k, v) ->
            val arr = (v as? JsonArray) ?: return@forEach
            put(k, arr.mapNotNull { (it as? JsonPrimitive)?.content })
        }
    }

    private fun parseRejects(obj: JsonObject): Map<String, List<String>> = buildMap {
        obj.forEach { (k, v) ->
            val arr = (v as? JsonArray) ?: return@forEach
            put(k, arr.mapNotNull { (it as? JsonPrimitive)?.content })
        }
    }

    private fun parseIntMap(obj: JsonObject): Map<String, Int> = buildMap {
        obj.forEach { (k, v) ->
            (v as? JsonPrimitive)?.content?.toIntOrNull()?.let { put(k, it) }
        }
    }

    private fun encodeState(
        letter: String,
        usedLetters: List<String>,
        phase: String,
        answers: Map<String, List<String>>,
        rejects: Map<String, List<String>>,
        approvals: List<String>,
        roundPoints: Map<String, Int>,
    ): String = buildJsonObject {
        put("letter", letter)
        put("used", buildJsonArray { usedLetters.forEach { add(JsonPrimitive(it)) } })
        put("phase", phase)
        put("answers", buildJsonObject { answers.forEach { (k, list) -> put(k, buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }) } })
        put("rejects", buildJsonObject { rejects.forEach { (k, list) -> put(k, buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }) } })
        put("approvals", buildJsonArray { approvals.forEach { add(JsonPrimitive(it)) } })
        put("roundPoints", buildJsonObject { roundPoints.forEach { (k, v) -> put(k, v) } })
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
        val letter = st.str("letter", "A")
        val phase = st.str("phase", "write")
        val answers = remember(session.stateJson) { parseAnswers(st.obj("answers")) }
        val rejects = remember(session.stateJson) { parseRejects(st.obj("rejects")) }
        val approvals = remember(session.stateJson) { st.arr("approvals").strList() }
        val roundPoints = remember(session.stateJson) { parseIntMap(st.obj("roundPoints")) }
        val tr = !s.english
        val categories = if (tr) NC_CATEGORIES_TR else NC_CATEGORIES_EN

        val fields = remember(session.round) { mutableStateListOf("", "", "", "", "") }
        var remainingSec by remember(session.turnDeadlineAt) { mutableStateOf(session.turnSeconds.coerceAtLeast(30)) }

        LaunchedEffect(phase, session.turnDeadlineAt, session.round) {
            if (phase != "write" || session.status != GameStatus.PLAYING || session.turnDeadlineAt <= 0L) return@LaunchedEffect
            while (true) {
                val leftMs = (session.turnDeadlineAt - controller.serverNow()).coerceAtLeast(0L)
                remainingSec = ((leftMs + 999L) / 1000L).toInt()
                if (leftMs <= 0L) {
                    if (me.uid !in answers) {
                        onAction(GameAction("submit", fields.joinToString("|") { it.replace('|', ' ').trim() }))
                    }
                    delay(800)
                    onAction(GameAction("timeout"))
                    break
                }
                delay(250)
            }
        }

        Column(
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header with huge letter tile + timer/scores
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(letter, color = InkInverse, fontSize = 30.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (phase) {
                            "write" -> if (tr) "Kalan süre: ${remainingSec} sn" else "Time left: ${remainingSec}s"
                            "vote" -> if (tr) "Cevapları kontrol et ve onayla" else "Review and approve answers"
                            else -> if (tr) "Tur ${session.round} Sonucu" else "Round ${session.round} Result"
                        },
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        session.order.joinToString(" · ") { uid ->
                            val p = session.players[uid]
                            "${if (uid == me.uid) s.you else p?.name?.substringBefore(' ').orEmpty()}: ${p?.score ?: 0}"
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            if (phase == "write") {
                val submitted = me.uid in answers
                if (submitted) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(DarkSurface)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (tr) "Cevapların kilitlendi (${answers.size}/${session.order.size}). Diğer oyuncular bekleniyor..." else "Answers locked (${answers.size}/${session.order.size}). Waiting for others...",
                            color = TextSecondary,
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    categories.forEachIndexed { idx, label ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DarkSurface)
                                    .border(1.dp, BorderHover, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                            ) {
                                if (fields[idx].isEmpty()) {
                                    Text("$letter...", color = TextMuted, fontSize = 15.sp)
                                }
                                BasicTextField(
                                    value = fields[idx],
                                    onValueChange = { fields[idx] = it.take(24) },
                                    singleLine = true,
                                    textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                                    cursorBrush = SolidColor(Ink),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Ink)
                            .clickable {
                                onAction(GameAction("submit", fields.joinToString("|") { it.replace('|', ' ').trim() }))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (tr) "Cevapları Kilitle" else "Lock Answers", color = InkInverse, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                // Vote or round_over table
                categories.forEachIndexed { catIdx, catLabel ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurface)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(catLabel, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        session.order.forEach { pid ->
                            val word = answers[pid]?.getOrNull(catIdx).orEmpty().ifBlank { "—" }
                            val key = "$pid:$catIdx"
                            val rejList = rejects[key].orEmpty()
                            val rejectedByMe = me.uid in rejList
                            val pName = if (pid == me.uid) s.you else session.players[pid]?.name?.substringBefore(' ').orEmpty()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(pName, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                                Text(
                                    word,
                                    color = if (rejList.isNotEmpty()) TextMuted else TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (phase == "vote" && pid != me.uid && word != "—") {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (rejectedByMe) Ink else DarkSurfaceVariant)
                                            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                                            .clickable { onAction(GameAction("toggle_reject", key)) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            if (rejectedByMe) (if (tr) "Reddedildi" else "Rejected") else (if (tr) "Reddet" else "Reject"),
                                            color = if (rejectedByMe) InkInverse else TextSecondary,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (phase == "vote") {
                    val approved = me.uid in approvals
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (approved) DarkSurfaceVariant else Ink)
                            .clickable(enabled = !approved) { onAction(GameAction("approve")) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (approved) {
                                if (tr) "Onaylandı (${approvals.size}/${session.order.size})" else "Approved (${approvals.size}/${session.order.size})"
                            } else {
                                if (tr) "Puanlamayı Onayla" else "Approve Scoring"
                            },
                            color = if (approved) TextSecondary else InkInverse,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else if (phase == "round_over") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        session.order.forEach { pid ->
                            val add = roundPoints[pid] ?: 0
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DarkSurfaceVariant)
                                    .padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(if (pid == me.uid) s.you else session.players[pid]?.name?.substringBefore(' ').orEmpty(), color = TextSecondary, fontSize = 11.sp)
                                Text("+$add", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (session.status == GameStatus.PLAYING) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Ink)
                                .clickable { onAction(GameAction("next_round")) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (tr) "Sonraki Tur" else "Next Round", color = InkInverse, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

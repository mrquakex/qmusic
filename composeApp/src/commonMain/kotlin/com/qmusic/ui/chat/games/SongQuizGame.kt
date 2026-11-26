package com.qmusic.ui.chat.games

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.chat.ChatController
import com.qmusic.ui.chat.ChatStrings
import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.StatusAudio
import com.qmusic.ui.chat.games.GameJson.arr
import com.qmusic.ui.chat.games.GameJson.int
import com.qmusic.ui.chat.games.GameJson.long
import com.qmusic.ui.chat.games.GameJson.obj
import com.qmusic.ui.chat.games.GameJson.str
import com.qmusic.ui.chat.games.GameJson.strList
import com.qmusic.ui.theme.BorderHover
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object SongQuizEngine : GameEngine {
    override val kind: GameKind = GameKind.SONG_QUIZ

    override fun title(s: ChatStrings): String = if (s.english) "Guess the Song!" else "Şarkıyı Bil!"
    override fun subtitle(s: ChatStrings): String =
        if (s.english) "Listen to 12s music clips and race to pick" else "12 saniyelik müzik kesitinden şarkıyı tanı"

    override fun rules(s: ChatStrings): String = if (s.english) {
        "Each round plays a 12-second preview from Nuvelo's music library. Pick the right song among 4 choices! Faster correct answers score more points: 100 pts (0-3s), 75 pts (3-6s), 50 pts (6-15s)."
    } else {
        "Her turda Nuvelo müzik arşivinden 12 saniyelik bir kesit çalar. 4 şık arasından doğru şarkıyı seç! Hızlı bilen daha çok puan alır: 0-3 sn 100 puan, 3-6 sn 75 puan, 6-15 sn 50 puan."
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
        val rawExtra = GameJson.parseObject(config.extra)
        val questions = rawExtra.arr("questions").mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        val totalRounds = questions.size.coerceAtLeast(1)
        val turnSec = config.turnSeconds.coerceIn(10, 25)
        val genre = rawExtra.str("genre", config.mode.ifBlank { "mixed" })
        val players = linkedMapOf(
            creator.uid to GamePlayer(creator.uid, creator.name, score = 0, joinedAt = nowMs, ready = true),
        )
        val order = mutableListOf(creator.uid)
        val status = when {
            solo -> GameStatus.PLAYING
            opponent != null && opponent.uid != creator.uid -> {
                players[opponent.uid] = GamePlayer(opponent.uid, opponent.name, score = 0, joinedAt = nowMs, ready = true)
                order.add(opponent.uid)
                GameStatus.PLAYING
            }
            else -> GameStatus.WAITING
        }
        return GameSession(
            id = gameId,
            chatId = chatId,
            kind = GameKind.SONG_QUIZ,
            status = status,
            createdBy = creator.uid,
            players = players,
            order = order,
            turn = null,
            round = 1,
            maxRounds = totalRounds,
            turnSeconds = turnSec,
            turnDeadlineAt = if (status == GameStatus.PLAYING) nowMs + turnSec * 1000L else 0L,
            version = 1L,
            stateJson = encodeState(
                genre = genre,
                phase = "play",
                roundStartedAt = nowMs,
                questions = questions,
                answers = emptyMap(),
                roundAwarded = emptyMap(),
            ),
            summary = if (status == GameStatus.PLAYING) "Şarkı 1/$totalRounds çalıyor" else "Oyuncu bekleniyor",
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
        val questions = st.arr("questions").mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        val genre = st.str("genre", "mixed")
        return session.copy(
            status = GameStatus.PLAYING,
            players = players,
            order = order,
            turnDeadlineAt = nowMs + session.turnSeconds.coerceAtLeast(15) * 1000L,
            stateJson = encodeState(genre, "play", nowMs, questions, emptyMap(), emptyMap()),
            summary = "Şarkı 1/${session.maxRounds} başladı",
            updatedAt = nowMs,
        )
    }

    internal fun speedPoints(elapsedMs: Long): Int = when {
        elapsedMs <= 3_000L -> 100
        elapsedMs <= 6_000L -> 75
        else -> 50
    }

    override fun applyAction(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession? {
        GameRulesHelper.handleCommonActions(session, uid, action, nowMs)?.let { return it }
        if (session.status != GameStatus.PLAYING || uid !in session.players) return null

        val st = GameJson.parseObject(session.stateJson)
        val genre = st.str("genre", "mixed")
        val phase = st.str("phase", "play")
        val roundStartedAt = st.long("startedAt", session.updatedAt)
        val questions = st.arr("questions").mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        val q = questions.getOrNull(session.round - 1) ?: return null
        val correctIdx = q.int("correct", 0)
        val answers = parseIntMap(st.obj("answers")).toMutableMap()
        val awarded = parseIntMap(st.obj("awarded")).toMutableMap()

        when (action.type) {
            "answer" -> {
                if (phase != "play" || uid in answers) return null
                val parts = action.payload.split('|')
                val choice = parts.getOrNull(0)?.toIntOrNull() ?: return null
                if (choice !in 0..3) return null
                val elapsed = parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0L) ?: (nowMs - roundStartedAt).coerceAtLeast(0L)
                val pts = if (choice == correctIdx) speedPoints(elapsed) else 0
                answers[uid] = choice
                awarded[uid] = pts

                val players = session.players.toMutableMap()
                if (pts > 0) {
                    players[uid]?.let { players[uid] = it.copy(score = it.score + pts) }
                }
                val allDone = session.order.all { it in answers }
                return finishOrKeepRound(session, players, genre, roundStartedAt, questions, answers, awarded, allDone, nowMs)
            }
            "timeout" -> {
                if (phase != "play" || session.turnDeadlineAt <= 0L || nowMs < session.turnDeadlineAt) return null
                session.order.forEach { pid ->
                    if (pid !in answers) {
                        answers[pid] = -1
                        awarded[pid] = 0
                    }
                }
                return finishOrKeepRound(session, session.players, genre, roundStartedAt, questions, answers, awarded, true, nowMs)
            }
            "next_round" -> {
                if (phase != "reveal" || session.round >= session.maxRounds) return null
                val nextRound = session.round + 1
                return session.copy(
                    round = nextRound,
                    turnDeadlineAt = nowMs + session.turnSeconds.coerceAtLeast(15) * 1000L,
                    stateJson = encodeState(genre, "play", nowMs, questions, emptyMap(), emptyMap()),
                    summary = "Şarkı $nextRound/${session.maxRounds} çalıyor",
                    updatedAt = nowMs,
                )
            }
            else -> return null
        }
    }

    private fun finishOrKeepRound(
        session: GameSession,
        players: Map<String, GamePlayer>,
        genre: String,
        roundStartedAt: Long,
        questions: List<JsonObject>,
        answers: Map<String, Int>,
        awarded: Map<String, Int>,
        roundComplete: Boolean,
        nowMs: Long,
    ): GameSession {
        if (!roundComplete) {
            return session.copy(
                players = players,
                stateJson = encodeState(genre, "play", roundStartedAt, questions, answers, awarded),
                summary = "Şarkı ${session.round}/${session.maxRounds} · ${answers.size}/${session.order.size} cevapladı",
                updatedAt = nowMs,
            )
        }
        val q = questions.getOrNull(session.round - 1)
        val songTitle = q?.str("title").orEmpty()
        val isLast = session.round >= session.maxRounds
        val sorted = players.values.sortedByDescending { it.score }
        val winner = when {
            !isLast -> null
            players.size == 1 -> players.values.first().takeIf { it.score > 0 }?.uid
            sorted.size >= 2 && sorted[0].score > sorted[1].score -> sorted[0].uid
            else -> null
        }
        val sum = if (isLast) {
            winner?.let { "${players[it]?.name?.substringBefore(' ')} kazandı (${players[it]?.score} puan)" } ?: "Berabere · $songTitle"
        } else {
            "Doğru cevap: $songTitle"
        }
        return session.copy(
            status = if (isLast) GameStatus.FINISHED else GameStatus.PLAYING,
            players = players,
            winnerUid = winner,
            stateJson = encodeState(genre, "reveal", roundStartedAt, questions, answers, awarded),
            summary = sum,
            updatedAt = nowMs,
        )
    }

    private fun parseIntMap(obj: JsonObject): Map<String, Int> = buildMap {
        obj.forEach { (k, v) -> (v as? JsonPrimitive)?.content?.toIntOrNull()?.let { put(k, it) } }
    }

    private fun encodeState(
        genre: String,
        phase: String,
        roundStartedAt: Long,
        questions: List<JsonObject>,
        answers: Map<String, Int>,
        roundAwarded: Map<String, Int>,
    ): String = buildJsonObject {
        put("genre", genre)
        put("phase", phase)
        put("startedAt", roundStartedAt)
        put("questions", buildJsonArray { questions.forEach { add(it) } })
        put("answers", buildJsonObject { answers.forEach { (k, v) -> put(k, v) } })
        put("awarded", buildJsonObject { roundAwarded.forEach { (k, v) -> put(k, v) } })
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
        val phase = st.str("phase", "play")
        val questions = remember(session.stateJson) { st.arr("questions").mapNotNull { runCatching { it.jsonObject }.getOrNull() } }
        val q = questions.getOrNull(session.round - 1)
        val answers = remember(session.stateJson) { parseIntMap(st.obj("answers")) }
        val awarded = remember(session.stateJson) { parseIntMap(st.obj("awarded")) }
        val tr = !s.english

        if (q == null) {
            Box(modifier, contentAlignment = Alignment.Center) {
                Text(if (tr) "Şarkı paketi bulunamadı" else "No quiz tracks found", color = TextSecondary)
            }
            return
        }

        val songId = q.str("songId")
        val startMs = q.long("startMs", 30_000L)
        val correctIdx = q.int("correct", 0)
        val choices = remember(q) { q.arr("choices").strList() }

        var audioUrl by remember(songId) { mutableStateOf<String?>(null) }
        var loadingAudio by remember(songId) { mutableStateOf(true) }
        var muted by remember(session.round) { mutableStateOf(false) }
        var retryCount by remember(songId) { mutableStateOf(0) }
        var remainingSec by remember(session.turnDeadlineAt) { mutableStateOf(session.turnSeconds.coerceAtLeast(15)) }
        var localStartMs by remember(session.round) { mutableStateOf(controller.serverNow()) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(songId, retryCount) {
            loadingAudio = true
            audioUrl = runCatching { controller.songAudio(songId) }.getOrNull()
            loadingAudio = false
            localStartMs = controller.serverNow()
        }

        LaunchedEffect(phase, session.turnDeadlineAt, session.round) {
            if (phase != "play" || session.status != GameStatus.PLAYING || session.turnDeadlineAt <= 0L) return@LaunchedEffect
            while (true) {
                val leftMs = (session.turnDeadlineAt - controller.serverNow()).coerceAtLeast(0L)
                remainingSec = ((leftMs + 999L) / 1000L).toInt()
                if (leftMs <= 0L) {
                    onAction(GameAction("timeout"))
                    break
                }
                delay(200)
            }
        }

        val playingNow = session.status == GameStatus.PLAYING && phase == "play" && !muted && !audioUrl.isNullOrBlank()
        audioUrl?.let { url ->
            StatusAudio(
                url = url,
                playing = playingNow,
                startMs = startMs,
                loopMs = 12_000L,
                onFailed = {
                    if (retryCount < 1) {
                        scope.launch { retryCount++ }
                    }
                },
            )
        }

        val myChoice = answers[me.uid]

        Column(
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Scores bar
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                session.order.forEach { uid ->
                    val p = session.players[uid] ?: return@forEach
                    val done = uid in answers && phase == "play"
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurface)
                            .border(1.dp, if (done) Ink else BorderSubtle, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(if (uid == me.uid) s.you else p.name.substringBefore(' '), color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                        Text("${p.score}", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Music preview stage
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(DarkSurface)
                    .border(1.dp, BorderHover, RoundedCornerShape(18.dp))
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (playingNow) Ink else DarkSurfaceVariant)
                        .clickable { muted = !muted },
                    contentAlignment = Alignment.Center,
                ) {
                    if (loadingAudio) {
                        CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(
                            if (playingNow) QMusicIcons.Waveform else QMusicIcons.Headphones,
                            contentDescription = null,
                            tint = if (playingNow) InkInverse else Ink,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                if (playingNow) {
                    MonochromeEqualizer()
                }
                Text(
                    when {
                        phase == "reveal" -> "${q.str("title")} — ${q.str("artist")}"
                        loadingAudio -> if (tr) "Şarkı kesiti yükleniyor..." else "Loading audio clip..."
                        audioUrl == null -> if (tr) "Ses yüklenemedi · Şıklardan tahmin et" else "Audio unavailable · Guess from choices"
                        else -> if (tr) "Dinle ve tahmin et · ${remainingSec} sn" else "Listen & guess · ${remainingSec}s"
                    },
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }

            // 4 Choices
            val tags = listOf("A", "B", "C", "D")
            choices.forEachIndexed { idx, label ->
                val isPicked = myChoice == idx
                val isCorrect = phase == "reveal" && idx == correctIdx
                val isWrongPick = phase == "reveal" && isPicked && idx != correctIdx
                val bg = when {
                    isCorrect -> Ink
                    isPicked -> DarkSurfaceVariant
                    else -> DarkSurface
                }
                val fg = if (isCorrect) InkInverse else TextPrimary
                val borderC = when {
                    isCorrect || isPicked -> Ink
                    else -> BorderSubtle
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(bg)
                        .border(if (isCorrect || isPicked) 1.5.dp else 1.dp, borderC, RoundedCornerShape(12.dp))
                        .clickable(enabled = phase == "play" && myChoice == null && session.status == GameStatus.PLAYING) {
                            val elapsed = (controller.serverNow() - localStartMs).coerceAtLeast(0L)
                            onAction(GameAction("answer", "$idx|$elapsed"))
                        }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (isCorrect) InkInverse.copy(alpha = 0.16f) else DarkSurfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(tags.getOrElse(idx) { "" }, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        label,
                        color = fg,
                        fontSize = 14.sp,
                        fontWeight = if (isCorrect || isPicked) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isCorrect) {
                        Text("✓", color = InkInverse, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    } else if (isWrongPick) {
                        Text("×", color = TextMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (phase == "reveal") {
                val myPts = awarded[me.uid] ?: 0
                Text(
                    if (myPts > 0) (if (tr) "+$myPts puan kazandın!" else "+$myPts points!") else (if (tr) "Bu turda 0 puan" else "0 points this round"),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
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
                        Text(if (tr) "Sonraki Şarkı" else "Next Song", color = InkInverse, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonochromeEqualizer() {
    val inf = rememberInfiniteTransition()
    val bars = listOf(
        inf.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse)).value,
        inf.animateFloat(0.95f, 0.3f, infiniteRepeatable(tween(520), RepeatMode.Reverse)).value,
        inf.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(360), RepeatMode.Reverse)).value,
        inf.animateFloat(0.85f, 0.2f, infiniteRepeatable(tween(480), RepeatMode.Reverse)).value,
        inf.animateFloat(0.3f, 0.95f, infiniteRepeatable(tween(400), RepeatMode.Reverse)).value,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(22.dp),
    ) {
        bars.forEach { f ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((22f * f).dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Ink),
            )
        }
    }
}

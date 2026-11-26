package com.qmusic.ui.chat.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.chat.ChatController
import com.qmusic.ui.chat.ChatStrings
import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.games.GameJson.arr
import com.qmusic.ui.chat.games.GameJson.str
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Son Harften Kelime Zinciri (1-1 & Group, 2-6 players):
 * - Next word must start with the previous word's last letter (Turkish 'Ğ' rule: walks back to preceding letter).
 * - Repeats forbidden; validated against offline TurkishWords dictionary.
 * - Timed turns synced via controller.serverNow(); timeout costs 1 life (3 lives each), last survivor wins.
 */
object WordChainEngine : GameEngine {
    override val kind: GameKind = GameKind.WORD_CHAIN
    private const val START_LIVES = 3

    override fun title(s: ChatStrings): String = if (s.english) "Word Chain" else "Kelime Zinciri"
    override fun subtitle(s: ChatStrings): String =
        if (s.english) "Last-letter Turkish word chain with timer & 3 lives" else "Son harften kelime türet · süreli ve 3 canlı"

    override fun rules(s: ChatStrings): String = if (s.english) {
        "Write a valid Turkish word (3+ letters) starting with the last letter of the previous word. Words ending in 'Ğ' use the letter before it. No repeats! Running out of time costs 1 of your 3 lives."
    } else {
        "Önceki kelimenin son harfiyle başlayan en az 3 harfli geçerli bir Türkçe kelime yaz. 'Ğ' ile biten kelimede bir önceki harf geçerlidir. Tekrar yasak! Süre dolunca 3 canından 1'i gider; son kalan kazanır."
    }

    /** Returns required starting Turkish uppercase char and whether the 'Ğ' fallback rule was applied. */
    fun requiredStartLetter(wordRaw: String): Pair<Char, Boolean> {
        val w = TurkishText.trNormalize(wordRaw)
        if (w.isEmpty()) return 'A' to false
        var idx = w.lastIndex
        var usedSoftG = false
        while (idx > 0 && w[idx] == 'Ğ') {
            idx--
            usedSoftG = true
        }
        return w[idx] to usedSoftG
    }

    data class ChainEntry(val word: String, val uid: String, val name: String, val meaning: String = "")

    private data class State(val startLetter: Char, val entries: List<ChainEntry>)

    private fun decode(raw: String): State {
        val o = GameJson.parseObject(raw)
        val entries = o.arr("entries").mapNotNull { el ->
            val eo = el as? JsonObject ?: return@mapNotNull null
            val word = eo.str("w").ifBlank { return@mapNotNull null }
            ChainEntry(word, eo.str("u"), eo.str("n"), eo.str("m"))
        }
        val start = o.str("startLetter").firstOrNull()?.let(TurkishText::trUpperChar)
            ?: entries.firstOrNull()?.word?.firstOrNull()
            ?: 'K'
        return State(start, entries)
    }

    private fun encode(st: State): String = buildJsonObject {
        put("startLetter", st.startLetter.toString())
        put(
            "entries",
            buildJsonArray {
                st.entries.forEach { e ->
                    add(
                        buildJsonObject {
                            put("w", e.word)
                            put("u", e.uid)
                            put("n", e.name)
                            if (e.meaning.isNotBlank()) put("m", e.meaning.take(90))
                        },
                    )
                }
            },
        )
    }.toString()

    /** Returns the required starting letter (and whether the 'Ğ' fallback rule applied) for the current chain state. */
    fun currentRequirement(session: GameSession): Pair<Char, Boolean> {
        val st = decode(session.stateJson)
        val lastWord = st.entries.lastOrNull()?.word.orEmpty()
        return if (lastWord.isNotEmpty()) requiredStartLetter(lastWord) else (st.startLetter to false)
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
        val letters = TurkishText.PLAYABLE_NAME_CITY_LETTERS
        val explicit = config.extra.trim().firstOrNull()?.let(TurkishText::trUpperChar)?.takeIf { it in letters }
        val startLetter = explicit ?: letters[Sha256.deterministicIndex("$gameId|$nowMs|${Sha256.randomSaltHex()}", letters.length)]
        val seconds = config.turnSeconds.takeIf { it in 10..60 } ?: 20
        val st = State(startLetter = startLetter, entries = emptyList())
        return GameSession(
            id = gameId,
            chatId = chatId,
            kind = GameKind.WORD_CHAIN,
            status = GameStatus.WAITING,
            createdBy = creator.uid,
            players = mapOf(creator.uid to GamePlayer(creator.uid, creator.name, score = 0, joinedAt = nowMs, lives = START_LIVES, ready = true)),
            order = listOf(creator.uid),
            turn = creator.uid,
            round = 1,
            maxRounds = 1,
            turnSeconds = seconds,
            turnDeadlineAt = 0L,
            stateJson = encode(st),
            summary = "${creator.name.substringBefore(' ')} davet gönderdi · Harf çarkı hazır!",
            createdAt = nowMs,
            updatedAt = nowMs,
            expireAt = nowMs + GAME_TTL_MS,
        )
    }

    override fun join(session: GameSession, player: ChatUser, nowMs: Long): GameSession? {
        if (player.uid in session.players) return null
        if (session.status != GameStatus.WAITING && !(session.status == GameStatus.PLAYING && session.players.size < 6)) return null
        val updatedPlayers = session.players + (player.uid to GamePlayer(player.uid, player.name, joinedAt = nowMs, lives = START_LIVES, ready = true))
        val updatedOrder = session.order + player.uid
        val firstTurn = if (session.status == GameStatus.WAITING) player.uid else session.turn
        // +3s grace on turn 1 so the 2.35s horizontal letter roulette animation finishes smoothly
        val deadline = if (session.status == GameStatus.WAITING) nowMs + (session.turnSeconds + 3) * 1000L else session.turnDeadlineAt
        val req = currentRequirement(session).first
        return session.copy(
            status = GameStatus.PLAYING,
            players = updatedPlayers,
            order = updatedOrder,
            turn = firstTurn,
            turnDeadlineAt = deadline,
            summary = "${updatedPlayers[firstTurn]?.name?.substringBefore(' ')} · '$req' ile başla",
            updatedAt = nowMs,
        )
    }

    /** Returns null if valid, or a localized error message string if invalid. */
    fun validateWord(session: GameSession, rawWord: String, english: Boolean, checkLocalDict: Boolean = true): String? {
        val cleanInput = rawWord.substringBefore('|').trim()
        val word = TurkishText.trNormalize(cleanInput)
        if (word.length < 3 || word != TurkishText.trUpper(cleanInput)) {
            return if (english) "Enter at least 3 Turkish letters" else "En az 3 Türkçe harf yaz"
        }
        val st = decode(session.stateJson)
        val (req, softG) = currentRequirement(session)
        if (word.first() != req) {
            return if (english) {
                "Must start with '$req'" + if (softG) " (ğ rule)" else ""
            } else {
                "'$req' harfiyle başlamalı" + if (softG) " (ğ kuralı)" else ""
            }
        }
        if (st.entries.any { it.word == word }) {
            return if (english) "Already used in this game" else "Bu kelime daha önce kullanıldı"
        }
        if (checkLocalDict && !TurkishWords.isValidChainWord(word)) {
            return if (english) "Not in Turkish dictionary" else "TDK sözlüğünde bulunamadı"
        }
        return null
    }

    override fun applyAction(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession? {
        GameRulesHelper.handleCommonActions(session, uid, action, nowMs)?.let { return it }
        if (session.status != GameStatus.PLAYING || uid !in session.players) return null
        val st = decode(session.stateJson)

        return when (action.type) {
            "word" -> {
                if (session.turn != uid) return null
                val rawWord = action.payload.substringBefore('|')
                val meaning = action.payload.substringAfter('|', "").trim().take(90)
                if (validateWord(session, rawWord, english = false, checkLocalDict = TurkishDictApi.httpGet == null) != null) return null
                val word = TurkishText.trNormalize(rawWord)
                val mePlayer = session.players.getValue(uid)
                val updatedPlayers = session.players + (uid to mePlayer.copy(score = mePlayer.score + 1))
                val nextEntries = (st.entries + ChainEntry(word, uid, mePlayer.name, meaning)).takeLast(120)
                val nextTurn = GameRulesHelper.nextInOrder(session.order, uid) { (updatedPlayers[it]?.lives ?: 0) > 0 }
                val (req, _) = requiredStartLetter(word)
                session.copy(
                    players = updatedPlayers,
                    turn = nextTurn,
                    turnDeadlineAt = nowMs + session.turnSeconds * 1000L,
                    stateJson = encode(st.copy(entries = nextEntries)),
                    summary = "$word → ${updatedPlayers[nextTurn]?.name?.substringBefore(' ')} ('$req')",
                    updatedAt = nowMs,
                )
            }
            "timeout" -> {
                val targetUid = session.turn ?: return null
                if (session.turnDeadlineAt <= 0L || nowMs < session.turnDeadlineAt) return null
                val targetPlayer = session.players[targetUid] ?: return null
                val newLives = (targetPlayer.lives - 1).coerceAtLeast(0)
                val updatedPlayers = session.players + (targetUid to targetPlayer.copy(lives = newLives))
                val alive = session.order.filter { (updatedPlayers[it]?.lives ?: 0) > 0 }
                if (alive.size <= 1) {
                    val winner = alive.firstOrNull()
                    val winnerName = winner?.let { updatedPlayers[it]?.name?.substringBefore(' ') }.orEmpty()
                    return session.copy(
                        status = GameStatus.FINISHED,
                        players = updatedPlayers,
                        turn = null,
                        turnDeadlineAt = 0L,
                        winnerUid = winner,
                        summary = if (winnerName.isNotEmpty()) "$winnerName kazandı!" else "Oyun bitti",
                        updatedAt = nowMs,
                    )
                }
                val nextTurn = GameRulesHelper.nextInOrder(session.order, targetUid) { (updatedPlayers[it]?.lives ?: 0) > 0 }
                session.copy(
                    players = updatedPlayers,
                    turn = nextTurn,
                    turnDeadlineAt = nowMs + session.turnSeconds * 1000L,
                    summary = "${targetPlayer.name.substringBefore(' ')} süre kaçırdı · Sıra ${updatedPlayers[nextTurn]?.name?.substringBefore(' ')}",
                    updatedAt = nowMs,
                )
            }
            else -> null
        }
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
        val (reqLetter, usedSoftG) = remember(session.stateJson) { currentRequirement(session) }
        val myTurn = session.status == GameStatus.PLAYING && session.turn == me.uid
        var input by remember(st.entries.size) { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf<String?>(null) }
        var checking by remember { mutableStateOf(false) }
        var reelDone by remember(session.id) { mutableStateOf(st.entries.isNotEmpty() || session.status != GameStatus.PLAYING) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        var nowMs by remember { mutableLongStateOf(controller.serverNow()) }
        var claimedDeadline by remember { mutableLongStateOf(0L) }
        LaunchedEffect(session.turnDeadlineAt, session.status) {
            while (session.status == GameStatus.PLAYING && session.turnDeadlineAt > 0L) {
                nowMs = controller.serverNow()
                if (nowMs >= session.turnDeadlineAt + 300L && claimedDeadline != session.turnDeadlineAt) {
                    claimedDeadline = session.turnDeadlineAt
                    onAction(GameAction("timeout"))
                }
                delay(250)
            }
        }
        LaunchedEffect(st.entries.size) {
            if (st.entries.isNotEmpty()) listState.animateScrollToItem(st.entries.lastIndex)
        }
        LaunchedEffect(errorMsg) {
            if (errorMsg != null) {
                delay(2000)
                errorMsg = null
            }
        }

        val remainingMs = (session.turnDeadlineAt - nowMs).coerceAtLeast(0L)
        val progress = if (session.turnSeconds > 0) (remainingMs.toFloat() / (session.turnSeconds * 1000f)).coerceIn(0f, 1f) else 1f

        val cyan = GameColors.Cyan
        val indigo = GameColors.Indigo
        val timerColor = when {
            remainingMs <= 5_000L -> GameColors.Crimson
            remainingMs <= 10_000L -> GameColors.Amber
            else -> cyan
        }

        Column(modifier = modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
            // Players & crimson lives strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurface)
                    .border(1.dp, cyan.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                session.order.mapNotNull { session.players[it] }.forEach { p ->
                    val active = session.turn == p.uid && session.status == GameStatus.PLAYING
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) cyan.copy(alpha = 0.18f) else DarkSurfaceVariant)
                            .border(1.dp, if (active) cyan else BorderSubtle, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            if (p.uid == me.uid) (if (en) "You" else "Sen") else p.name.substringBefore(' '),
                            color = if (active) cyan else TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                            for (i in 0 until START_LIVES) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (i < p.lives) GameColors.Crimson else BorderSubtle),
                                )
                            }
                        }
                    }
                }
            }

            // Color-shifting countdown bar (Cyan -> Amber -> Crimson)
            if (session.status == GameStatus.PLAYING && session.turnSeconds > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(999.dp)).background(DarkSurfaceVariant)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(RoundedCornerShape(999.dp)).background(timerColor))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${(remainingMs + 999) / 1000}s", color = timerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Horizontal linear letter roulette on game start, or active next-letter prompt once words flow
            Spacer(Modifier.height(10.dp))
            if (st.entries.isEmpty()) {
                LetterRouletteStrip(
                    targetLetter = st.startLetter,
                    spinKey = session.id,
                    accent = cyan,
                    animate = session.status == GameStatus.PLAYING,
                    onSettled = { reelDone = true },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        session.status == GameStatus.WAITING -> if (en) "Waiting for player to spin the letter reel…" else "Oyuncu katılınca başlangıç harfi çarkı dönecek…"
                        !reelDone -> if (en) "Picking a random Turkish letter…" else "Rastgele başlangıç harfi çekiliyor…"
                        myTurn -> if (en) "'$reqLetter' landed! Write the first word" else "'$reqLetter' geldi! İlk kelimeyi yaz"
                        else -> {
                            val who = session.players[session.turn]?.name?.substringBefore(' ').orEmpty()
                            if (en) "'$reqLetter' landed · $who goes first" else "'$reqLetter' geldi · İlk sıra $who oyuncusunda"
                        }
                    },
                    color = if (reelDone && myTurn) cyan else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(cyan.copy(alpha = 0.12f))
                        .border(1.2.dp, if (myTurn) cyan else cyan.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(cyan),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(reqLetter.toString(), color = GameColors.onAccent(cyan), fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (en) "Start with '$reqLetter'" + if (usedSoftG) " (ğ rule)" else ""
                            else "'$reqLetter' harfiyle başla" + if (usedSoftG) " (ğ kuralı)" else "",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (myTurn) (if (en) "Your turn — ${st.entries.size} words · TDK live" else "Sıra sende · ${st.entries.size} kelime · TDK canlı")
                            else {
                                val who = session.players[session.turn]?.name?.substringBefore(' ').orEmpty()
                                if (en) "$who's turn…" else "Sıra $who oyuncusunda…"
                            },
                            color = if (myTurn) cyan else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (myTurn) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            // Word chain history (with live TDK meaning line!)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(st.entries, key = { i, e -> "$i:${e.word}" }) { idx, entry ->
                    val isMine = entry.uid == me.uid
                    val isStarter = entry.uid.isEmpty()
                    val bubbleBg = when {
                        isStarter -> DarkSurface
                        isMine -> cyan
                        else -> indigo.copy(alpha = 0.18f)
                    }
                    val bubbleFg = if (isMine) GameColors.onAccent(cyan) else TextPrimary
                    val bubbleSub = if (isMine) GameColors.onAccent(cyan).copy(alpha = 0.78f) else TextSecondary
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isStarter) Arrangement.Center else if (isMine) Arrangement.End else Arrangement.Start,
                    ) {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(13.dp))
                                .background(bubbleBg)
                                .border(1.dp, if (isMine) cyan else if (isStarter) BorderSubtle else indigo.copy(alpha = 0.45f), RoundedCornerShape(13.dp))
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(
                                "${idx + 1}. ${entry.word}",
                                color = bubbleFg,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (entry.meaning.isNotBlank()) {
                                Text(
                                    entry.meaning,
                                    color = bubbleSub,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                if (isStarter) (if (en) "Starter" else "Başlangıç") else if (isMine) (if (en) "You" else "Sen") else entry.name.substringBefore(' '),
                                color = bubbleSub,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }

            errorMsg?.let { msg ->
                Text(msg, color = GameColors.Crimson, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
            }

            // Input bar with live TDK API verification
            val submitWord: () -> Unit = {
                if (myTurn && !checking && input.isNotBlank()) {
                    val structuralErr = validateWord(session, input, en, checkLocalDict = false)
                    if (structuralErr != null) {
                        errorMsg = structuralErr
                    } else {
                        val candidate = TurkishText.trNormalize(input)
                        checking = true
                        scope.launch {
                            val res = TurkishDictApi.lookup(candidate)
                            checking = false
                            if (!res.valid) {
                                errorMsg = if (en) "Not found in TDK Turkish Dictionary" else "TDK sözlüğünde bulunamadı"
                            } else {
                                onAction(GameAction("word", payload = "$candidate|${res.meaning}"))
                                input = ""
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkSurfaceVariant)
                    .border(1.2.dp, if (myTurn) cyan else BorderSubtle, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (input.isEmpty()) {
                        Text(
                            if (myTurn) (if (en) "Word starting with $reqLetter…" else "$reqLetter ile başlayan kelime…")
                            else (if (en) "Wait for your turn…" else "Sıranı bekle…"),
                            color = TextMuted,
                            fontSize = 15.sp,
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { if (myTurn && !checking) input = it.take(24) },
                        enabled = myTurn && !checking,
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(cyan),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitWord() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (myTurn && input.trim().length >= 3 && !checking) cyan else DarkSurface)
                        .clickable(enabled = myTurn && input.trim().length >= 3 && !checking, onClick = submitWord),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checking) {
                        CircularProgressIndicator(color = cyan, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(
                            QMusicIcons.Send,
                            contentDescription = null,
                            tint = if (myTurn && input.trim().length >= 3) GameColors.onAccent(cyan) else TextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

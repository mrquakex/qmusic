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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.qmusic.ui.chat.games.GameJson.arr
import com.qmusic.ui.chat.games.GameJson.bool
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
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Kelime Düellosu (Turkish 5-letter Wordle):
 * - Mode "race" (1-1, Group, Solo): everyone guesses the same 5-letter Turkish word in up to 6 tries.
 *   Rivals only see your tile pattern (not letters) until the round ends.
 * - Mode "duel" (1-1, 2 or 4 rounds): setter picks a secret 5-letter valid Turkish word, guesser solves in 6 tries,
 *   then roles swap.
 */
object WordDuelEngine : GameEngine {
    override val kind: GameKind = GameKind.WORD_DUEL
    private const val MAX_TRIES = 6
    private const val WORD_LEN = 5

    override fun title(s: ChatStrings): String = if (s.english) "Word Duel" else "Kelime Düellosu"
    override fun subtitle(s: ChatStrings): String =
        if (s.english) "5-letter Turkish Wordle race or set-a-word duel" else "5 harfli Türkçe kelime yarışı veya kur & bul"

    override fun rules(s: ChatStrings): String = if (s.english) {
        "Guess the 5-letter Turkish word in 6 tries. Filled tile = exact spot; thick border + dot = right letter, wrong spot; dim tile = not in word. Fewest tries wins (fastest breaks ties)."
    } else {
        "5 harfli Türkçe kelimeyi 6 hakta bul. Dolu kutu = doğru yer; kalın çerçeve + nokta = harf var ama yanlış yer; soluk kutu = harf yok. En az denemede (eşitlikte en hızlı) bulan kazanır."
    }

    /**
     * Exact 2-pass Wordle duplicate-letter evaluation.
     * Pass 1 marks exact matches and counts remaining target characters;
     * Pass 2 marks present-elsewhere only up to the remaining count.
     */
    fun evaluateGuess(targetRaw: String, guessRaw: String): List<KeyMark> {
        val target = TurkishText.trNormalize(targetRaw)
        val guess = TurkishText.trNormalize(guessRaw)
        if (target.length != WORD_LEN || guess.length != WORD_LEN) return List(WORD_LEN) { KeyMark.ABSENT }
        val result = Array(WORD_LEN) { KeyMark.ABSENT }
        val remaining = HashMap<Char, Int>()
        for (i in 0 until WORD_LEN) {
            if (guess[i] == target[i]) {
                result[i] = KeyMark.EXACT
            } else {
                val t = target[i]
                remaining[t] = (remaining[t] ?: 0) + 1
            }
        }
        for (i in 0 until WORD_LEN) {
            if (result[i] == KeyMark.EXACT) continue
            val g = guess[i]
            val left = remaining[g] ?: 0
            if (left > 0) {
                result[i] = KeyMark.PRESENT
                remaining[g] = left - 1
            }
        }
        return result.toList()
    }

    fun encodeMarks(marks: List<KeyMark>): String = buildString(marks.size) {
        marks.forEach { m ->
            append(
                when (m) {
                    KeyMark.EXACT -> 'E'
                    KeyMark.PRESENT -> 'P'
                    else -> 'A'
                },
            )
        }
    }

    fun decodeMarks(raw: String): List<KeyMark> = raw.take(WORD_LEN).map { c ->
        when (c) {
            'E' -> KeyMark.EXACT
            'P' -> KeyMark.PRESENT
            else -> KeyMark.ABSENT
        }
    }

    private data class PlayerBoard(
        val guesses: List<String> = emptyList(),
        val patterns: List<String> = emptyList(),
        val solved: Boolean = false,
        val done: Boolean = false,
        val finishedAt: Long = 0L,
    )

    private data class State(
        val mode: String,              // "race" | "duel"
        val target: String,            // 5-letter uppercase Turkish (empty while setter hasn't chosen in duel)
        val setterUid: String,         // only used in "duel"
        val roundStartedAt: Long,
        val roundOver: Boolean,
        val roundWinner: String?,
        val boards: Map<String, PlayerBoard>,
    )

    private fun decode(raw: String): State {
        val o = GameJson.parseObject(raw)
        val boardsObj = o.obj("boards")
        val boards = boardsObj.entries.associate { (uid, el) ->
            val bo = (el as? JsonObject) ?: JsonObject(emptyMap())
            uid to PlayerBoard(
                guesses = bo.arr("g").strList(),
                patterns = bo.arr("p").strList(),
                solved = bo.bool("s"),
                done = bo.bool("d"),
                finishedAt = bo.long("f"),
            )
        }
        return State(
            mode = o.str("mode", "race"),
            target = o.str("target"),
            setterUid = o.str("setter"),
            roundStartedAt = o.long("startedAt"),
            roundOver = o.bool("roundOver"),
            roundWinner = o.str("roundWinner").ifBlank { null },
            boards = boards,
        )
    }

    private fun encode(st: State): String = buildJsonObject {
        put("mode", st.mode)
        put("target", st.target)
        put("setter", st.setterUid)
        put("startedAt", st.roundStartedAt)
        put("roundOver", st.roundOver)
        if (st.roundWinner != null) put("roundWinner", st.roundWinner)
        put(
            "boards",
            buildJsonObject {
                st.boards.forEach { (uid, b) ->
                    put(
                        uid,
                        buildJsonObject {
                            put("g", buildJsonArray { b.guesses.forEach { add(JsonPrimitive(it)) } })
                            put("p", buildJsonArray { b.patterns.forEach { add(JsonPrimitive(it)) } })
                            put("s", b.solved)
                            put("d", b.done)
                            put("f", b.finishedAt)
                        },
                    )
                }
            },
        )
    }.toString()

    override fun createInitial(
        gameId: String,
        chatId: String,
        creator: ChatUser,
        opponent: ChatUser?,
        solo: Boolean,
        config: GameConfig,
        nowMs: Long,
    ): GameSession {
        val mode = if (solo || opponent == null) "race" else if (config.mode == "duel") "duel" else "race"
        val rounds = if (mode == "duel") config.maxRounds.coerceIn(2, 4).let { if (it % 2 == 0) it else it + 1 } else config.maxRounds.coerceIn(1, 5)
        val target = if (mode == "race") TurkishWords.pickTarget5("$gameId:1") else ""
        val creatorPlayer = GamePlayer(creator.uid, creator.name, score = 0, joinedAt = nowMs, ready = true)
        val state = State(
            mode = mode,
            target = target,
            setterUid = if (mode == "duel") creator.uid else "",
            roundStartedAt = nowMs,
            roundOver = false,
            roundWinner = null,
            boards = mapOf(creator.uid to PlayerBoard()),
        )
        return GameSession(
            id = gameId,
            chatId = chatId,
            kind = GameKind.WORD_DUEL,
            status = if (solo) GameStatus.PLAYING else GameStatus.WAITING,
            createdBy = creator.uid,
            players = mapOf(creator.uid to creatorPlayer),
            order = listOf(creator.uid),
            turn = if (mode == "duel") creator.uid else null,
            round = 1,
            maxRounds = rounds,
            turnSeconds = 0,
            stateJson = encode(state),
            summary = if (solo) "5 harfli Türkçe kelimeyi bul" else "${creator.name.substringBefore(' ')} davet gönderdi",
            createdAt = nowMs,
            updatedAt = nowMs,
            expireAt = nowMs + GAME_TTL_MS,
        )
    }

    override fun join(session: GameSession, player: ChatUser, nowMs: Long): GameSession? {
        val st = decode(session.stateJson)
        if (player.uid in session.players) return null
        if (session.status != GameStatus.WAITING && !(session.status == GameStatus.PLAYING && st.mode == "race" && session.players.size < 6)) return null
        if (st.mode == "duel" && session.players.size >= 2) return null
        val updatedPlayers = session.players + (player.uid to GamePlayer(player.uid, player.name, joinedAt = nowMs, ready = true))
        val updatedOrder = if (player.uid in session.order) session.order else session.order + player.uid
        val updatedState = st.copy(
            roundStartedAt = if (session.status == GameStatus.WAITING) nowMs else st.roundStartedAt,
            boards = st.boards + (player.uid to PlayerBoard()),
        )
        val summary = if (st.mode == "duel") {
            val setter = updatedPlayers[st.setterUid]?.name?.substringBefore(' ').orEmpty()
            "$setter gizli kelime seçiyor"
        } else {
            "Tur ${session.round}/${session.maxRounds} · Yarış başladı!"
        }
        return session.copy(
            status = GameStatus.PLAYING,
            players = updatedPlayers,
            order = updatedOrder,
            turn = if (st.mode == "duel") st.setterUid else null,
            stateJson = encode(updatedState),
            summary = summary,
            updatedAt = nowMs,
        )
    }

    override fun applyAction(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession? {
        GameRulesHelper.handleCommonActions(session, uid, action, nowMs)?.let { return it }
        if (session.status != GameStatus.PLAYING || uid !in session.players) return null
        val st = decode(session.stateJson)

        return when (action.type) {
            "set_word" -> {
                if (st.mode != "duel" || st.setterUid != uid || st.target.isNotEmpty()) return null
                val word = TurkishText.trNormalize(action.payload)
                if (!TurkishWords.isValidWord5(word)) return null
                val guesser = session.order.firstOrNull { it != uid } ?: return null
                val nextState = st.copy(target = word, roundStartedAt = nowMs)
                val guesserName = session.players[guesser]?.name?.substringBefore(' ').orEmpty()
                session.copy(
                    turn = guesser,
                    commits = action.clientHash?.let { session.commits + (uid to it) } ?: session.commits,
                    stateJson = encode(nextState),
                    summary = "Tur ${session.round}/${session.maxRounds} · $guesserName tahmin ediyor",
                    updatedAt = nowMs,
                )
            }
            "guess" -> {
                if (st.roundOver || st.target.length != WORD_LEN) return null
                if (st.mode == "duel" && st.setterUid == uid) return null
                val board = st.boards[uid] ?: PlayerBoard()
                if (board.done || board.guesses.size >= MAX_TRIES) return null
                val guess = TurkishText.trNormalize(action.payload)
                if (!TurkishWords.isValidWord5(guess)) return null
                val marks = evaluateGuess(st.target, guess)
                val solved = marks.all { it == KeyMark.EXACT }
                val newGuesses = board.guesses + guess
                val newPatterns = board.patterns + encodeMarks(marks)
                val done = solved || newGuesses.size >= MAX_TRIES
                val updatedBoard = board.copy(
                    guesses = newGuesses,
                    patterns = newPatterns,
                    solved = solved,
                    done = done,
                    finishedAt = if (done) nowMs else 0L,
                )
                val nextBoards = st.boards + (uid to updatedBoard)
                val participants = if (st.mode == "duel") session.order.filter { it != st.setterUid } else session.order
                val allDone = participants.all { p -> nextBoards[p]?.done == true }
                if (!allDone) {
                    val solverCount = participants.count { nextBoards[it]?.solved == true }
                    val summary = if (solverCount > 0) {
                        "Tur ${session.round}/${session.maxRounds} · $solverCount kişi buldu!"
                    } else {
                        "Tur ${session.round}/${session.maxRounds} · Tahminler sürüyor"
                    }
                    return session.copy(
                        stateJson = encode(st.copy(boards = nextBoards)),
                        summary = summary,
                        updatedAt = nowMs,
                    )
                }
                // Round finished! Compute round winner & scores.
                val roundWinner: String? = if (st.mode == "duel") {
                    val guesser = participants.firstOrNull()
                    if (guesser != null && nextBoards[guesser]?.solved == true) guesser else st.setterUid
                } else {
                    participants
                        .mapNotNull { pid -> nextBoards[pid]?.takeIf { it.solved }?.let { pid to it } }
                        .minWithOrNull(compareBy<Pair<String, PlayerBoard>> { it.second.guesses.size }.thenBy { it.second.finishedAt })
                        ?.first
                }
                val updatedPlayers = if (roundWinner != null && roundWinner in session.players) {
                    val wp = session.players.getValue(roundWinner)
                    session.players + (roundWinner to wp.copy(score = wp.score + 1))
                } else {
                    session.players
                }
                val isLastRound = session.round >= session.maxRounds
                val finalWinner = if (isLastRound) {
                    val maxScore = updatedPlayers.values.maxOfOrNull { it.score } ?: 0
                    val leaders = updatedPlayers.values.filter { it.score == maxScore }
                    if (maxScore > 0 && leaders.size == 1) leaders.first().uid else null
                } else null
                val nextState = st.copy(
                    boards = nextBoards,
                    roundOver = true,
                    roundWinner = roundWinner,
                )
                val summary = if (isLastRound) {
                    if (finalWinner != null) {
                        "${updatedPlayers[finalWinner]?.name?.substringBefore(' ')} kazandı · ${st.target}"
                    } else if (session.players.size == 1) {
                        if (solved) "${newGuesses.size}/6 denemede buldun · ${st.target}" else "Kelime: ${st.target}"
                    } else {
                        "Berabere · ${st.target}"
                    }
                } else {
                    val rName = roundWinner?.let { updatedPlayers[it]?.name?.substringBefore(' ') }
                    if (rName != null) "Tur ${session.round}: $rName (${st.target})" else "Tur ${session.round} bitti (${st.target})"
                }
                session.copy(
                    status = if (isLastRound) GameStatus.FINISHED else GameStatus.PLAYING,
                    players = updatedPlayers,
                    turn = null,
                    winnerUid = finalWinner,
                    stateJson = encode(nextState),
                    summary = summary,
                    updatedAt = nowMs,
                )
            }
            "next_round" -> {
                if (!st.roundOver || session.round >= session.maxRounds) return null
                val nextRound = session.round + 1
                val nextSetter = if (st.mode == "duel") {
                    GameRulesHelper.nextInOrder(session.order, st.setterUid) ?: session.createdBy
                } else ""
                val nextTarget = if (st.mode == "race") TurkishWords.pickTarget5("${session.id}:$nextRound") else ""
                val resetBoards = session.order.associateWith { PlayerBoard() }
                val nextState = State(
                    mode = st.mode,
                    target = nextTarget,
                    setterUid = nextSetter,
                    roundStartedAt = nowMs,
                    roundOver = false,
                    roundWinner = null,
                    boards = resetBoards,
                )
                session.copy(
                    round = nextRound,
                    turn = if (st.mode == "duel") nextSetter else null,
                    stateJson = encode(nextState),
                    summary = if (st.mode == "duel") {
                        "Tur $nextRound/${session.maxRounds} · ${session.players[nextSetter]?.name?.substringBefore(' ')} kelime seçiyor"
                    } else {
                        "Tur $nextRound/${session.maxRounds} · Yeni kelime!"
                    },
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
        val myBoard = st.boards[me.uid] ?: PlayerBoard()
        var draft by remember(session.round, myBoard.guesses.size, st.target) { mutableStateOf("") }
        var notice by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(notice) {
            if (notice != null) {
                delay(1800)
                notice = null
            }
        }

        // Compute highest KeyMark per Turkish letter for my keyboard.
        val keyMarks = remember(myBoard.guesses, myBoard.patterns) {
            val map = HashMap<Char, KeyMark>()
            myBoard.guesses.zip(myBoard.patterns).forEach { (word, pat) ->
                val marks = decodeMarks(pat)
                word.forEachIndexed { idx, ch ->
                    val m = marks.getOrNull(idx) ?: KeyMark.ABSENT
                    map[ch] = (map[ch] ?: KeyMark.UNUSED).merge(m)
                }
            }
            map
        }

        val waitingToSet = st.mode == "duel" && st.target.isEmpty()
        val iAmSetter = st.mode == "duel" && st.setterUid == me.uid

        Column(
            modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Scoreboard strip
            if (session.players.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceVariant)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    session.order.mapNotNull { session.players[it] }.forEach { p ->
                        val pb = st.boards[p.uid]
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (p.uid == me.uid) (if (en) "You" else "Sen") else p.name.substringBefore(' '),
                                color = TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${p.score}",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (p.uid != me.uid && pb != null && pb.patterns.isNotEmpty() && !waitingToSet) {
                                Spacer(Modifier.height(3.dp))
                                MiniPatternGrid(pb.patterns)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            if (waitingToSet && iAmSetter) {
                // Setter enters secret 5-letter word
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (en) "Pick a secret 5-letter Turkish word" else "Rakibin için 5 harfli gizli kelime yaz",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    WordRow(word = draft, marks = null, active = true)
                    notice?.let { msg ->
                        Spacer(Modifier.height(10.dp))
                        Text(msg, color = TextSecondary, fontSize = 13.sp)
                    }
                }
                TurkishKeyboard(
                    marks = emptyMap(),
                    enabled = true,
                    enterEnabled = draft.length == WORD_LEN,
                    onLetter = { ch -> if (draft.length < WORD_LEN) draft += ch },
                    onBackspace = { if (draft.isNotEmpty()) draft = draft.dropLast(1) },
                    onEnter = {
                        if (TurkishWords.isValidWord5(draft)) {
                            val salt = Sha256.randomSaltHex()
                            val hash = Sha256.commitHash(session.id, session.round, me.uid, draft, salt)
                            onAction(GameAction("set_word", payload = draft, clientHash = hash))
                            draft = ""
                        } else {
                            notice = if (en) "Word not in Turkish dictionary" else "Sözlükte bulunamadı"
                        }
                    },
                )
                return@Column
            }

            if (waitingToSet && !iAmSetter) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val setterName = session.players[st.setterUid]?.name?.substringBefore(' ').orEmpty()
                    Text(
                        if (en) "$setterName is choosing a 5-letter word…" else "$setterName 5 harfli gizli kelime seçiyor…",
                        color = TextSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                return@Column
            }

            // Main 6x5 grid (or spectating guesser's grid if I am the setter in duel mode)
            val viewedUid = if (iAmSetter) session.order.firstOrNull { it != me.uid } ?: me.uid else me.uid
            val viewedBoard = st.boards[viewedUid] ?: PlayerBoard()
            val canGuess = !iAmSetter && !st.roundOver && !myBoard.done && session.status == GameStatus.PLAYING

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (iAmSetter) {
                    Text(
                        (if (en) "Your secret word: " else "Gizli kelimen: ") + st.target,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                for (row in 0 until MAX_TRIES) {
                    val pastWord = viewedBoard.guesses.getOrNull(row)
                    val pastPat = viewedBoard.patterns.getOrNull(row)?.let(::decodeMarks)
                    val isDraftRow = canGuess && row == myBoard.guesses.size
                    WordRow(
                        word = pastWord ?: if (isDraftRow) draft else "",
                        marks = pastPat,
                        active = isDraftRow,
                    )
                    if (row < MAX_TRIES - 1) Spacer(Modifier.height(6.dp))
                }
                notice?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                if (st.roundOver) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        (if (en) "Word: " else "Kelime: ") + st.target,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (session.status == GameStatus.PLAYING && session.round < session.maxRounds) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Ink)
                                .clickable { onAction(GameAction("next_round")) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Text(
                                if (en) "Next round (${session.round + 1}/${session.maxRounds})" else "Sonraki tur (${session.round + 1}/${session.maxRounds})",
                                color = InkInverse,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                } else if (myBoard.done && !iAmSetter) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (en) "Waiting for other players to finish…" else "Diğer oyuncuların bitirmesi bekleniyor…",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }

            if (!iAmSetter) {
                Spacer(Modifier.height(8.dp))
                TurkishKeyboard(
                    marks = keyMarks,
                    enabled = canGuess,
                    enterEnabled = draft.length == WORD_LEN,
                    onLetter = { ch -> if (canGuess && draft.length < WORD_LEN) draft += ch },
                    onBackspace = { if (canGuess && draft.isNotEmpty()) draft = draft.dropLast(1) },
                    onEnter = {
                        if (!canGuess || draft.length != WORD_LEN) return@TurkishKeyboard
                        if (TurkishWords.isValidWord5(draft)) {
                            onAction(GameAction("guess", payload = draft))
                            draft = ""
                        } else {
                            notice = if (en) "Not in Turkish word list" else "Türkçe sözlükte bulunamadı"
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun WordRow(word: String, marks: List<KeyMark>?, active: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 5) {
            val ch = word.getOrNull(i)
            val mark = marks?.getOrNull(i)
            val bg = when (mark) {
                KeyMark.EXACT -> Ink
                KeyMark.PRESENT -> DarkSurfaceVariant
                KeyMark.ABSENT -> DarkSurface
                KeyMark.UNUSED, null -> if (ch != null) DarkSurfaceVariant else DarkSurface
            }
            val fg = when (mark) {
                KeyMark.EXACT -> InkInverse
                KeyMark.ABSENT -> TextMuted
                else -> TextPrimary
            }
            val borderW = when {
                mark == KeyMark.PRESENT -> 2.dp
                active && ch != null -> 1.5.dp
                else -> 1.dp
            }
            val borderC = when {
                mark == KeyMark.PRESENT -> Ink
                active && ch != null -> BorderHover
                else -> BorderSubtle
            }
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .border(borderW, borderC, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (ch != null) {
                    Text(
                        text = ch.toString(),
                        color = fg,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (mark == KeyMark.PRESENT) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(Ink),
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPatternGrid(patterns: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        patterns.take(6).forEach { pat ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                WordDuelEngine.decodeMarks(pat).forEach { m ->
                    val bg = when (m) {
                        KeyMark.EXACT -> Ink
                        KeyMark.PRESENT -> TextSecondary
                        else -> BorderSubtle
                    }
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.5.dp)).background(bg))
                }
            }
        }
    }
}

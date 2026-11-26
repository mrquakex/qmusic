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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.chat.ChatController
import com.qmusic.ui.chat.ChatStrings
import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.games.GameJson.arr
import com.qmusic.ui.chat.games.GameJson.int
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val HANGMAN_MAX_WRONG = 6

private data class HangmanEntry(val word: String, val hint: String)

private object HangmanPools {
    private val cities = listOf(
        "İSTANBUL", "ANKARA", "İZMİR", "ANTALYA", "BURSA", "ADANA", "KONYA", "GAZİANTEP",
        "MERSİN", "KAYSERİ", "ESKİŞEHİR", "TRABZON", "SAMSUN", "DENİZLİ", "MUĞLA", "AYDIN",
        "BALIKESİR", "SAKARYA", "TEKİRDAĞ", "EDİRNE", "ÇANAKKALE", "HATAY", "MALATYA", "ERZURUM",
        "SİVAS", "KARS", "MARDİN", "ŞANLIURFA", "DİYARBAKIR", "BOLU", "AMASYA", "SİNOP",
        "RİZE", "ARTVİN", "NEVŞEHİR", "KASTAMONU", "ISPARTA", "BURDUR", "AFYON", "MANİSA",
    ).map { HangmanEntry(it, "Şehir") }

    private val animals = listOf(
        "KAPLAN", "ASLAN", "ZÜRAFA", "PENGUEN", "YUNUS", "BALİNA", "KARTAL", "ŞAHİN",
        "BAYKUŞ", "PAPAĞAN", "KAPLUMBAĞA", "TİMSAH", "KANGURU", "PANDA", "KOALA", "TİLKİ",
        "KURT", "GEYİK", "SİNCAP", "KİRPİ", "KUNDUZ", "FLAMİNGO", "LEYLEK", "KIRLANGIÇ",
        "KELEBEK", "UĞURBÖCEĞİ", "KARINCA", "AHTAPOT", "DENİZATI", "ISTAKOZ", "ÇİTA", "LEOPAR",
        "GORİL", "ŞEMPANZE", "DEVEKUŞU", "TAVUSKUŞU", "BUKALEMUN", "ALBATROS", "SUAYGIRI", "GERGEDAN",
    ).map { HangmanEntry(it, "Hayvan") }

    private val food = listOf(
        "BAKLAVA", "MANTI", "LAHMACUN", "İSKENDER", "KÜNEFE", "BÖREK", "SİMİT", "POĞAÇA",
        "MENEMEN", "SUCUKLU", "KARNIYARIK", "İÇLİKÖFTE", "ÇİĞKÖFTE", "PİDE", "GÖZLEME", "DÖNER",
        "KEBAP", "TANTUNİ", "KOKOREÇ", "HUMUS", "CACIK", "SARMA", "DOLMA", "TARHANA",
        "MERCİMEK", "YAYLA", "SÜTLAÇ", "KAZANDİBİ", "AŞURE", "LOKUM", "HELVA", "REVANI",
        "TULUMBA", "KADAYIF", "KATMER", "HÜNKAR", "TÜRLÜ", "MÜCVER", "KISIR", "PİYAZ",
    ).map { HangmanEntry(it, "Yemek") }

    private val objects = listOf(
        "BİLGİSAYAR", "TELEFON", "KULAKLIK", "KLAVYE", "PENCERE", "SANDALYE", "KİTAPLIK", "ÇAYDANLIK",
        "BUZDOLABI", "ÇAMAŞIRLIK", "SÜPÜRGE", "ANAHTARLIK", "CÜZDAN", "ŞEMSİYE", "GÖZLÜK", "KOLTUK",
        "YASTIK", "BATTANİYE", "PERDE", "AYNA", "LAMBA", "PUSULA", "DÜRBÜN", "MİKROSKOP",
        "TELESKOP", "TERMOS", "ÇANTA", "BAVUL", "DEFTER", "KALEMLİK", "CETVEL", "MAKAS",
        "ZIMBA", "TORNAVİDA", "ÇEKİÇ", "MERDİVEN", "BİSİKLET", "KAYKAY", "GİTAR", "PİYANO",
    ).map { HangmanEntry(it, "Eşya") }

    private val all = cities + animals + food + objects

    fun pick(category: String, seed: String): HangmanEntry {
        val list = when (category) {
            "cities" -> cities
            "animals" -> animals
            "food" -> food
            "objects" -> objects
            else -> all
        }
        return list[Sha256.deterministicIndex(seed, list.size)]
    }
}

object HangmanEngine : GameEngine {
    override val kind: GameKind = GameKind.HANGMAN

    override fun title(s: ChatStrings): String = if (s.english) "Hangman" else "Adam Asmaca"
    override fun subtitle(s: ChatStrings): String =
        if (s.english) "Guess the Turkish word before 6 mistakes" else "6 hata yapmadan gizli kelimeyi bul"

    override fun rules(s: ChatStrings): String = if (s.english) {
        "Guess letters one by one using the Turkish keyboard. Each wrong letter draws one part of the figure (6 mistakes max). In Custom mode, players take turns setting a secret word for their opponent."
    } else {
        "Türkçe klavyeden sırayla harf seç. Her yanlış harfte çizim bir parça ilerler (en fazla 6 hata). Özel Kelime modunda oyuncular sırayla birbirine gizli kelime belirler."
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
        val mode = if (config.mode == "custom" && !solo && opponent != null) "custom" else "pool"
        val category = config.extra.ifBlank { "mixed" }
        val maxRounds = config.maxRounds.coerceIn(1, 5)
        val players = linkedMapOf(
            creator.uid to GamePlayer(creator.uid, creator.name, score = 0, joinedAt = nowMs, ready = true),
        )
        val order = mutableListOf(creator.uid)
        val status: GameStatus
        if (solo) {
            status = GameStatus.PLAYING
        } else if (opponent != null) {
            players[opponent.uid] = GamePlayer(opponent.uid, opponent.name, score = 0, joinedAt = nowMs, ready = true)
            order.add(opponent.uid)
            status = GameStatus.PLAYING
        } else {
            status = GameStatus.WAITING
        }

        val stateJson = if (mode == "custom") {
            encodeState(
                mode = mode,
                category = category,
                phase = "setup",
                setterUid = creator.uid,
                word = "",
                hint = "",
                guessed = emptyList(),
                wrong = 0,
                roundOutcome = "",
            )
        } else {
            val entry = HangmanPools.pick(category, "$gameId|r1|$category")
            encodeState(
                mode = mode,
                category = category,
                phase = "guess",
                setterUid = "",
                word = entry.word,
                hint = entry.hint,
                guessed = emptyList(),
                wrong = 0,
                roundOutcome = "",
            )
        }
        val firstTurn = if (mode == "custom") creator.uid else order.first()
        val summary = when {
            status == GameStatus.WAITING -> "Oyuncu bekleniyor"
            mode == "custom" -> "${creator.name.substringBefore(' ')} gizli kelime seçiyor"
            else -> "Tur 1/$maxRounds · ${HangmanPools.pick(category, "$gameId|r1|$category").hint}"
        }
        return GameSession(
            id = gameId,
            chatId = chatId,
            kind = GameKind.HANGMAN,
            status = status,
            createdBy = creator.uid,
            players = players,
            order = order,
            turn = if (status == GameStatus.PLAYING) firstTurn else null,
            round = 1,
            maxRounds = maxRounds,
            turnSeconds = 0,
            version = 1L,
            stateJson = stateJson,
            summary = summary,
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
        return session.copy(
            status = GameStatus.PLAYING,
            players = players,
            order = order,
            turn = order.first(),
            summary = "Tur ${session.round}/${session.maxRounds} başladı",
            updatedAt = nowMs,
        )
    }

    override fun applyAction(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession? {
        GameRulesHelper.handleCommonActions(session, uid, action, nowMs)?.let { return it }
        if (session.status != GameStatus.PLAYING || uid !in session.players) return null

        val st = GameJson.parseObject(session.stateJson)
        val mode = st.str("mode", "pool")
        val category = st.str("category", "mixed")
        val phase = st.str("phase", "guess")
        val setterUid = st.str("setter")
        val word = st.str("word")
        val hint = st.str("hint")
        val guessed = st.arr("guessed").strList()
        val wrong = st.int("wrong", 0)

        when (action.type) {
            "set_word" -> {
                if (mode != "custom" || phase != "setup" || uid != setterUid) return null
                val parts = action.payload.split('|', limit = 2)
                val cleanWord = TurkishText.trNormalize(parts[0])
                if (cleanWord.length !in 3..14 || !TurkishText.isTurkishWord(cleanWord)) return null
                val cleanHint = parts.getOrNull(1)?.trim()?.take(40).orEmpty()
                val guesser = session.order.firstOrNull { it != setterUid } ?: return null
                val guesserName = session.players[guesser]?.name?.substringBefore(' ').orEmpty()
                return session.copy(
                    turn = guesser,
                    stateJson = encodeState(mode, category, "guess", setterUid, cleanWord, cleanHint, emptyList(), 0, ""),
                    summary = "Tur ${session.round}/${session.maxRounds} · $guesserName tahmin ediyor",
                    updatedAt = nowMs,
                )
            }
            "guess" -> {
                if (phase != "guess" || word.isEmpty()) return null
                if (session.turn != null && session.turn != uid) return null
                if (mode == "custom" && uid == setterUid) return null
                val letter = TurkishText.trUpper(action.payload.trim()).take(1)
                if (letter.length != 1 || !TurkishText.isTurkishLetter(letter[0])) return null
                if (letter in guessed) return null

                val nextGuessed = guessed + letter
                val hit = word.contains(letter[0])
                val nextWrong = if (hit) wrong else wrong + 1
                val solved = word.all { ch -> nextGuessed.any { it.length == 1 && it[0] == ch } }
                val failed = !solved && nextWrong >= HANGMAN_MAX_WRONG

                if (solved || failed) {
                    val players = session.players.toMutableMap()
                    val roundWinnerUid = when {
                        solved -> uid
                        mode == "custom" && setterUid.isNotEmpty() -> setterUid
                        else -> null
                    }
                    if (roundWinnerUid != null) {
                        val p = players[roundWinnerUid]
                        if (p != null) players[roundWinnerUid] = p.copy(score = p.score + 1)
                    }
                    val outcome = if (solved) "solved:$word" else "failed:$word"
                    val isLast = session.round >= session.maxRounds
                    val nextStatus = if (isLast) GameStatus.FINISHED else GameStatus.PLAYING
                    val finalWinner = if (isLast) pickOverallWinner(players) else null
                    val sum = when {
                        isLast && finalWinner != null -> "${players[finalWinner]?.name?.substringBefore(' ')} kazandı · $word"
                        isLast -> "Berabere · $word"
                        solved -> "${players[uid]?.name?.substringBefore(' ')} bildi: $word"
                        else -> "Bulunamadı: $word"
                    }
                    return session.copy(
                        status = nextStatus,
                        players = players,
                        turn = null,
                        winnerUid = finalWinner,
                        stateJson = encodeState(mode, category, "round_over", setterUid, word, hint, nextGuessed, nextWrong, outcome),
                        summary = sum,
                        updatedAt = nowMs,
                    )
                }

                val nextTurn = if (mode == "custom" || session.order.size <= 1) {
                    uid
                } else {
                    // Keep turn on hit, pass turn on miss
                    if (hit) uid else GameRulesHelper.nextInOrder(session.order, uid)
                }
                val nextName = nextTurn?.let { session.players[it]?.name?.substringBefore(' ') }.orEmpty()
                return session.copy(
                    turn = nextTurn,
                    stateJson = encodeState(mode, category, "guess", setterUid, word, hint, nextGuessed, nextWrong, ""),
                    summary = "Hata $nextWrong/$HANGMAN_MAX_WRONG · Sıra $nextName",
                    updatedAt = nowMs,
                )
            }
            "next_round" -> {
                if (phase != "round_over" || session.round >= session.maxRounds) return null
                val nextRound = session.round + 1
                if (mode == "custom") {
                    val nextSetter = session.order[(nextRound - 1) % session.order.size]
                    val setterName = session.players[nextSetter]?.name?.substringBefore(' ').orEmpty()
                    return session.copy(
                        round = nextRound,
                        turn = nextSetter,
                        stateJson = encodeState(mode, category, "setup", nextSetter, "", "", emptyList(), 0, ""),
                        summary = "Tur $nextRound/${session.maxRounds} · $setterName kelime seçiyor",
                        updatedAt = nowMs,
                    )
                } else {
                    val entry = HangmanPools.pick(category, "${session.id}|r$nextRound|$category")
                    val first = session.order[(nextRound - 1) % session.order.size]
                    return session.copy(
                        round = nextRound,
                        turn = first,
                        stateJson = encodeState(mode, category, "guess", "", entry.word, entry.hint, emptyList(), 0, ""),
                        summary = "Tur $nextRound/${session.maxRounds} · ${entry.hint}",
                        updatedAt = nowMs,
                    )
                }
            }
            else -> return null
        }
    }

    private fun pickOverallWinner(players: Map<String, GamePlayer>): String? {
        if (players.size <= 1) return players.values.firstOrNull()?.takeIf { it.score > 0 }?.uid
        val sorted = players.values.sortedByDescending { it.score }
        return if (sorted.size >= 2 && sorted[0].score > sorted[1].score) sorted[0].uid else null
    }

    private fun encodeState(
        mode: String,
        category: String,
        phase: String,
        setterUid: String,
        word: String,
        hint: String,
        guessed: List<String>,
        wrong: Int,
        roundOutcome: String,
    ): String = buildJsonObject {
        put("mode", mode)
        put("category", category)
        put("phase", phase)
        put("setter", setterUid)
        put("word", word)
        put("hint", hint)
        put("guessed", buildJsonArray { guessed.forEach { add(JsonPrimitive(it)) } })
        put("wrong", wrong)
        put("outcome", roundOutcome)
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
        val mode = st.str("mode", "pool")
        val phase = st.str("phase", "guess")
        val setterUid = st.str("setter")
        val word = st.str("word")
        val hint = st.str("hint")
        val guessed = st.arr("guessed").strList()
        val wrong = st.int("wrong", 0)
        val tr = !s.english

        Column(
            modifier = modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Scores bar
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                session.order.forEach { uid ->
                    val p = session.players[uid] ?: return@forEach
                    val active = session.turn == uid && session.status == GameStatus.PLAYING
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) Ink else DarkSurface)
                            .border(1.dp, if (active) Ink else BorderSubtle, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (uid == me.uid) s.you else p.name.substringBefore(' '),
                            color = if (active) InkInverse else TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            p.score.toString(),
                            color = if (active) InkInverse else TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (phase == "setup") {
                if (setterUid == me.uid) {
                    var customWord by remember(session.round) { mutableStateOf("") }
                    var customHint by remember(session.round) { mutableStateOf("") }
                    val clean = TurkishText.trNormalize(customWord)
                    val valid = clean.length in 3..14 && TurkishText.isTurkishWord(clean)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(DarkSurface)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(if (tr) "Rakibin için gizli kelime belirle (3-14 harf)" else "Set a secret Turkish word (3-14 letters)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurfaceVariant)
                                .border(1.dp, BorderHover, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                        ) {
                            if (customWord.isEmpty()) Text(if (tr) "Örn: KELEBEK" else "e.g. KELEBEK", color = TextMuted, fontSize = 15.sp)
                            BasicTextField(
                                value = customWord,
                                onValueChange = { customWord = it.take(14) },
                                singleLine = true,
                                textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                                cursorBrush = SolidColor(Ink),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurfaceVariant)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            if (customHint.isEmpty()) Text(if (tr) "İpucu (isteğe bağlı)" else "Hint (optional)", color = TextMuted, fontSize = 14.sp)
                            BasicTextField(
                                value = customHint,
                                onValueChange = { customHint = it.take(40) },
                                singleLine = true,
                                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                                cursorBrush = SolidColor(Ink),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (valid) Ink else DarkSurfaceVariant)
                                .clickable(enabled = valid) {
                                    onAction(GameAction("set_word", "$clean|${customHint.trim()}"))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (tr) "Kelimeyi Kilitle" else "Lock Word", color = if (valid) InkInverse else TextMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            if (tr) "Rakibin gizli kelime seçiyor..." else "Opponent is choosing a word...",
                            color = TextSecondary,
                            fontSize = 15.sp,
                        )
                    }
                }
                return@Column
            }

            // Gallows canvas + word display
            val amber = GameColors.Amber
            val crimson = GameColors.Crimson
            val emerald = GameColors.Emerald
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hint.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(amber.copy(alpha = 0.16f))
                                .border(1.dp, amber.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(hint, color = amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        if (tr) "Hata: $wrong / $HANGMAN_MAX_WRONG" else "Mistakes: $wrong / $HANGMAN_MAX_WRONG",
                        color = if (wrong >= 4) crimson else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (wrong >= 4) FontWeight.Bold else FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height(10.dp))
                HangmanCanvas(wrong = wrong, modifier = Modifier.size(168.dp))
                Spacer(Modifier.height(14.dp))

                // Masked word letters
                val revealAll = phase == "round_over" || session.status == GameStatus.FINISHED || (mode == "custom" && setterUid == me.uid)
                val displayWord = word.map { ch ->
                    if (revealAll || guessed.any { it.length == 1 && it[0] == ch }) ch.toString() else "_"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                ) {
                    displayWord.forEach { slot ->
                        val revealed = slot != "_"
                        Box(
                            modifier = Modifier
                                .width(if (word.length > 9) 24.dp else 30.dp)
                                .height(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (revealed) emerald.copy(alpha = 0.16f) else DarkSurface)
                                .border(1.2.dp, if (revealed) emerald else BorderSubtle, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                slot,
                                color = if (revealed) emerald else TextMuted,
                                fontSize = if (word.length > 9) 15.sp else 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                    }
                }

                if (phase == "round_over" && session.status == GameStatus.PLAYING) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(amber)
                            .clickable { onAction(GameAction("next_round")) }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    ) {
                        Text(if (tr) "Sonraki Tur" else "Next Round", color = GameColors.onAccent(amber), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (phase == "guess" && session.status == GameStatus.PLAYING) {
                val canGuess = (session.turn == null || session.turn == me.uid) && !(mode == "custom" && setterUid == me.uid)
                val marks = remember(guessed, word) {
                    buildMap<Char, KeyMark> {
                        guessed.forEach { g ->
                            val c = g.firstOrNull() ?: return@forEach
                            put(c, if (word.contains(c)) KeyMark.EXACT else KeyMark.ABSENT)
                        }
                    }
                }
                if (!canGuess) {
                    Text(
                        if (mode == "custom" && setterUid == me.uid) {
                            if (tr) "Rakibin kelimeni tahmin ediyor..." else "Opponent is guessing your word..."
                        } else {
                            if (tr) "Rakibin sırası..." else "Opponent's turn..."
                        },
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                TurkishKeyboard(
                    marks = marks,
                    enabled = canGuess,
                    showEnter = false,
                    onLetter = { ch ->
                        if (canGuess && ch.toString() !in guessed) {
                            onAction(GameAction("guess", ch.toString()))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HangmanCanvas(wrong: Int, modifier: Modifier = Modifier) {
    val strokeColor = if (wrong >= 4) GameColors.Crimson else GameColors.Orange
    val frameColor = GameColors.Amber
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(GameColors.Amber.copy(alpha = 0.08f))
            .border(1.2.dp, GameColors.Amber.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val baseStroke = 2.6.dp.toPx()
            val bodyStroke = 3.dp.toPx()

            // Gallows frame
            drawLine(frameColor, Offset(w * 0.12f, h * 0.92f), Offset(w * 0.62f, h * 0.92f), baseStroke, StrokeCap.Round)
            drawLine(frameColor, Offset(w * 0.28f, h * 0.92f), Offset(w * 0.28f, h * 0.08f), baseStroke, StrokeCap.Round)
            drawLine(frameColor, Offset(w * 0.28f, h * 0.08f), Offset(w * 0.72f, h * 0.08f), baseStroke, StrokeCap.Round)
            drawLine(frameColor, Offset(w * 0.28f, h * 0.24f), Offset(w * 0.44f, h * 0.08f), baseStroke, StrokeCap.Round)
            drawLine(frameColor, Offset(w * 0.72f, h * 0.08f), Offset(w * 0.72f, h * 0.22f), baseStroke, StrokeCap.Round)

            // 6 stages of stick figure
            if (wrong >= 1) {
                drawCircle(strokeColor, radius = w * 0.10f, center = Offset(w * 0.72f, h * 0.32f), style = Stroke(bodyStroke))
            }
            if (wrong >= 2) {
                drawLine(strokeColor, Offset(w * 0.72f, h * 0.42f), Offset(w * 0.72f, h * 0.68f), bodyStroke, StrokeCap.Round)
            }
            if (wrong >= 3) {
                drawLine(strokeColor, Offset(w * 0.72f, h * 0.48f), Offset(w * 0.56f, h * 0.60f), bodyStroke, StrokeCap.Round)
            }
            if (wrong >= 4) {
                drawLine(strokeColor, Offset(w * 0.72f, h * 0.48f), Offset(w * 0.88f, h * 0.60f), bodyStroke, StrokeCap.Round)
            }
            if (wrong >= 5) {
                drawLine(strokeColor, Offset(w * 0.72f, h * 0.68f), Offset(w * 0.58f, h * 0.84f), bodyStroke, StrokeCap.Round)
            }
            if (wrong >= 6) {
                drawLine(strokeColor, Offset(w * 0.72f, h * 0.68f), Offset(w * 0.86f, h * 0.84f), bodyStroke, StrokeCap.Round)
            }
        }
    }
}

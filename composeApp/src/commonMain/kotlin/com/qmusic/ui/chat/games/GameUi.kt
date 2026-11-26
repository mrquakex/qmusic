package com.qmusic.ui.chat.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qmusic.ui.PlatformBackHandler
import com.qmusic.ui.chat.ChatController
import com.qmusic.ui.chat.ChatStrings
import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.DialogEdgeToEdge
import com.qmusic.ui.chat.SharedItem
import com.qmusic.ui.chat.isGroup
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.BgSurface
import com.qmusic.ui.theme.BorderHover
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import com.qmusic.ui.theme.QMusicIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface GameSlashResult {
    data object OpenPicker : GameSlashResult
    data class QuickStart(val kind: GameKind, val extra: String = "") : GameSlashResult
}

private val SLASH_ALIASES: Map<String, GameKind> = mapOf(
    "kelime" to GameKind.WORD_DUEL,
    "wordle" to GameKind.WORD_DUEL,
    "word" to GameKind.WORD_DUEL,
    "zincir" to GameKind.WORD_CHAIN,
    "chain" to GameKind.WORD_CHAIN,
    "xox" to GameKind.XOX,
    "tic" to GameKind.XOX,
    "adam" to GameKind.HANGMAN,
    "asmaca" to GameKind.HANGMAN,
    "hangman" to GameKind.HANGMAN,
    "dortlu" to GameKind.CONNECT4,
    "dörtlü" to GameKind.CONNECT4,
    "connect4" to GameKind.CONNECT4,
    "tkm" to GameKind.RPS,
    "rps" to GameKind.RPS,
    "tas" to GameKind.RPS,
    "taş" to GameKind.RPS,
    "isim" to GameKind.NAME_CITY,
    "sehir" to GameKind.NAME_CITY,
    "şehir" to GameKind.NAME_CITY,
    "sayi" to GameKind.MASTERMIND,
    "sayı" to GameKind.MASTERMIND,
    "mastermind" to GameKind.MASTERMIND,
    "sarki" to GameKind.SONG_QUIZ,
    "şarkı" to GameKind.SONG_QUIZ,
    "muzik" to GameKind.SONG_QUIZ,
    "müzik" to GameKind.SONG_QUIZ,
    "song" to GameKind.SONG_QUIZ,
    "amiral" to GameKind.BATTLESHIP,
    "battleship" to GameKind.BATTLESHIP,
    "tabu" to GameKind.TABOO,
    "taboo" to GameKind.TABOO,
    "bilgi" to GameKind.TRIVIA,
    "trivia" to GameKind.TRIVIA,
    "quiz" to GameKind.TRIVIA,
)

/** Parses /oyna, /play, and per-game shortcuts typed in the chat composer. */
fun parseGameCommand(raw: String): GameSlashResult? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("/")) return null
    val parts = trimmed.removePrefix("/").split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val cmd = TurkishText.trLower(parts[0])
    if (cmd == "oyna" || cmd == "play" || cmd == "oyun") {
        if (parts.size == 1) return GameSlashResult.OpenPicker
        val sub = TurkishText.trLower(parts[1])
        val kind = SLASH_ALIASES[sub]
        return if (kind != null && GameRegistry.of(kind) != null) {
            GameSlashResult.QuickStart(kind, parts.drop(2).joinToString(" "))
        } else {
            GameSlashResult.OpenPicker
        }
    }
    val direct = SLASH_ALIASES[cmd] ?: return null
    return if (GameRegistry.of(direct) != null) {
        GameSlashResult.QuickStart(direct, parts.drop(1).joinToString(" "))
    } else {
        GameSlashResult.OpenPicker
    }
}

private fun slashTagOf(kind: GameKind): String = when (kind) {
    GameKind.WORD_DUEL -> "/kelime"
    GameKind.WORD_CHAIN -> "/zincir"
    GameKind.XOX -> "/xox"
    GameKind.HANGMAN -> "/adam"
    GameKind.CONNECT4 -> "/dortlu"
    GameKind.RPS -> "/tkm"
    GameKind.NAME_CITY -> "/isim"
    GameKind.MASTERMIND -> "/sayi"
    GameKind.SONG_QUIZ -> "/sarki"
    GameKind.BATTLESHIP -> "/amiral"
    GameKind.TABOO -> "/tabu"
    GameKind.TRIVIA -> "/bilgi"
}

private fun isAvailableFor(kind: GameKind, isGroup: Boolean, isSelf: Boolean): Boolean = when {
    isSelf -> kind.soloReady
    isGroup -> kind.groupReady
    else -> true
}

/** Monochrome slash-command popup rendered above the chat input box when typing '/'. */
@Composable
fun GameSlashCommands(
    query: String,
    isGroup: Boolean,
    isSelf: Boolean,
    s: ChatStrings,
    onOpenPicker: () -> Unit,
    onQuickStart: (GameKind) -> Unit,
) {
    val q = TurkishText.trLower(query.trim())
    val showMain = "/oyna".startsWith(q) || "/play".startsWith(q) || "/oyun".startsWith(q) || q == "/"
    val matchingGames = GameRegistry.implemented.filter { kind ->
        if (!isAvailableFor(kind, isGroup, isSelf)) return@filter false
        val tag = slashTagOf(kind)
        val engine = GameRegistry.of(kind) ?: return@filter false
        val titleLower = TurkishText.trLower(engine.title(s))
        val clean = q.removePrefix("/oyna ").removePrefix("/play ").removePrefix("/")
        q == "/" || tag.startsWith(q) || (clean.isNotEmpty() && (tag.removePrefix("/").startsWith(clean) || titleLower.contains(clean)))
    }.take(5)

    if (!showMain && matchingGames.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurfaceVariant)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .padding(vertical = 4.dp),
    ) {
        if (showMain) {
            SlashGameRow(
                command = if (s.english) "/play" else "/oyna",
                label = s.cmdPlay,
                onClick = onOpenPicker,
            )
        }
        matchingGames.forEach { kind ->
            val engine = GameRegistry.of(kind) ?: return@forEach
            SlashGameRow(
                command = slashTagOf(kind),
                label = engine.title(s),
                kind = kind,
                onClick = { onQuickStart(kind) },
            )
        }
    }
}

@Composable
private fun SlashGameRow(command: String, label: String, kind: GameKind? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (kind != null) {
            GameEmblem(kind, modifier = Modifier.size(18.dp))
        } else {
            Icon(QMusicIcons.Dice, contentDescription = null, tint = GameColors.Indigo, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            command,
            color = kind?.palette()?.primary ?: TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** Monochrome banner shown below the top bar while one or more games are active in the chat. */
@Composable
fun ActiveGameBanner(
    games: Map<String, GameSession>,
    myUid: String,
    nowMs: Long,
    s: ChatStrings,
    onOpen: (String) -> Unit,
) {
    val active = remember(games, nowMs) {
        games.values
            .filter { it.isActive(nowMs) }
            .sortedWith(
                compareByDescending<GameSession> { it.turn == myUid }
                    .thenByDescending { it.status == GameStatus.WAITING && myUid !in it.players }
                    .thenByDescending { it.updatedAt },
            )
    }
    val top = active.firstOrNull() ?: return
    val engine = GameRegistry.of(top.kind)
    val pal = top.kind.palette()
    val myTurn = top.status == GameStatus.PLAYING && top.turn == myUid
    val canJoin = top.status == GameStatus.WAITING && myUid !in top.players

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .clickable { onOpen(top.id) }
            .padding(end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.5.dp).height(50.dp).background(pal.primary))
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(pal.softBg)
                .border(1.dp, pal.softBorder, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            GameEmblem(top.kind, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    engine?.title(s) ?: top.kind.wire,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active.size > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "+${active.size - 1} ${s.gameActiveGames}",
                        color = pal.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                when {
                    canJoin -> s.gameInvite
                    myTurn -> s.gameYourTurn + if (top.summary.isNotBlank()) " · ${top.summary}" else ""
                    else -> top.summary.ifBlank { s.gameWaiting }
                },
                color = if (myTurn || canJoin) pal.primary else TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (myTurn || canJoin) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (myTurn || canJoin) pal.primary else pal.softBg)
                .border(1.dp, if (myTurn || canJoin) pal.primary else pal.softBorder, RoundedCornerShape(999.dp))
                .padding(horizontal = 13.dp, vertical = 5.dp),
        ) {
            Text(
                if (canJoin) s.gameJoin else s.gameOpen,
                color = if (myTurn || canJoin) pal.onPrimary else pal.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(BorderSubtle))
}

/** Live game card rendered inside a chat bubble with each game's custom emblem and signature accent. */
@Composable
fun GameMessageCard(
    ref: ChatGameRef,
    session: GameSession?,
    myUid: String,
    mine: Boolean,
    nowMs: Long,
    s: ChatStrings,
    onJoin: () -> Unit,
    onOpen: () -> Unit,
) {
    val engine = GameRegistry.of(ref.kind)
    val pal = ref.kind.palette()
    val fg = if (mine) InkInverse else TextPrimary
    val fgSoft = if (mine) InkInverse.copy(alpha = 0.72f) else TextSecondary
    val cardBg = if (mine) InkInverse.copy(alpha = 0.08f) else DarkSurface
    val cardBorder = if (mine) pal.primary.copy(alpha = 0.50f) else pal.softBorder
    val badgeBg = if (mine) InkInverse.copy(alpha = 0.14f) else pal.softBg
    val ctaBg = pal.primary
    val ctaFg = pal.onPrimary

    val expired = session?.isExpired(nowMs) == true
    val statusLabel = when {
        session == null -> s.gameLoading
        session.status == GameStatus.CANCELLED -> s.gameCancelled
        session.status == GameStatus.FINISHED -> s.gameFinished
        expired -> s.gameExpired
        session.status == GameStatus.WAITING -> s.gameWaiting
        session.turn == myUid -> s.gameYourTurn
        else -> {
            val turnName = session.turn?.let { session.players[it]?.name?.substringBefore(' ') }
            if (turnName != null) (if (s.english) "$turnName's turn" else "Sıra $turnName") else s.gameOpen
        }
    }
    val canJoin = session != null && !expired && session.status == GameStatus.WAITING && myUid !in session.players
    val finished = session == null || expired || session.status == GameStatus.FINISHED || session.status == GameStatus.CANCELLED

    Column(
        modifier = Modifier
            .width(264.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.2.dp, cardBorder, RoundedCornerShape(14.dp))
            .clickable(enabled = session != null) { if (canJoin) onJoin() else onOpen() },
    ) {
        // Signature colored top accent bar
        Box(Modifier.fillMaxWidth().height(4.dp).background(pal.primary))
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeBg)
                        .border(1.dp, pal.softBorder, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    GameEmblem(ref.kind, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        engine?.title(s) ?: ref.title.ifBlank { ref.kind.wire },
                        color = fg,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        statusLabel,
                        color = if (!finished && (session?.turn == myUid || canJoin)) pal.primary else fgSoft,
                        fontSize = 11.sp,
                        fontWeight = if (!finished && (session?.turn == myUid || canJoin)) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val summary = session?.summary?.takeIf { it.isNotBlank() }
            if (summary != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    summary,
                    color = fgSoft,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (session != null && session.players.size >= 2) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    session.order.take(3).forEach { uid ->
                        val p = session.players[uid] ?: return@forEach
                        val label = if (uid == myUid) s.you else p.name.substringBefore(' ')
                        val isWinner = session.winnerUid == uid && session.status == GameStatus.FINISHED
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isWinner) GameColors.Emerald else badgeBg)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$label ${p.score}",
                                color = if (isWinner) GameColors.onAccent(GameColors.Emerald) else fg,
                                fontSize = 11.sp,
                                fontWeight = if (isWinner) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (finished) badgeBg else ctaBg)
                    .border(1.dp, if (finished) cardBorder else ctaBg, RoundedCornerShape(10.dp))
                    .clickable(enabled = session != null) { if (canJoin) onJoin() else onOpen() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        session == null -> "..."
                        canJoin -> s.gameJoin
                        finished -> s.gameWatch
                        else -> s.gameOpen
                    },
                    color = if (finished) fg else ctaFg,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Full monochrome game picker sheet launched via /oyna or AttachPanel. */
@Composable
fun GamePickerSheet(
    controller: ChatController,
    other: ChatUser,
    me: ChatUser,
    s: ChatStrings,
    onDismiss: () -> Unit,
    onStarted: (String) -> Unit,
) {
    val isGroup = other.isGroup
    val isSelf = other.uid == me.uid
    val available = remember(isGroup, isSelf) {
        GameRegistry.implemented.filter { isAvailableFor(it, isGroup, isSelf) }
    }
    var category by remember { mutableStateOf(GameCategory.ALL) }
    val shown = remember(available, category) {
        if (category == GameCategory.ALL) available else available.filter { it.category == category }
    }
    var selected by remember(available) { mutableStateOf(available.firstOrNull() ?: GameKind.WORD_DUEL) }
    var config by remember(selected) { mutableStateOf(GameRegistry.defaultConfig(selected)) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgRoot)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        PlatformBackHandler(enabled = true) { onDismiss() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(QMusicIcons.Close, contentDescription = s.closeLabel, tint = Ink)
                }
                Icon(QMusicIcons.Dice, contentDescription = null, tint = Ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(s.gamePickTitle, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (isSelf) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(DarkSurfaceVariant)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(s.gameSolo, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(BorderSubtle))

            // Category chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    GameCategory.ALL to s.gameCatAll,
                    GameCategory.WORD to s.gameCatWord,
                    GameCategory.CLASSIC to s.gameCatClassic,
                    GameCategory.PARTY to s.gameCatParty,
                ).forEach { (cat, label) ->
                    val on = category == cat
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (on) Ink else DarkSurface)
                            .border(1.dp, if (on) Ink else BorderSubtle, RoundedCornerShape(999.dp))
                            .clickable {
                                category = cat
                                val nextList = if (cat == GameCategory.ALL) available else available.filter { it.category == cat }
                                if (selected !in nextList && nextList.isNotEmpty()) selected = nextList.first()
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(label, color = if (on) InkInverse else TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // 2-column distinct colorful game cards
            val selectedPal = selected.palette()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                shown.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { kind ->
                            val engine = GameRegistry.of(kind)
                            val pal = kind.palette()
                            val isSel = selected == kind
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isSel) pal.softBg else DarkSurface)
                                    .border(if (isSel) 1.8.dp else 1.dp, if (isSel) pal.primary else pal.softBorder.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                                    .clickable { selected = kind }
                                    .padding(13.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(pal.softBg)
                                            .border(1.dp, pal.softBorder, RoundedCornerShape(11.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        GameEmblem(kind, modifier = Modifier.size(24.dp))
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(if (isSel) pal.primary else DarkSurfaceVariant)
                                            .padding(horizontal = 7.dp, vertical = 3.dp),
                                    ) {
                                        Text(
                                            slashTagOf(kind),
                                            color = if (isSel) pal.onPrimary else pal.primary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    engine?.title(s) ?: kind.wire,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    engine?.subtitle(s).orEmpty(),
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Selected game options & accented Start button
            Box(Modifier.fillMaxWidth().height(1.dp).background(selectedPal.softBorder))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GameConfigOptions(
                    kind = selected,
                    config = config,
                    isGroup = isGroup,
                    isSelf = isSelf,
                    s = s,
                    onChange = { config = it },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (busy) DarkSurfaceVariant else selectedPal.primary)
                        .clickable(enabled = !busy) {
                            if (selected == GameKind.SONG_QUIZ) {
                                busy = true
                                scope.launch {
                                    val extra = prepareSongQuizPayload(controller, config.mode, config.maxRounds)
                                    busy = false
                                    if (extra == null) {
                                        controller.reportError(if (s.english) "Could not load songs for quiz" else "Yarışma için şarkılar yüklenemedi")
                                    } else {
                                        controller.createGame(other, config.copy(extra = extra), s) { gid ->
                                            onDismiss()
                                            onStarted(gid)
                                        }
                                    }
                                }
                            } else if (selected == GameKind.WORD_DUEL && config.mode != "duel") {
                                busy = true
                                scope.launch {
                                    val verifiedWord = TurkishDictApi.pickVerifiedTarget5()
                                    busy = false
                                    controller.createGame(other, config.copy(extra = verifiedWord), s) { gid ->
                                        onDismiss()
                                        onStarted(gid)
                                    }
                                }
                            } else {
                                controller.createGame(other, config, s) { gid ->
                                    onDismiss()
                                    onStarted(gid)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(color = selectedPal.primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Text(s.gameLoading, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text(s.gameStart, color = selectedPal.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun GameConfigOptions(
    kind: GameKind,
    config: GameConfig,
    isGroup: Boolean,
    isSelf: Boolean,
    s: ChatStrings,
    onChange: (GameConfig) -> Unit,
) {
    val tr = !s.english
    when (kind) {
        GameKind.WORD_DUEL -> {
            if (!isGroup && !isSelf) {
                OptionRow(
                    label = s.gameMode,
                    options = listOf("race" to (if (tr) "Aynı Kelime Yarışı" else "Same-Word Race"), "duel" to (if (tr) "Kur & Bul" else "Set & Guess")),
                    selected = config.mode,
                    onPick = { onChange(config.copy(mode = it)) },
                )
            }
        }
        GameKind.WORD_CHAIN -> {
            OptionRow(
                label = s.gameTime,
                options = listOf("15" to "15 sn", "20" to "20 sn", "30" to "30 sn"),
                selected = config.turnSeconds.toString(),
                onPick = { onChange(config.copy(turnSeconds = it.toIntOrNull() ?: 20)) },
            )
        }
        GameKind.XOX -> {
            OptionRow(
                label = s.gameMode,
                options = listOf("infinite" to (if (tr) "Sonsuz 3'lü" else "Infinite 3"), "classic" to (if (tr) "Klasik 3×3" else "Classic 3×3")),
                selected = config.mode,
                onPick = { onChange(config.copy(mode = it)) },
            )
            OptionRow(
                label = s.gameRounds,
                options = listOf("1" to "1", "3" to "3", "5" to "5"),
                selected = config.maxRounds.toString(),
                onPick = { onChange(config.copy(maxRounds = it.toIntOrNull() ?: 3)) },
            )
        }
        GameKind.HANGMAN -> {
            if (!isGroup && !isSelf) {
                OptionRow(
                    label = s.gameMode,
                    options = listOf("pool" to (if (tr) "Hazır Havuz" else "Word Pool"), "custom" to (if (tr) "Özel Kelime" else "Custom Word")),
                    selected = config.mode,
                    onPick = { onChange(config.copy(mode = it)) },
                )
            }
            if (config.mode != "custom") {
                OptionRow(
                    label = if (tr) "Kategori" else "Category",
                    options = listOf(
                        "mixed" to (if (tr) "Karışık" else "Mixed"),
                        "cities" to (if (tr) "Şehirler" else "Cities"),
                        "animals" to (if (tr) "Hayvanlar" else "Animals"),
                        "food" to (if (tr) "Yemekler" else "Food"),
                        "objects" to (if (tr) "Eşyalar" else "Objects"),
                    ),
                    selected = config.extra.ifBlank { "mixed" },
                    onPick = { onChange(config.copy(extra = it)) },
                )
            }
        }
        GameKind.RPS -> {
            OptionRow(
                label = s.gameRounds,
                options = listOf("3" to (if (tr) "3'lü Seri" else "Best of 3"), "5" to (if (tr) "5'li Seri" else "Best of 5")),
                selected = config.maxRounds.toString(),
                onPick = {
                    val r = it.toIntOrNull() ?: 3
                    onChange(config.copy(mode = if (r == 5) "bo5" else "bo3", maxRounds = r))
                },
            )
        }
        GameKind.NAME_CITY -> {
            OptionRow(
                label = s.gameRounds,
                options = listOf("2" to "2", "3" to "3", "5" to "5"),
                selected = config.maxRounds.toString(),
                onPick = { onChange(config.copy(maxRounds = it.toIntOrNull() ?: 3)) },
            )
            OptionRow(
                label = s.gameTime,
                options = listOf("45" to "45 sn", "60" to "60 sn", "90" to "90 sn"),
                selected = config.turnSeconds.toString(),
                onPick = { onChange(config.copy(turnSeconds = it.toIntOrNull() ?: 60)) },
            )
        }
        GameKind.SONG_QUIZ -> {
            OptionRow(
                label = if (tr) "Tarz" else "Genre",
                options = listOf(
                    "mixed" to (if (tr) "Karışık" else "Mixed"),
                    "pop" to (if (tr) "Türkçe Pop" else "Turkish Pop"),
                    "rap" to "Rap",
                    "rock" to "Rock",
                ),
                selected = config.mode,
                onPick = { onChange(config.copy(mode = it)) },
            )
            OptionRow(
                label = s.gameRounds,
                options = listOf("5" to "5", "8" to "8"),
                selected = config.maxRounds.toString(),
                onPick = { onChange(config.copy(maxRounds = it.toIntOrNull() ?: 5)) },
            )
        }
        GameKind.TABOO -> {
            OptionRow(
                label = s.gameRounds,
                options = listOf("2" to "2", "4" to "4", "6" to "6"),
                selected = config.maxRounds.toString(),
                onPick = { onChange(config.copy(maxRounds = it.toIntOrNull() ?: 4)) },
            )
        }
        GameKind.TRIVIA -> {
            OptionRow(
                label = if (tr) "Kategori" else "Category",
                options = listOf(
                    "mixed" to (if (tr) "Karışık" else "Mixed"),
                    "general" to (if (tr) "Genel" else "General"),
                    "music" to (if (tr) "Müzik" else "Music"),
                    "cinema" to (if (tr) "Sinema" else "Cinema"),
                    "science" to (if (tr) "Bilim" else "Science"),
                    "sport" to (if (tr) "Spor" else "Sport"),
                ),
                selected = config.mode,
                onPick = { onChange(config.copy(mode = it)) },
            )
            OptionRow(
                label = s.gameRounds,
                options = listOf("5" to "5", "10" to "10"),
                selected = config.maxRounds.toString(),
                onPick = { onChange(config.copy(maxRounds = it.toIntOrNull() ?: 5)) },
            )
        }
        else -> Unit
    }
}

@Composable
private fun OptionRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 66.dp))
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (value, text) ->
                val on = selected == value
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) Ink else DarkSurface)
                        .border(1.dp, if (on) Ink else BorderSubtle, RoundedCornerShape(8.dp))
                        .clickable { onPick(value) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text, color = if (on) InkInverse else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Pre-fetches songs via ChatController.searchSongs for SongQuiz so engine creation stays pure & synchronous. */
internal suspend fun prepareSongQuizPayload(
    controller: ChatController,
    mode: String,
    rounds: Int,
): String? {
    val queries = when (mode) {
        "pop" -> listOf("Türkçe pop hit şarkılar", "Tarkan Sezen Aksu Gülşen")
        "rap" -> listOf("Türkçe rap hit", "Ezhel Ceza Sagopa Lvbel")
        "rock" -> listOf("Türkçe rock klasik", "Duman Mor ve Ötesi maNga")
        else -> listOf("Türkçe en çok dinlenen şarkılar", "Türkçe pop rock rap hit")
    }
    val collected = LinkedHashMap<String, SharedItem>()
    for (q in queries) {
        val list = runCatching { controller.searchSongs(q) }.getOrDefault(emptyList())
        list.forEach { item ->
            if (item.id.isNotBlank() && item.title.isNotBlank()) {
                collected.putIfAbsent(item.id, item)
            }
        }
        if (collected.size >= rounds + 6) break
    }
    val pool = collected.values.toList().shuffled()
    if (pool.size < 4) return null
    val roundCount = rounds.coerceIn(1, pool.size.coerceAtMost(10))
    val answers = pool.take(roundCount)
    val payload = buildJsonObject {
        put("genre", mode)
        put(
            "questions",
            buildJsonArray {
                answers.forEachIndexed { idx, ans ->
                    val distractors = pool.filter { it.id != ans.id }.shuffled().take(3)
                    val choices = (distractors + ans).shuffled()
                    val correctIdx = choices.indexOfFirst { it.id == ans.id }.coerceAtLeast(0)
                    val startMs = 25_000L + ((idx * 9_000L) % 35_000L)
                    add(
                        buildJsonObject {
                            put("songId", ans.id)
                            put("title", ans.title.take(64))
                            put("artist", ans.subtitle.take(48))
                            put("startMs", startMs)
                            put("correct", correctIdx)
                            put(
                                "choices",
                                buildJsonArray {
                                    choices.forEach { c ->
                                        val label = if (c.subtitle.isNotBlank()) "${c.title} — ${c.subtitle}" else c.title
                                        add(kotlinx.serialization.json.JsonPrimitive(label.take(72)))
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }
    return payload.toString()
}

/** Full-screen monochrome game arena dialog. Pressing Back minimizes to chat without losing state. */
@Composable
fun GameArenaDialog(
    session: GameSession,
    me: ChatUser,
    other: ChatUser,
    controller: ChatController,
    s: ChatStrings,
    onDismiss: () -> Unit,
    onOpenGame: (String) -> Unit,
) {
    val engine = GameRegistry.of(session.kind) ?: return
    var showRules by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmForfeit by remember { mutableStateOf(false) }
    var rematchBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgRoot)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        PlatformBackHandler(enabled = true) { onDismiss() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // Top bar with game emblem & signature accent divider
            val arenaPal = session.kind.palette()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(QMusicIcons.Back, contentDescription = s.gameBackToChat, tint = Ink)
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(arenaPal.softBg)
                        .border(1.dp, arenaPal.softBorder, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    GameEmblem(session.kind, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        engine.title(s),
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = buildString {
                        if (session.maxRounds > 1) append(if (s.english) "Round ${session.round}/${session.maxRounds}" else "Tur ${session.round}/${session.maxRounds}")
                        if (session.summary.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(session.summary)
                        }
                    }
                    if (sub.isNotBlank()) {
                        Text(sub, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(arenaPal.softBg)
                        .border(1.dp, arenaPal.softBorder, CircleShape)
                        .clickable { showRules = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("?", color = arenaPal.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(2.dp))
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(QMusicIcons.More, contentDescription = null, tint = Ink)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = DarkSurfaceVariant,
                    ) {
                        DropdownMenuItem(
                            text = { Text(s.gameRules, color = TextPrimary) },
                            onClick = { showMenu = false; showRules = true },
                        )
                        if (session.status == GameStatus.WAITING && session.createdBy == me.uid) {
                            DropdownMenuItem(
                                text = { Text(s.gameCancel, color = TextPrimary) },
                                onClick = {
                                    showMenu = false
                                    controller.cancelGame(other, session.id)
                                },
                            )
                        }
                        if (session.status == GameStatus.PLAYING && me.uid in session.players) {
                            DropdownMenuItem(
                                text = { Text(s.gameForfeit, color = GameColors.Crimson) },
                                onClick = { showMenu = false; confirmForfeit = true },
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(arenaPal.primary.copy(alpha = 0.55f)))

            // Join strip when opening a WAITING invite we haven't joined yet
            if (session.status == GameStatus.WAITING && me.uid !in session.players) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.gameInvite, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Ink)
                            .clickable { controller.joinGame(other, session.id) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(s.gameJoin, color = InkInverse, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(BorderSubtle))
            }

            // Game arena content
            Box(Modifier.weight(1f).fillMaxWidth()) {
                engine.ArenaContent(
                    session = session,
                    me = me,
                    other = other,
                    controller = controller,
                    s = s,
                    onAction = { action -> controller.submitGameAction(other, session.id, action) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // End card footer when finished or cancelled
            if (session.status == GameStatus.FINISHED || session.status == GameStatus.CANCELLED) {
                GameEndCard(
                    session = session,
                    myUid = me.uid,
                    s = s,
                    rematchBusy = rematchBusy,
                    onRematch = {
                        if (session.kind == GameKind.SONG_QUIZ) {
                            rematchBusy = true
                            scope.launch {
                                val st = GameJson.parseObject(session.stateJson)
                                val genre = (st["genre"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "mixed"
                                val extra = prepareSongQuizPayload(controller, genre, session.maxRounds.coerceAtLeast(5))
                                rematchBusy = false
                                if (extra != null) {
                                    controller.createGame(
                                        other,
                                        GameConfig(GameKind.SONG_QUIZ, mode = genre, maxRounds = session.maxRounds.coerceAtLeast(5), turnSeconds = 15, extra = extra),
                                        s,
                                        onCreated = onOpenGame,
                                    )
                                }
                            }
                        } else {
                            controller.rematchGame(other, session, s, onCreated = onOpenGame)
                        }
                    },
                    onBack = onDismiss,
                )
            }
        }
    }

    if (showRules) {
        AlertDialog(
            onDismissRequest = { showRules = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(engine.title(s) + " · " + s.gameRules, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text(engine.rules(s), color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = { showRules = false }) { Text(s.closeLabel, color = Ink) }
            },
        )
    }

    if (confirmForfeit) {
        AlertDialog(
            onDismissRequest = { confirmForfeit = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(s.gameForfeit, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text(s.gameForfeitConfirm, color = TextSecondary, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForfeit = false
                    controller.forfeitGame(other, session.id)
                }) { Text(s.gameForfeit, color = Ink) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForfeit = false }) { Text(s.cancel, color = TextSecondary) }
            },
        )
    }
}

@Composable
private fun GameEndCard(
    session: GameSession,
    myUid: String,
    s: ChatStrings,
    rematchBusy: Boolean,
    onRematch: () -> Unit,
    onBack: () -> Unit,
) {
    val headline = when {
        session.status == GameStatus.CANCELLED -> s.gameCancelled
        session.winnerUid == null -> s.gameDraw
        session.winnerUid == myUid -> s.gameYouWon
        else -> {
            val name = session.players[session.winnerUid]?.name?.substringBefore(' ') ?: ""
            if (s.english) "$name won!" else "$name kazandı!"
        }
    }
    val pal = session.kind.palette()
    val iWon = session.winnerUid == myUid && session.status == GameStatus.FINISHED
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSurface)
            .border(BorderStroke(1.dp, if (iWon) GameColors.Emerald.copy(alpha = 0.55f) else pal.softBorder))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            headline,
            color = if (iWon) GameColors.Emerald else pal.primary,
            fontSize = 19.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (session.summary.isNotBlank()) {
            Text(session.summary, color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (session.players.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                session.order.take(4).forEach { uid ->
                    val p = session.players[uid] ?: return@forEach
                    val win = session.winnerUid == uid
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (win) GameColors.Emerald else DarkSurface)
                            .border(1.dp, if (win) GameColors.Emerald else BorderSubtle, RoundedCornerShape(10.dp))
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (uid == myUid) s.you else p.name.substringBefore(' '),
                            color = if (win) GameColors.onAccent(GameColors.Emerald) else TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            p.score.toString(),
                            color = if (win) GameColors.onAccent(GameColors.Emerald) else TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurface)
                    .border(1.dp, BorderHover, RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.gameBackToChat, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(pal.primary)
                    .clickable(enabled = !rematchBusy, onClick = onRematch),
                contentAlignment = Alignment.Center,
            ) {
                if (rematchBusy) {
                    CircularProgressIndicator(color = pal.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Text(s.gameRematch, color = pal.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Monochrome head-to-head / group game scoreboard card for ProfilePage & GroupInfoPage. */
@Composable
fun GameScoreboardCard(
    stats: ChatGameStats,
    names: Map<String, String>,
    myUid: String,
    s: ChatStrings,
    modifier: Modifier = Modifier,
) {
    if (stats.totalPlayed <= 0) return
    val entries = remember(stats) { stats.wins.entries.sortedByDescending { it.value } }
    val topWins = entries.firstOrNull()?.value?.coerceAtLeast(1) ?: 1

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(QMusicIcons.Dice, contentDescription = null, tint = GameColors.Gold, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(s.gameScoreboard, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "${stats.totalPlayed}",
                color = GameColors.Gold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (entries.isEmpty()) {
            Text(s.gameDraw, color = TextSecondary, fontSize = 13.sp)
        } else {
            val barColors = listOf(GameColors.Gold, GameColors.Emerald, GameColors.Sky, GameColors.Violet, GameColors.Coral, GameColors.Teal)
            entries.take(6).forEachIndexed { idx, (uid, wins) ->
                val label = if (uid == myUid) s.you else names[uid] ?: "?"
                val ratio = (wins.toFloat() / topWins.toFloat()).coerceIn(0.08f, 1f)
                val barColor = barColors[idx % barColors.size]
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("$wins ${s.gameWins}", color = barColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(DarkSurfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(ratio)
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(barColor),
                        )
                    }
                }
            }
        }
    }
}

/** Compact host composable called once at the bottom of ConversationPage so ChatScreens.kt stays under 64 KB. */
@Composable
fun ConversationGameHost(
    controller: ChatController,
    other: ChatUser,
    me: ChatUser,
    s: ChatStrings,
    games: Map<String, GameSession>,
    pickerOpen: Boolean,
    openGameId: String?,
    onClosePicker: () -> Unit,
    onOpenGame: (String?) -> Unit,
) {
    if (pickerOpen) {
        GamePickerSheet(
            controller = controller,
            other = other,
            me = me,
            s = s,
            onDismiss = onClosePicker,
            onStarted = { id -> onOpenGame(id) },
        )
    }
    val active = openGameId?.let { games[it] }
    if (active != null) {
        GameArenaDialog(
            session = active,
            me = me,
            other = other,
            controller = controller,
            s = s,
            onDismiss = { onOpenGame(null) },
            onOpenGame = { nextId -> onOpenGame(nextId) },
        )
    }
}

/** Handles slash command quick-start (either immediate creation or picker for SongQuiz). */
fun handleGameSlashCommand(
    cmd: GameSlashResult,
    controller: ChatController,
    other: ChatUser,
    me: ChatUser,
    s: ChatStrings,
    onOpenPicker: () -> Unit,
    onOpenGame: (String) -> Unit,
) {
    when (cmd) {
        GameSlashResult.OpenPicker -> onOpenPicker()
        is GameSlashResult.QuickStart -> {
            if (!isAvailableFor(cmd.kind, other.isGroup, other.uid == me.uid) || cmd.kind == GameKind.SONG_QUIZ) {
                onOpenPicker()
            } else {
                val cfg = GameRegistry.defaultConfig(cmd.kind)
                controller.createGame(other, cfg, s, onCreated = onOpenGame)
            }
        }
    }
}

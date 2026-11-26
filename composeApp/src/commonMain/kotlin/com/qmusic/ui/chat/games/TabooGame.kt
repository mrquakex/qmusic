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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TabooCard(val target: String, val forbidden: List<String>)

private const val TABOO_MAX_PASSES = 3

internal object TabooDeck {
    private const val CHUNK_1 = """
KAHVE:FİNCAN,TÜRK,SÜT,KAFEİN,FAL
TELEFON:ARAMA,MESAJ,EKRAN,CEP,ŞARJ
BİSİKLET:PEDAL,TEKERLEK,SÜRMEK,İKİ,GİDON
KÜTÜPHANE:KİTAP,OKUMAK,SESSİZ,RAF,ÖDÜNÇ
ŞEMSİYE:YAĞMUR,ISLANMAK,AÇMAK,GÜNEŞ,SU
DENİZ:SU,DALGA,YÜZMEK,SAHİL,MAVİ
PİZZA:HAMUR,PEYNİR,DİLİM,İTALYAN,FIRIN
GÖZLÜK:GÖZ,CAM,TAKMAK,GÜNEŞ,NUMARA
FUTBOL:TOP,GOL,KALE,SAHA,HAKEM
SİNEMA:FİLM,PERDE,MISIR,KOLTUK,BİLET
OKUL:ÖĞRENCİ,ÖĞRETMEN,DERS,SINIF,ZİL
UÇAK:KANAT,PİLOT,HAVALİMANI,UÇMAK,GÖKYÜZÜ
KEDİ:MİYAV,PATİ,TÜY,HAYVAN,FARE
KÖPEK:HAVLAMAK,SADIK,TASMA,KEMİK,KUYRUK
KARPUZ:YAZ,KIRMIZI,ÇEKİRDEK,MEYVE,KABUK
ÇİKOLATA:TATLI,KAKAO,SÜT,BİTTER,ŞEKER
YASTIK:UYKU,YATAK,BAŞ,YUMUŞAK,KILIF
AYNA:YANSIMA,BAKMAK,CAM,YÜZ,GÖRMEK
SAAT:ZAMAN,AKREP,YELKOVAN,KOL,DAKİKA
ANAHTAR:KİLİT,KAPI,AÇMAK,METAL,ÇİLİNGİR
MÜZİK:ŞARKI,NOTA,DİNLEMEK,SES,RİTİM
GİTAR:TEL,ÇALMAK,MÜZİK,AKOR,ENSTRÜMAN
KAMP:ÇADIR,ATEŞ,DOĞA,ORMAN,UYKU
TREN:RAY,VAGON,İSTASYON,LOKOMOTİF,YOLCU
GEMİ:DENİZ,KAPTAN,LİMAN,YELKEN,SU
ÇAY:DEMLİK,BARDAK,RİZE,SICAK,ŞEKER
BAKLAVA:TATLI,FISTIK,ŞERBET,YUFKA,GAZİANTEP
SİMİT:SUSAM,KAHVALTI,FIRIN,HALKA,ÇAY
DONDURMA:SOĞUK,KÜLAH,YAZ,MARAŞ,ERİMEK
HAMBURGER:EKMEK,KÖFTE,PATATES,FAST,SOS
PATATES:KIZARTMA,PÜRE,KETÇAP,SEBZE,TOPRAK
LİMON:EKŞİ,SARI,SALATA,SIKMAK,VİTAMİN
ELMA:MEYVE,KIRMIZI,AĞAÇ,AMASYA,KURT
MUZ:SARI,MAYMUZ,KABUK,TROPİKAL,MEYVE
ÇİLEK:KIRMIZI,REÇEL,MEYVE,BAHÇE,TATLI
PORTAKAL:TURUNCU,KIŞ,SU,VİTAMİN,KABUK
BALIK:DENİZ,OLTA,YÜZGEÇ,IZGARA,AKVARYUM
ARABA:TEKERLEK,DİREKSİYON,MOTOR,BENZİN,YOL
OTOBÜS:DURAK,ŞOFÖR,YOLCU,BİLET,TERMİNAL
METRO:YERALT,RAY,TREN,İSTASYON,HIZLI
TAKSİ:SARI,ÜCRET,ŞOFÖR,MÜŞTERİ,TAKSiMETRE
ASANSÖR:KAT,BİNA,ÇIKMAK,İNMEK,DÜĞME
MERDİVEN:BASAMAK,ÇIKMAK,İNMEK,TIRABZAN,KAT
PENCERE:CAM,PERDE,AÇMAK,MANZARA,EV
BALKON:EV,DIŞARI,ÇAMAŞIR,MANZARA,OTURMAK
MUTFAK:YEMEK,OCAK,BUZDOLABI,PİŞİRMEK,TEZGAH
BANYO:DUŞ,SU,SABUN,HAVLU,YIKANMAK
HASTANE:DOKTOR,HEMŞİRE,HASTA,ACİL,AMBULANS
ECZANE:İLAÇ,REÇETE,HASTA,SAĞLIK,NÖBETÇİ
POSTANE:MEKTUP,KARGO,ZARF,PUL,GÖNDERMEK
"""

    private const val CHUNK_2 = """
POLİS:KARAKOL,SİREN,SUÇLU,KELEPÇE,ÜNiFORMA
İTFAİYE:YANGIN,SU,HORTUM,KIRMIZI,SÖNDÜRMEK
AŞÇI:YEMEK,MUTFAK,RESTORAN,TARİF,ŞAPKA
PİLOT:UÇAK,KOKPİT,UÇMAK,HOSTES,HAVA
MİMAR:BİNA,ÇİZİM,PROJE,TASARIM,İNŞAAT
AVUKAT:MAHKEME,HAKİM,DAVA,SAVUNMA,KANUN
RESSAM:TABLO,FIRÇA,BOYA,TUVAL,SANAT
YAZAR:KİTAP,ROMAN,KALEM,HİKAYE,OKUR
ŞARKICI:MİKROFON,KONSER,SAHNE,ALBÜM,SES
OYUNCU:FİLM,TİYATRO,ROL,SAHNE,KAMERA
FOTOGRAF:KAMERA,POZ,FLAŞ,ANİ,ALBÜM
BİLGİSAYAR:EKRAN,KLAVYE,FARE,İNTERNET,YAZILIM
KULAKLIK:MÜZİK,SES,KULAK,KABLO,BLUETOOTH
KLAVYE:TUŞ,YAZMAK,HARF,BİLGİSAYAR,ENTER
ŞARJ:PİL,BATARYA,KABLO,PRİZ,TELEFON
İNTERNET:WİFİ,WEB,BAĞLANTI,SİTE,AĞ
ROKET:UZAY,FIRLATMAK,ASTRONOT,AY,YAKIT
GEZEGEN:UZAY,DÜNYA,MARS,YÖRÜNGE,GÜNEŞ
GÜNEŞ:SICAK,IŞIK,GÜNDÜZ,YAZ,YILDIZ
YILDIZ:GECE,GÖKYÜZÜ,PARLAK,UZAY,KAYMAK
BULUT:GÖKYÜZÜ,YAĞMUR,BEYAZ,PAMUK,HAVA
GÖKKUŞAĞI:RENK,YAĞMUR,GÜNEŞ,YEDİ,KEMER
ŞİMŞEK:GÖKGÜRÜLTÜSÜ,YAĞMUR,IŞIK,FIRTINA,ELEKTRİK
KAR:KIŞ,BEYAZ,SOĞUK,KARDAN,YAĞMAK
RÜZGAR:ESMEK,HAVA,FIRTINA,YAPRAK,SOĞUK
ORMAN:AĞAÇ,YEŞİL,DOĞA,HAYVAN,YÜRÜYÜŞ
DAĞ:ZİRVE,TIRMANMAK,YÜKSEK,KAR,TEPE
NEHİR:SU,AKMAK,DERE,KÖPRÜ,BALIK
ŞELALE:SU,DÜŞMEK,YÜKSEK,DOĞA,SES
ÇÖL:KUM,SICAK,DEVE,KURAK,VAHA
VOLKAN:LAV,PATLAMAK,DAĞ,ATEŞ,KÜL
DEPREM:SALLANMAK,FAY,YIKIM,ŞİDDET,AFET
TİMSAH:SÜRÜNGEN,DİŞ,NEHİR,YEŞİL,YIRTICI
PENGUEN:KUTUP,SOĞUK,KUŞ,SİYAH,YÜRÜMEK
ZÜRAFA:UZUN,BOYUN,AFRİKA,BENEKLİ,YAPRAK
FİL:HORTUM,BÜYÜK,KULAK,DİŞ,AĞIR
ASLAN:ORMAN,KRAL,YELE,KÜKREMEK,VAHŞİ
YUNUS:DENİZ,ZEKİ,YÜZMEK,ATLAMAK,MEMELİ
KELEBEK:KANAT,RENKLİ,TIRTIL,KOZA,UÇMAK
ARI:BAL,KOVAN,İĞNE,ÇİÇEK,VIZILTı
KARINCA:ÇALIŞKAN,KÜÇÜK,YUVA,SÜRÜ,BÖCEK
BAYKUŞ:GECE,KUŞ,GÖZ,AĞAÇ,ÖTMEK
PAPAĞAN:KONUŞMAK,RENKLİ,KUŞ,KAFES,TAKLİT
KAPLUMBAĞA:KABUK,YAVAŞ,YEŞİL,UZUN,YÜRÜMEK
PANDA:BAMBU,SİYAH,BEYAZ,ÇİN,AYI
TİLKİ:KURNAZ,KUYRUK,TURUNCU,ORMAN,HAYVAN
KANGURU:AVUSTRALYA,ZIPLAMAK,KESE,KUYRUK,HAYVAN
AHTAPOT:SEKİZ,KOL,DENİZ,MÜREKKEP,VANTUZ
BUKALEMUN:RENK,DEĞİŞTİRMEK,KERTENKELE,DİL,KAMUFLAJ
FLAMİNGO:PEMBE,TEK,AYAK,KUŞ,GÖL
"""

    private const val CHUNK_3 = """
PİYANO:TUŞ,SİYAH,BEYAZ,ÇALMAK,MÜZİK
KEMAN:YAY,TEL,ÇALMAK,ORKESTRA,MÜZİK
DAVUL:TOKMAK,RİTİM,SES,RAMAZAN,VURMAK
SATRANÇ:ŞAH,MAT,VEZİR,PİYON,TAHTA
TAVLA:ZAR,PUL,KAPI,MARS,OYUN
BASKETBOL:POTA,TOP,SMAÇ,PİVOT,SAHA
VOLEYBOL:FİLE,SMAÇ,SERVİS,TAKIM,TOP
TENİS:RAKET,KORT,TOP,FİLE,SET
YÜZME:HAVUZ,DENİZ,KULAÇ,MAYO,SU
KAYAK:KAR,DAĞ,KIŞ,PİST,SOĞUK
BOKS:ELDİVEN,RİNG,YUMRUK,RAUNT,DÖVÜŞ
YOGA:ESNEME,NEFES,MAT,MEDİTASYON,SPOR
KAHVALTI:SABAH,PEYNİR,ZEYTİN,YUMURTA,ÇAY
DOĞUMGÜNÜ:PASTA,MUM,HEDİYE,ÜFLEMEK,PARTİ
DÜĞÜN:GELİN,DAMAT,HALAY,NİKAH,TAKİ
TATİL:OTEL,YAZ,DENİZ,DİNLENMEK,VALİZ
MÜZE:TARİH,ESER,SERGİ,GEZMEK,BİLET
TİYATRO:SAHNE,PERDE,OYUNCU,ALKİŞ,DEKOR
KONSER:SAHNE,MÜZİK,ŞARKICI,SEYİRCİ,BİLET
LUNAPARK:DÖNMEDOLAP,EĞLENCE,BİLET,ÇARPIŞAN,TREN
HAYVANAT:BAHÇE,KAFES,ZİYARET,ASLAN,MAYMUN
BAKKAL:MAHALLE,EKMEK,VERESİYE,ALIŞVERİŞ,DÜKKAN
PAZAR:SEBZE,MEYVE,TEZGAH,TAZE,ALIŞVERİŞ
KAFE:KAHVE,OTURMAK,SOHBET,MASA,TATLI
RESTORAN:YEMEK,GARSON,MENÜ,HESAP,MASA
OTEL:ODA,TATİL,RESEPSİYON,YATAK,KONAKLAMA
KÖPRÜ:GEÇMEK,BOĞAZ,SU,ARAÇ,AYAK
TÜNEL:KARANLIK,DAĞ,GEÇMEK,YOL,ALT
FENER:IŞIK,DENİZ,EL,KARANLIK,PİL
PUSULA:YÖN,KUZEY,GÜNEY,İBRE,HARİTA
HARİTA:YOL,ÜLKE,ŞEHİR,KONUM,NAVİGASYON
DÜRBÜN:UZAK,BAKMAK,GÖZ,YAKINLAŞTIRMAK,CAM
MİKROSKOP:KÜÇÜK,BAKMAK,HÜCRE,LABORATUVAR,BÜYÜTMEK
TELESKOP:UZAY,YILDIZ,AY,BAKMAK,GÖZLEM
TERMOS:SICAK,SOĞUK,SU,ÇAY,SAKLAMAK
BAVUL:SEYAHAT,KIYAFET,TEKERLEK,TATİL,TAŞIMAK
CÜZDAN:PARA,KART,CEP,DERİ,KİMLİK
DEFTER:SAYFA,YAZMAK,KALEM,ÇİZGİLİ,OKUL
SİLGİ:SİLMEK,YANLIŞ,KURŞUN,KALEM,KAUÇUK
CETVEL:ÖLÇMEK,DÜZ,ÇİZGİ,SANTİM,MATEMATİK
MAKAS:KESMEK,KAĞIT,SAÇ,BIÇAK,İKİ
ÇEKİÇ:ÇİVİ,VURMAK,ALET,TAMİR,DEMİR
TORNAVİDA:VİDA,SIKMAK,TAMİR,ALET,ÇEVİRMEK
SÜPÜRGE:TEMİZLİK,TOZ,ELEKTRİK,YER,ÇEKMEK
ÜTÜ:KIRIŞIK,BUHAR,GÖMLEK,SICAK,DÜZ
BUZDOLABI:SOĞUK,YİYECEK,MUTFAK,DONMAK,KAPAK
FIRIN:EKMEK,SICAK,PİŞİRMEK,BÖREK,TEPSİ
MİKRODALGA:ISITMAK,MUTFAK,HIZLI,YEMEK,TABAK
ÇAYDANLIK:DEM,SU,KAYNAMAK,OCAK,İKİ
KUMBARA:PARA,BİRİKTİRMEK,BOZUK,TASARRUF,KIRMAK
"""

    val cards: List<TabooCard> by lazy {
        (CHUNK_1 + "\n" + CHUNK_2 + "\n" + CHUNK_3)
            .lineSequence()
            .map { it.trim() }
            .filter { it.contains(':') }
            .mapNotNull { line ->
                val target = TurkishText.trNormalize(line.substringBefore(':'))
                val forb = line.substringAfter(':').split(',').map { TurkishText.trNormalize(it) }.filter { it.isNotEmpty() }
                if (target.isEmpty() || forb.size < 3) null else TabooCard(target, forb)
            }
            .distinctBy { it.target }
            .toList()
    }

    fun pick(seed: String, cardIndex: Int): TabooCard {
        val list = cards
        val start = Sha256.deterministicIndex(seed, list.size)
        return list[(start + cardIndex * 7) % list.size]
    }
}

object TabooEngine : GameEngine {
    override val kind: GameKind = GameKind.TABOO

    override fun title(s: ChatStrings): String = if (s.english) "Taboo" else "Tabu"
    override fun subtitle(s: ChatStrings): String =
        if (s.english) "Describe without forbidden words · auto filter" else "Yasaklı kelimeleri kullanmadan anlat · otomatik filtre"

    override fun rules(s: ChatStrings): String = if (s.english) {
        "Each round lasts 60 seconds. One player describes the target word via text clues while avoiding the 5 forbidden words (automatically blocked!). Other players guess; every correct guess gives +1 to both describer and guesser. Up to 3 passes per round."
    } else {
        "Her tur 60 saniye sürer. Anlatıcı hedef kelimeyi 5 yasaklı kelimeyi kullanmadan ipuçlarıyla anlatır (yasaklı kelimeler otomatik engellenir!). Diğer oyuncular tahmin eder; her doğru tahminde hem anlatıcı hem bilen +1 puan alır. Tur başına 3 pas hakkı vardır."
    }

    /** Returns the forbidden word violated by [clue], or null if the clue is clean. */
    internal fun violatedWord(clue: String, card: TabooCard): String? {
        val normClue = TurkishText.trLower(clue).replace(Regex("[^a-zçğıöşü\\s]"), " ")
        val tokens = normClue.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val banned = listOf(card.target) + card.forbidden
        for (rawBan in banned) {
            val b = TurkishText.trLower(rawBan)
            if (b.length < 2) continue
            val stem = if (b.length >= 5) b.take(b.length - 1) else b
            if (normClue.contains(b)) return rawBan
            if (stem.length >= 4 && tokens.any { it.startsWith(stem) || it.contains(b) }) return rawBan
        }
        return null
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
        val maxRounds = config.maxRounds.coerceIn(2, 8)
        val turnSec = config.turnSeconds.coerceIn(30, 90)
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
            kind = GameKind.TABOO,
            status = status,
            createdBy = creator.uid,
            players = players,
            order = order,
            turn = if (status == GameStatus.PLAYING) creator.uid else null,
            round = 1,
            maxRounds = maxRounds,
            turnSeconds = turnSec,
            turnDeadlineAt = if (status == GameStatus.PLAYING) nowMs + turnSec * 1000L else 0L,
            version = 1L,
            stateJson = encodeState(
                phase = "play",
                cardIndex = 0,
                passesLeft = TABOO_MAX_PASSES,
                roundHits = 0,
                clues = emptyList(),
                guesses = emptyList(),
            ),
            summary = if (status == GameStatus.PLAYING) "${creator.name.substringBefore(' ')} anlatıyor" else "Oyuncu bekleniyor",
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
            turnDeadlineAt = nowMs + session.turnSeconds.coerceAtLeast(45) * 1000L,
            summary = "${players[order.first()]?.name?.substringBefore(' ')} anlatıyor",
            updatedAt = nowMs,
        )
    }

    override fun applyAction(session: GameSession, uid: String, action: GameAction, nowMs: Long): GameSession? {
        GameRulesHelper.handleCommonActions(session, uid, action, nowMs)?.let { return it }
        if (session.status != GameStatus.PLAYING || uid !in session.players) return null

        val st = GameJson.parseObject(session.stateJson)
        val phase = st.str("phase", "play")
        val cardIndex = st.int("cardIdx", 0)
        val passesLeft = st.int("passes", TABOO_MAX_PASSES)
        val roundHits = st.int("hits", 0)
        val clues = st.arr("clues").strList()
        val guesses = st.arr("guesses").strList()
        val describerUid = session.turn ?: session.order.firstOrNull() ?: return null
        val card = TabooDeck.pick(session.id, cardIndex)

        when (action.type) {
            "clue" -> {
                if (phase != "play" || uid != describerUid) return null
                val text = action.payload.trim().take(120)
                if (text.isEmpty()) return null
                if (violatedWord(text, card) != null) return null
                return session.copy(
                    stateJson = encodeState("play", cardIndex, passesLeft, roundHits, (clues + text).takeLast(12), guesses),
                    updatedAt = nowMs,
                )
            }
            "guess" -> {
                if (phase != "play" || uid == describerUid) return null
                val raw = action.payload.trim().take(40)
                if (raw.isEmpty()) return null
                val norm = TurkishText.trNormalize(raw)
                val who = session.players[uid]?.name?.substringBefore(' ').orEmpty()
                if (norm == card.target) {
                    val players = session.players.toMutableMap()
                    players[uid]?.let { players[uid] = it.copy(score = it.score + 1) }
                    players[describerUid]?.let { players[describerUid] = it.copy(score = it.score + 1) }
                    val nextHits = roundHits + 1
                    return session.copy(
                        players = players,
                        stateJson = encodeState("play", cardIndex + 1, passesLeft, nextHits, emptyList(), listOf("✓ $who bildi: ${card.target}")),
                        summary = "$who bildi! (${card.target})",
                        updatedAt = nowMs,
                    )
                } else {
                    return session.copy(
                        stateJson = encodeState("play", cardIndex, passesLeft, roundHits, clues, (guesses + "$who: $raw").takeLast(10)),
                        updatedAt = nowMs,
                    )
                }
            }
            "pass" -> {
                if (phase != "play" || uid != describerUid || passesLeft <= 0) return null
                return session.copy(
                    stateJson = encodeState("play", cardIndex + 1, passesLeft - 1, roundHits, emptyList(), listOf("→ Pas geçildi")),
                    updatedAt = nowMs,
                )
            }
            "end_turn" -> {
                if (phase != "play") return null
                if (session.turnDeadlineAt > 0L && nowMs < session.turnDeadlineAt && uid != describerUid) return null
                val isLast = session.round >= session.maxRounds
                val sorted = session.players.values.sortedByDescending { it.score }
                val winner = if (isLast && sorted.size >= 2 && sorted[0].score > sorted[1].score) sorted[0].uid else null
                val sum = if (isLast) {
                    winner?.let { "${session.players[it]?.name?.substringBefore(' ')} kazandı!" } ?: "Berabere!"
                } else {
                    "Tur ${session.round} bitti · $roundHits kelime bilindi"
                }
                return session.copy(
                    status = if (isLast) GameStatus.FINISHED else GameStatus.PLAYING,
                    winnerUid = winner,
                    stateJson = encodeState("round_over", cardIndex + 1, passesLeft, roundHits, clues, guesses),
                    summary = sum,
                    updatedAt = nowMs,
                )
            }
            "next_round" -> {
                if (phase != "round_over" || session.round >= session.maxRounds) return null
                val nextRound = session.round + 1
                val nextDescriber = session.order[(nextRound - 1) % session.order.size]
                val name = session.players[nextDescriber]?.name?.substringBefore(' ').orEmpty()
                return session.copy(
                    round = nextRound,
                    turn = nextDescriber,
                    turnDeadlineAt = nowMs + session.turnSeconds.coerceAtLeast(45) * 1000L,
                    stateJson = encodeState("play", cardIndex, TABOO_MAX_PASSES, 0, emptyList(), emptyList()),
                    summary = "Tur $nextRound/${session.maxRounds} · $name anlatıyor",
                    updatedAt = nowMs,
                )
            }
            else -> return null
        }
    }

    private fun encodeState(
        phase: String,
        cardIndex: Int,
        passesLeft: Int,
        roundHits: Int,
        clues: List<String>,
        guesses: List<String>,
    ): String = buildJsonObject {
        put("phase", phase)
        put("cardIdx", cardIndex)
        put("passes", passesLeft)
        put("hits", roundHits)
        put("clues", buildJsonArray { clues.forEach { add(JsonPrimitive(it)) } })
        put("guesses", buildJsonArray { guesses.forEach { add(JsonPrimitive(it)) } })
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
        val cardIndex = st.int("cardIdx", 0)
        val passesLeft = st.int("passes", TABOO_MAX_PASSES)
        val roundHits = st.int("hits", 0)
        val clues = remember(session.stateJson) { st.arr("clues").strList() }
        val guesses = remember(session.stateJson) { st.arr("guesses").strList() }
        val isDescriber = session.turn == me.uid
        val card = remember(session.id, cardIndex) { TabooDeck.pick(session.id, cardIndex) }
        val tr = !s.english

        var input by remember(session.round, cardIndex) { mutableStateOf("") }
        var warning by remember(input, cardIndex) { mutableStateOf<String?>(null) }
        var remainingSec by remember(session.turnDeadlineAt) { mutableStateOf(session.turnSeconds.coerceAtLeast(45)) }

        LaunchedEffect(phase, session.turnDeadlineAt, session.round) {
            if (phase != "play" || session.status != GameStatus.PLAYING || session.turnDeadlineAt <= 0L) return@LaunchedEffect
            while (true) {
                val leftMs = (session.turnDeadlineAt - controller.serverNow()).coerceAtLeast(0L)
                remainingSec = ((leftMs + 999L) / 1000L).toInt()
                if (leftMs <= 0L) {
                    onAction(GameAction("end_turn"))
                    break
                }
                delay(250)
            }
        }

        Column(
            modifier = modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Top scores + timer
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (remainingSec <= 10 && phase == "play") Ink else DarkSurfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "${remainingSec}s",
                        color = if (remainingSec <= 10 && phase == "play") InkInverse else TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    if (isDescriber) (if (tr) "Sen anlatıyorsun" else "You are describing")
                    else "${session.players[session.turn]?.name?.substringBefore(' ').orEmpty()} " + (if (tr) "anlatıyor" else "is describing"),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (tr) "Doğru: $roundHits" else "Hits: $roundHits",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }

            if (phase == "round_over") {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkSurface)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(session.summary, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        if (session.status == GameStatus.PLAYING) {
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
                    }
                }
                return@Column
            }

            // Describer sees authentic Hot-Magenta & Crimson Taboo card
            val magenta = GameColors.Magenta
            val crimson = GameColors.Crimson
            if (isDescriber) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(DarkSurface)
                        .border(2.dp, magenta, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(magenta)
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(card.target, color = GameColors.onAccent(magenta), fontSize = 21.sp, fontWeight = FontWeight.Black)
                    }
                    Text(if (tr) "⊘ YASAKLI KELİMELER" else "⊘ FORBIDDEN WORDS", color = crimson, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                        card.forbidden.take(3).forEach { f -> ForbiddenPill(f) }
                    }
                    if (card.forbidden.size > 3) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                            card.forbidden.drop(3).forEach { f -> ForbiddenPill(f) }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(magenta.copy(alpha = 0.12f))
                        .border(1.dp, magenta.copy(alpha = 0.40f), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (tr) "İpuçlarını oku ve gizli kelimeyi tahmin et!" else "Read the clues and guess the secret word!",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Live clue + guess feed
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkSurface)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (clues.isEmpty() && guesses.isEmpty()) {
                    Text(
                        if (isDescriber) (if (tr) "Yasaklı kelimeleri kullanmadan ipucu yaz..." else "Type a clue without forbidden words...")
                        else (if (tr) "Anlatıcıdan ilk ipucu bekleniyor..." else "Waiting for first clue..."),
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                }
                clues.forEach { c ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(magenta.copy(alpha = 0.14f))
                            .border(1.dp, magenta.copy(alpha = 0.40f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(c, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                guesses.forEach { g ->
                    val hitLine = g.startsWith("✓")
                    Text(g, color = if (hitLine) GameColors.Emerald else TextSecondary, fontSize = 12.sp, fontWeight = if (hitLine) FontWeight.Bold else FontWeight.Normal)
                }
            }

            warning?.let { w ->
                Text(w, color = crimson, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            // Input bar
            if (session.status == GameStatus.PLAYING) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isDescriber) {
                        Box(
                            modifier = Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(GameColors.Amber.copy(alpha = 0.16f))
                                .border(1.dp, GameColors.Amber.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .clickable(enabled = passesLeft > 0) { onAction(GameAction("pass")) }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (tr) "Pas ($passesLeft)" else "Pass ($passesLeft)", color = if (passesLeft > 0) GameColors.Amber else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurface)
                            .border(1.2.dp, magenta.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                    ) {
                        if (input.isEmpty()) {
                            Text(
                                if (isDescriber) (if (tr) "İpucu yaz..." else "Write clue...") else (if (tr) "Tahminini yaz..." else "Type guess..."),
                                color = TextMuted,
                                fontSize = 14.sp,
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it.take(100) },
                            singleLine = true,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                            cursorBrush = SolidColor(magenta),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (input.isNotBlank()) magenta else DarkSurfaceVariant)
                            .clickable(enabled = input.isNotBlank()) {
                                val text = input.trim()
                                if (isDescriber) {
                                    val bad = violatedWord(text, card)
                                    if (bad != null) {
                                        warning = if (tr) "Yasaklı kelime tespit edildi: $bad" else "Forbidden word detected: $bad"
                                    } else {
                                        warning = null
                                        onAction(GameAction("clue", text))
                                        input = ""
                                    }
                                } else {
                                    onAction(GameAction("guess", text))
                                    input = ""
                                }
                            }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (tr) "Gönder" else "Send", color = if (input.isNotBlank()) GameColors.onAccent(magenta) else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ForbiddenPill(word: String) {
    val crimson = GameColors.Crimson
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(crimson.copy(alpha = 0.14f))
            .border(1.dp, crimson.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(word, color = crimson, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

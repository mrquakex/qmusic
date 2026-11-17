package com.qmusic.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.math.roundToInt

// ==================================================================== the state

/**
 * The secret space: open only after its PIN (or the fingerprint), for as long as the app is in use; it closes when the
 * app goes to the background (or after the chosen while), the screen goes off, or nothing is touched for five minutes.
 * Whether it is open is kept only in memory: the app always starts with it closed.
 */
object SecretSpace {
    val unlocked = MutableStateFlow(false)

    /** Opened with the decoy PIN: an empty secret space. */
    val decoy = MutableStateFlow(false)

    /** The phone's part: settings kept, the screen protected from screenshots. */
    var load: (String) -> String? = { null }
    var save: (String, String?) -> Unit = { _, _ -> }
    var secureWindow: (Boolean) -> Unit = {}

    private fun now() = Clock.System.now().toEpochMilliseconds()

    /** How long the app may be away before the space closes (0: at once). */
    var autoLockMs: Long
        get() = load("autoLock")?.toLongOrNull() ?: 0L
        set(v) = save("autoLock", v.toString())

    var biometric: Boolean
        get() = load("biometric") == "1"
        set(v) = save("biometric", if (v) "1" else null)

    /** Disappearing messages turned on for every conversation put in the secret space (0: off). */
    var defaultTtl: Long
        get() = load("defaultTtl")?.toLongOrNull() ?: 0L
        set(v) = save("defaultTtl", v.toString())

    var showCallerName: Boolean
        get() = SecretChats.showCallerName
        set(v) {
            SecretChats.showCallerName = v
            save("showCaller", if (v) "1" else "0")
        }

    /** New conversations are encrypted end to end on their own (when the others can take it). */
    var encryptNewChats: Boolean
        get() = load("encryptNew") != "0"
        set(v) = save("encryptNew", if (v) "1" else "0")

    private var awayAt = 0L
    var lastTouch = 0L

    fun unlock(asDecoy: Boolean) {
        decoy.value = asDecoy
        lastTouch = now()
        unlocked.value = true
    }

    fun lock() {
        unlocked.value = false
        decoy.value = false
    }

    fun onBackground() {
        awayAt = now()
        if (autoLockMs == 0L) lock()
    }

    fun onForeground() {
        if (unlocked.value && now() - awayAt > autoLockMs) lock()
    }

    fun onScreenOff() = lock()

    const val IDLE_LOCK_MS = 5 * 60_000L

    private var secureCount = 0

    fun secure(on: Boolean) {
        secureCount = (secureCount + if (on) 1 else -1).coerceAtLeast(0)
        secureWindow(secureCount > 0)
    }
}

/** While shown: no screenshots or screen recording, and a blank picture in the recent apps. */
@Composable
internal fun SecureScreen() {
    DisposableEffect(Unit) {
        SecretSpace.secure(true)
        onDispose { SecretSpace.secure(false) }
    }
}

/** Six taps within three seconds open the secret space; a tap more or less does nothing anyone could see. */
internal fun Modifier.secretTaps(onOpen: () -> Unit): Modifier = pointerInput(Unit) {
    val taps = ArrayDeque<Long>()
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val t = Clock.System.now().toEpochMilliseconds()
        taps.addLast(t)
        while (taps.isNotEmpty() && t - taps.first() > 3_000) taps.removeFirst()
        if (taps.size >= 6) {
            taps.clear()
            onOpen()
        }
    }
}

// ==================================================================== the words

internal class E2eWords(private val english: Boolean) {
    private fun t(tr: String, en: String) = if (english) en else tr
    val secret get() = t("Gizli", "Secret")
    val secretSpace get() = t("Gizli alan", "Secret space")
    val introTitle get() = t("Gizli alan", "Secret space")
    val introBody get() = t(
        "Burada kurduğun gruplar ve taşıdığın sohbetler hiçbir listede görünmez. Bildirimlerde sadece \"Yeni mesaj\" yazar. Ekran görüntüsü alınamaz ve her şey uçtan uca şifrelidir.",
        "Groups made here and chats moved here show in no list. Notifications say only \"New message\". Screenshots are blocked and everything is end-to-end encrypted.",
    )
    val introHow get() = t("Açmak için sohbetlerdeki nuvelo yazısına 3 saniye içinde 6 kez dokun.", "To open it, tap the nuvelo title in Chats 6 times within 3 seconds.")
    val start get() = t("Başla", "Start")
    val choosePin get() = t("6 haneli bir PIN belirle", "Choose a 6-digit PIN")
    val repeatPin get() = t("PIN'i tekrar gir", "Enter the PIN again")
    val pinMismatch get() = t("PIN'ler aynı değil, baştan dene", "The PINs don't match, try again")
    val pinNote get() = t("Bu PIN gizli alanı açar ve şifreleme anahtarını yedekler. Yeni telefonda eski şifreli mesajların bununla geri gelir.", "This PIN opens the secret space and backs up your encryption key. On a new phone your old encrypted messages come back with it.")
    val enterPin get() = t("PIN'ini gir", "Enter your PIN")
    fun wrong(left: Int) = t("Yanlış PIN · $left deneme kaldı", "Wrong PIN · $left tries left")
    fun wait(ms: Long) = t("Çok fazla deneme. ${(ms + 59_999) / 60_000} dakika sonra tekrar dene.", "Too many tries. Try again in ${(ms + 59_999) / 60_000} min.")
    val gone get() = t("Yedek 10 yanlış denemeden sonra kalıcı olarak kilitlendi. Yeni bir anahtarla devam edebilirsin; eski şifreli mesajlar bu telefonda okunamaz.", "The backup was locked for good after 10 wrong tries. You can go on with a new key; old encrypted messages can't be read on this phone.")
    val failed get() = t("Şu an yapılamadı. İnternet bağlantını kontrol et.", "Couldn't do it now. Check your connection.")
    val useBiometric get() = t("Parmak izi veya yüz ile aç", "Open with fingerprint or face")
    val biometricNote get() = t("PIN yerine telefonun kilidiyle açılır", "Opens with the phone's lock instead of the PIN")
    val skip get() = t("Şimdi değil", "Not now")
    val turnOn get() = t("Aç", "Turn on")
    val forgot get() = t("PIN'imi unuttum", "Forgot PIN")
    val forgotBody get() = t(
        "Telefonunun kilidiyle kim olduğunu doğrula, sonra yeni bir PIN belirle. Bu telefondaki sohbetlerin kaybolmaz.",
        "Confirm it's you with the phone's lock, then choose a new PIN. Your chats on this phone stay.",
    )
    val forgotNoLock get() = t("Telefonda ekran kilidi yok. PIN'i sıfırlamak için çıkış yapıp Google hesabınla tekrar giriş yap.", "This phone has no screen lock. To reset the PIN, sign out and sign in with Google again.")
    val confirm get() = t("Doğrula", "Confirm")
    val cancel get() = t("Vazgeç", "Cancel")
    val newItem get() = t("Yeni", "New")
    val newSecretGroup get() = t("Gizli grup kur", "New secret group")
    val newSecretChat get() = t("Gizli sohbet başlat", "New secret chat")
    val moveChat get() = t("Var olan sohbeti buraya taşı", "Move a chat here")
    val empty get() = t("Burada henüz bir şey yok", "Nothing here yet")
    val emptyNote get() = t("Gizli bir grup kur ya da bir sohbeti buraya taşı.", "Make a secret group or move a chat here.")
    val invitation get() = t("Gizli sohbet daveti", "Secret chat invitation")
    val accept get() = t("Kabul et", "Accept")
    val invitedSection get() = t("Davet edilenler", "Invited")
    val invitePending get() = t("Davet bekliyor", "Invitation pending")
    val oldVersion get() = t("Nuvelo'yu güncellemesi gerekiyor", "Needs to update Nuvelo")
    val oldVersionNote get() = t(
        "Eski sürümdeki biri gizli sohbeti normal bir sohbet gibi görür ve şifreleme onda çalışmaz. Güncelleyince ekleyebilirsin.",
        "Someone on an older version sees a secret chat as an ordinary one, and encryption does not work for them. Add them once they update.",
    )
    fun oldVersionNames(names: String) = t("$names Nuvelo'yu güncelleyince eklenebilir.", "$names can be added once they update Nuvelo.")
    val decline get() = t("Reddet", "Decline")
    val inviteNote get() = t("Kabul edene kadar içeriği görünmez. Reddedersen kimseye bildirilmez.", "Nothing of it shows until you accept. If you decline, no one is told.")
    val takeOut get() = t("Gizli alandan çıkar", "Move out of secret space")
    val moveIn get() = t("Gizli alana taşı", "Move to secret space")
    val lockNow get() = t("Kilitle", "Lock")
    val settings get() = t("Gizli alan ayarları", "Secret space settings")
    val groupName get() = t("Grup adı", "Group name")
    val onlyFriends get() = t("Sadece arkadaşların eklenebilir. Davet edilenler kabul edince grubu görür.", "Only your friends can be added. Those invited see the group once they accept.")
    val noFriends get() = t("Henüz arkadaşın yok", "No friends yet")
    val create get() = t("Oluştur", "Create")
    val pickFriend get() = t("Kiminle?", "With whom?")
    val pickChat get() = t("Hangi sohbet?", "Which chat?")
    val moveConfirm get() = t("Bu sohbet gizli alana taşınsın mı? Listende ve bildirimlerde görünmez, bundan sonra uçtan uca şifreli olur. Karşı tarafta normal kalır.", "Move this chat to the secret space? It disappears from your list and notifications and is end-to-end encrypted from now on. For the other person it stays as it is.")
    val move get() = t("Taşı", "Move")
    val changePin get() = t("PIN'i değiştir", "Change PIN")
    val currentPin get() = t("Şu anki PIN'ini gir", "Enter your current PIN")
    val pinChanged get() = t("PIN değişti", "PIN changed")
    val autoLock get() = t("Otomatik kilit", "Auto-lock")
    val autoLockNote get() = t("Uygulamadan çıkınca ne kadar sonra kilitlensin", "How soon it locks after you leave the app")
    val atOnce get() = t("Hemen", "At once")
    val oneMin get() = t("1 dakika", "1 minute")
    val fiveMin get() = t("5 dakika", "5 minutes")
    val disappearing get() = t("Varsayılan kaybolan mesajlar", "Default disappearing messages")
    val disappearingNote get() = t("Buraya taşınan ve burada kurulan sohbetlerde", "For chats moved or made here")
    val off get() = t("Kapalı", "Off")
    val day get() = t("24 saat", "24 hours")
    val week get() = t("7 gün", "7 days")
    val callerName get() = t("Arayanın adı görünsün", "Show who calls")
    val callerNameNote get() = t("Kapalıysa gizli birinden gelen aramada \"Nuvelo araması\" yazar", "When off, a call from someone secret says \"Nuvelo call\"")
    val decoyPin get() = t("Sahte PIN", "Decoy PIN")
    val decoyNote get() = t("Baskı altında bunu girersen boş bir gizli alan açılır", "If forced, entering it opens an empty secret space")
    val decoySet get() = t("Sahte PIN ayarlandı", "Decoy PIN set")
    val decoyRemove get() = t("Sahte PIN'i kaldır", "Remove decoy PIN")
    val decoySame get() = t("Sahte PIN gerçek PIN'le aynı olamaz", "The decoy PIN can't be your real PIN")
    val turnOff get() = t("Gizli alanı kapat", "Turn off secret space")
    val turnOffBody get() = t("Tüm gizli sohbetler normal listene döner ve PIN silinir. Şifreli mesajlar şifreli kalır.", "All secret chats go back to your list and the PIN is removed. Encrypted messages stay encrypted.")
    val limitsTitle get() = t("Neyi korur, neyi korumaz", "What it protects, and what not")
    val limits get() = t(
        "Mesajlar, fotoğraflar, sesler ve dosyalar telefonunda şifrelenir; sunucularımız ve Google sadece okunamayan veri görür. Kimden kime, ne zaman mesaj gittiği, tepkiler ve anket oyları görülebilir. Telefonun açık haldeyken başkasının eline geçerse ya da PIN'in zorla öğrenilirse koruma kalmaz. PIN yedeği sunucumuzdaki bir kasaya bağlıdır ve 10 yanlış denemede kalıcı kilitlenir; ama sunucuyu yöneten biri kasanın kodunu değiştirirse teorik olarak bu sınırı kaldırabilir.",
        "Messages, photos, voice and files are encrypted on your phone; our servers and Google see only unreadable data. Who wrote to whom and when, reactions and poll votes can be seen. If your phone is taken while unlocked, or your PIN is forced out of you, nothing protects it. The PIN backup depends on a vault on our server that locks for good after 10 wrong tries; whoever runs the server could in theory change the vault's code to lift that limit.",
    )
    val e2eTitle get() = t("Uçtan uca şifreleme", "End-to-end encryption")
    val e2eBody get() = t(
        "Şifreli sohbetlerde mesajlar senin telefonunda kilitlenir ve sadece sohbettekilerin telefonunda açılır. Nuvelo dahil kimse okuyamaz.",
        "In encrypted chats, messages are locked on your phone and opened only on the phones in the chat. No one else, not even Nuvelo, can read them.",
    )
    val encryptNew get() = t("Yeni sohbetleri şifrele", "Encrypt new chats")
    val encryptNewNote get() = t("Açtığın sohbetler, herkesin uygulaması destekliyorsa şifrelenir. Eski mesajlar olduğu gibi kalır.", "Chats you open are encrypted when everyone's app supports it. Old messages stay as they are.")
    val backupPin get() = t("Yedek PIN'i", "Backup PIN")
    val backupOn get() = t("Ayarlı · anahtarın yedekli", "Set · your key is backed up")
    val backupOff get() = t("Yok · telefon değişirse eski şifreli mesajlar gelmez", "None · old encrypted messages won't come to a new phone")
    val makeBackup get() = t("Yedek PIN'i oluştur", "Create backup PIN")
    val restoreTitle get() = t("Şifreli mesajlarını geri getir", "Bring back your encrypted messages")
    val restoreBody get() = t("Bu hesabın şifreleme anahtarı yedekli. PIN'ini girince eski şifreli mesajların bu telefonda açılır.", "This account's key is backed up. Enter your PIN and your old encrypted messages open on this phone.")
    val startOver get() = t("PIN'i hatırlamıyorum, yeni anahtarla devam et", "I don't remember it, start with a new key")
    val startOverBody get() = t("Yeni bir anahtar oluşturulur. Eski şifreli mesajlar bu telefonda okunamaz, karşı taraflar güvenlik kodunun değiştiğini görür.", "A new key is made. Old encrypted messages can't be read on this phone, and others see that your safety code changed.")
    val goOn get() = t("Devam et", "Go on")
    val linkTitle get() = t("Bu cihazı bağla", "Link this device")
    val linkBody get() = t(
        "Şifreli sohbetlerin başka bir cihazında. Orada Nuvelo'yu aç: Ayarlar, Uçtan uca şifreleme, Cihaz bağla. Sonra bu kodu okut.",
        "Your encrypted chats are on another device. Open Nuvelo there: Settings, End-to-end encryption, Link a device. Then scan this code.",
    )
    val linkWaiting get() = t("Diğer cihazın okutması bekleniyor…", "Waiting for the other device to scan…")
    val linkExpired get() = t("Kodun süresi doldu.", "The code expired.")
    val newCode get() = t("Yeni kod", "New code")
    val usePin get() = t("Yedek PIN'imle getir", "Use my backup PIN")
    val useQr get() = t("Diğer cihazımdan QR ile bağla", "Link from my other device with a QR")
    val useThisKey get() = t("Bu cihazdaki anahtarı kullan", "Use this device's key")
    val useThisKeyBody get() = t(
        "Bu cihazın eski anahtarı yeniden hesabın anahtarı olur. Diğer cihazlarını bundan sonra QR ile yeniden bağlaman gerekir; karşı taraflar güvenlik kodunun değiştiğini görür.",
        "This device's old key becomes the account's again. Link your other devices again afterwards with a QR; others see that your safety code changed.",
    )
    val newKeyAnyway get() = t("Yeni anahtarla başla", "Start with a new key")
    val newKeyBody get() = t(
        "Yeni bir anahtar oluşturulur. Eski şifreli mesajlar okunamaz, diğer cihazlarındaki şifreli sohbetler de durur ve onları yeniden bağlaman gerekir.",
        "A new key is made. Old encrypted messages can't be read, encrypted chats stop on your other devices and you'll need to link them again.",
    )
    val linkDevice get() = t("Cihaz bağla", "Link a device")
    val linkDeviceNote get() = t("Tablet ya da başka bir telefonda şifreli sohbetlerini aç: oradaki QR kodunu okut", "Open your encrypted chats on a tablet or another phone: scan the QR shown there")
    val linkSent get() = t("Bağlandı. Şifreli sohbetlerin diğer cihazda da açılıyor.", "Linked. Your encrypted chats now open on the other device too.")
    val linkNotALink get() = t("Bu bir Nuvelo cihaz bağlama kodu değil.", "This isn't a Nuvelo device link code.")
    val linkOtherAccount get() = t("Bu kod başka bir hesabın. İki cihazda da aynı hesapla giriş yapmalısın.", "This code belongs to another account. Both devices must be signed in to the same account.")
    val linkNoKey get() = t("Hesabın şifreleme anahtarı bu cihazda değil. Kodu, şifreli sohbetlerin açıldığı cihazdan okut.", "Your account's key isn't on this device. Scan the code from the device where your encrypted chats open.")
    val bannerLinkTitle get() = t("Şifreli sohbetlerini bu cihaza getir", "Bring your encrypted chats to this device")
    val bannerLinkBody get() = t("Diğer cihazından QR ile bağla ya da yedek PIN'ini gir", "Link from your other device with a QR, or enter your backup PIN")
    val bannerLinkBodyNoPin get() = t("Diğer cihazından QR ile bağla", "Link from your other device with a QR")
    val preparing get() = t("Şifreleme hazırlanıyor…", "Preparing encryption…")
    val encrypted get() = t("Uçtan uca şifreli", "End-to-end encrypted")
    val encrypt get() = t("Uçtan uca şifrele", "Encrypt end to end")
    val noKeys get() = t("Sohbetteki herkesin uygulaması henüz şifrelemeyi desteklemiyor. Güncelledikleri zaman açabilirsin.", "Not everyone in this chat has an app that supports encryption yet. You can turn it on once they update.")
    val safetyCode get() = t("Güvenlik kodu", "Safety code")
    val safetyNote get() = t("Bu kod ikinizin telefonunda aynıysa mesajlarınız araya kimse girmeden şifreleniyor demektir. Yan yanayken birbirinizin kodunu okutun ya da rakamları karşılaştırın.", "If this code is the same on both your phones, your messages are encrypted with no one in between. Side by side, scan each other's code or compare the numbers.")
    val scan get() = t("Kodu tara", "Scan code")
    val markVerified get() = t("Doğrulandı olarak işaretle", "Mark as verified")
    val verified get() = t("Doğrulandı", "Verified")
    val scanOk get() = t("Kodlar eşleşti. Doğrulandı.", "The codes match. Verified.")
    val scanBad get() = t("Kodlar eşleşmedi. Doğru kişinin kodunu okuttuğundan emin ol.", "The codes don't match. Make sure you scanned the right person's code.")
    val noCode get() = t("Güvenlik kodu için ikinizin de şifreleme anahtarı olmalı.", "Both of you need an encryption key for a safety code.")
    fun keyChanged(name: String) = t("$name için güvenlik kodu değişti. Yeni telefon olabilir. Dokun: kodu gör.", "$name's safety code changed. Perhaps a new phone. Tap to see the code.")
    val unreadable get() = t("Şifreli mesaj · anahtarı bekleniyor", "Encrypted message · waiting for its key")
    val noForward get() = t("Gizli sohbetten iletilemez", "Can't forward from a secret chat")
    val noClaude get() = t("Claude şifreli sohbetleri okuyamaz", "Claude can't read encrypted chats")
    val shareHistory get() = t("Yeni üyeler eski mesajları görsün", "New members see past messages")
    val shareHistoryNote get() = t("Kapalıysa yeni gelen sadece katıldıktan sonrasını okuyabilir", "When off, newcomers read only what comes after they join")
    val notes get() = t("Gizli notlar", "Secret notes")
    val notesNote get() = t("Sadece senin gördüğün, şifreli notlar ve fotoğraflar", "Encrypted notes and photos only you see")
    val selfDelete get() = t("Grup kendini silsin", "Group deletes itself")
    val selfDeleteNote get() = t("Seçilen sürede grup ve içindeki her şey herkesten silinir", "After the chosen time the group and all in it are deleted for everyone")
    val never get() = t("Hiçbir zaman", "Never")
    val month get() = t("30 gün", "30 days")
    fun endsOn(date: String) = t("Bu grup $date tarihinde kendini silecek", "This group deletes itself on $date")
}

// ==================================================================== the PIN pad

/** The dots of a PIN being typed, shaking when it was wrong. */
@Composable
private fun PinDots(count: Int, shake: Int) {
    val offset = remember { Animatable(0f) }
    LaunchedEffect(shake) {
        if (shake > 0) {
            for (x in listOf(14f, -12f, 9f, -6f, 3f, 0f)) offset.animateTo(x, androidx.compose.animation.core.tween(45))
        }
    }
    Row(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(6) { i ->
            Box(
                Modifier.size(14.dp).clip(CircleShape)
                    .then(if (i < count) Modifier.background(TextPrimary) else Modifier.border(1.5.dp, TextSecondary, CircleShape)),
            )
        }
    }
}

/** A number pad of the app's own (the phone's keyboard could learn it): 1-9, 0, delete, and a fingerprint key. */
@Composable
private fun PinPad(onDigit: (Char) -> Unit, onDelete: () -> Unit, onBiometric: (() -> Unit)? = null) {
    val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf('b', '0', 'd'))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                row.forEach { k ->
                    val action: (() -> Unit)? = when (k) {
                        'b' -> onBiometric
                        'd' -> onDelete
                        else -> ({ onDigit(k) })
                    }
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .then(if (k.isDigit()) Modifier.background(DarkSurfaceVariant) else Modifier)
                            .then(if (action != null) Modifier.clickable(onClick = action) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            k.isDigit() -> Text(k.toString(), color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                            k == 'd' -> Icon(QMusicIcons.Back, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(24.dp))
                            onBiometric != null -> Icon(QMusicIcons.Lock, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * A whole PIN screen: a title, the dots, a line under them (an error), the pad. [onPin] gets the six digits and says
 * what to show under them (null: nothing, the dots clear).
 */
@Composable
private fun PinScreen(
    title: String,
    note: String?,
    busy: Boolean,
    message: String?,
    shake: Int,
    onPin: (String) -> Unit,
    onBiometric: (() -> Unit)? = null,
    footer: @Composable () -> Unit = {},
) {
    var pin by remember(title) { mutableStateOf("") }
    LaunchedEffect(shake) { if (shake > 0) pin = "" }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        Box(Modifier.size(56.dp).clip(CircleShape).background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
            Icon(QMusicIcons.Lock, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            Text(note, color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
        }
        Spacer(Modifier.height(28.dp))
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(22.dp)) else PinDots(pin.length, shake)
        }
        Box(Modifier.height(44.dp).padding(top = 10.dp), contentAlignment = Alignment.TopCenter) {
            if (message != null) Text(message, color = Color(0xFFE57373), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp))
        }
        PinPad(
            onDigit = { d ->
                if (!busy && pin.length < 6) {
                    pin += d
                    if (pin.length == 6) {
                        val done = pin
                        pin = ""
                        onPin(done)
                    }
                }
            },
            onDelete = { if (!busy) pin = pin.dropLast(1) },
            onBiometric = onBiometric,
        )
        Spacer(Modifier.height(16.dp))
        footer()
        Spacer(Modifier.height(24.dp))
    }
}

// ==================================================================== setting a PIN

/** Choosing a PIN twice; then [onDone]. */
@Composable
private fun ChoosePin(w: E2eWords, note: String?, onDone: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var shake by remember { mutableStateOf(0) }
    val f = first
    if (f == null) {
        PinScreen(w.choosePin, note, false, message, shake, onPin = { p ->
            message = null
            first = p
        })
    } else {
        PinScreen(w.repeatPin, note, false, message, shake, onPin = { p ->
            if (p == f) {
                onDone(p)
            } else {
                first = null
                message = w.pinMismatch
                shake++
            }
        })
    }
}

/** Sets the PIN (with the key backup), showing the wait; [onSet] after. */
@Composable
private fun SetPinFlow(w: E2eWords, note: String?, onSet: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (busy) {
        Centered { CircularProgressIndicator(color = Ink, strokeWidth = 2.dp) }
        return
    }
    Column {
        if (error != null) Text(error!!, color = Color(0xFFE57373), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
        ChoosePin(w, note) { pin ->
            busy = true
            scope.launch {
                E2e.api.setPin(pin)
                    .onSuccess { onSet() }
                    .onFailure { error = w.failed }
                busy = false
            }
        }
    }
}

// ==================================================================== checking the PIN

/** The lock: the PIN (or the fingerprint when chosen) opens the space; the decoy PIN opens an empty one. */
@Composable
private fun SecretLock(w: E2eWords, onForgot: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lock = rememberAppLock()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var shake by remember { mutableStateOf(0) }
    val canBio = SecretSpace.biometric && lock != null && lock.available
    val askBio: () -> Unit = { lock?.authenticate(w.secretSpace, w.useBiometric) { ok -> if (ok) SecretSpace.unlock(false) } }
    LaunchedEffect(Unit) { if (canBio) askBio() }
    PinScreen(
        title = w.enterPin,
        note = null,
        busy = busy,
        message = message,
        shake = shake,
        onPin = { pin ->
            busy = true
            scope.launch {
                when (val r = E2e.api.checkPin(pin)) {
                    PinResult.Ok -> SecretSpace.unlock(false)
                    PinResult.Decoy -> SecretSpace.unlock(true)
                    is PinResult.Wrong -> { message = w.wrong(r.left); shake++ }
                    is PinResult.Wait -> { message = w.wait(r.ms); shake++ }
                    PinResult.Gone -> { message = w.gone; shake++ }
                    is PinResult.Failed -> { message = w.failed; shake++ }
                }
                busy = false
            }
        },
        onBiometric = if (canBio) askBio else null,
        footer = { TextButton(onClick = onForgot) { Text(w.forgot, color = TextSecondary) } },
    )
}

/** On a new phone: the PIN brings the key (and the old encrypted messages) back, or a new key starts. */
@Composable
internal fun RestoreKey(w: E2eWords, onDone: () -> Unit, onQr: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var shake by remember { mutableStateOf(0) }
    var askStartOver by remember { mutableStateOf(false) }
    PinScreen(
        title = w.restoreTitle,
        note = w.restoreBody,
        busy = busy,
        message = message,
        shake = shake,
        onPin = { pin ->
            busy = true
            scope.launch {
                when (val r = E2e.api.restore(pin)) {
                    PinResult.Ok, PinResult.Decoy -> onDone()
                    is PinResult.Wrong -> { message = w.wrong(r.left); shake++ }
                    is PinResult.Wait -> { message = w.wait(r.ms); shake++ }
                    PinResult.Gone -> { message = w.gone; shake++ }
                    is PinResult.Failed -> { message = w.failed; shake++ }
                }
                busy = false
            }
        },
        footer = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                onQr?.let { TextButton(onClick = it) { Text(w.useQr, color = TextPrimary, textAlign = TextAlign.Center) } }
                TextButton(onClick = { askStartOver = true }) { Text(w.startOver, color = TextSecondary, textAlign = TextAlign.Center) }
            }
        },
    )
    if (askStartOver) {
        AlertDialog(
            onDismissRequest = { askStartOver = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(w.startOver, color = TextPrimary) },
            text = { Text(w.startOverBody, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    askStartOver = false
                    scope.launch { E2e.api.startOver().onSuccess { onDone() } }
                }) { Text(w.goOn, color = Color(0xFFE57373)) }
            },
            dismissButton = { TextButton(onClick = { askStartOver = false }) { Text(w.cancel, color = TextSecondary) } },
        )
    }
}

/** The account's key is not on this device: from the other device (a QR), from the backup (the PIN), or anew. */
@Composable
internal fun BringKey(w: E2eWords, onDone: () -> Unit) {
    val hasBackup by E2e.api.hasBackup.collectAsState()
    var byPin by remember(hasBackup) { mutableStateOf(false) }
    if (byPin && hasBackup) RestoreKey(w, onDone, onQr = { byPin = false })
    else LinkThisDevice(w, onDone, onPin = if (hasBackup) ({ byPin = true }) else null)
}

/** The new device's side of linking: a QR for the device that has the key to scan. */
@Composable
private fun LinkThisDevice(w: E2eWords, onDone: () -> Unit, onPin: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    val hasOwnKey by E2e.api.hasOwnKey.collectAsState()
    var code by remember { mutableStateOf<String?>(null) }
    var expired by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        expired = false
        code = null
        if (E2e.api.awaitLink { code = it }) onDone() else expired = true
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(w.linkTitle, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(w.linkBody, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp), contentAlignment = Alignment.Center) {
            val c = code
            if (c != null && !expired) QrCode(c, Modifier.fillMaxSize())
            else if (!expired) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(12.dp))
        if (expired) {
            Text(w.linkExpired, color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.width(220.dp)) { WhiteButton(w.newCode) { attempt++ } }
        } else {
            Text(w.linkWaiting, color = TextMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        onPin?.let { TextButton(onClick = it) { Text(w.usePin, color = TextPrimary, textAlign = TextAlign.Center) } }
        TextButton(onClick = { asking = true }) {
            Text(if (hasOwnKey) w.useThisKey else w.newKeyAnyway, color = TextSecondary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(if (hasOwnKey) w.useThisKey else w.newKeyAnyway, color = TextPrimary) },
            text = { Text(if (hasOwnKey) w.useThisKeyBody else w.newKeyBody, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    scope.launch { (if (hasOwnKey) E2e.api.useThisDevice() else E2e.api.startOver()).onSuccess { onDone() } }
                }) { Text(w.goOn, color = Color(0xFFE57373)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(w.cancel, color = TextSecondary) } },
        )
    }
}

// ==================================================================== the secret space

/** The page behind the six taps: set up, locked, or open. */
@Composable
internal fun SecretPage(controller: ChatController, s: ChatStrings, me: ChatUser) {
    val w = remember(ChatMusic.english) { E2eWords(ChatMusic.english) }
    val ready by E2e.api.ready.collectAsState()
    val needsRestore by E2e.api.needsRestore.collectAsState()
    val hasPin by E2e.api.hasPin.collectAsState()
    val unlocked by SecretSpace.unlocked.collectAsState()
    var setup by remember { mutableStateOf(0) }
    var forgot by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    val lock = rememberAppLock()
    SecureScreen()
    Column(Modifier.fillMaxSize().background(BgRoot)) {
        when {
            needsRestore -> {
                TopBar(w.secretSpace, { controller.pop() })
                BringKey(w) { SecretSpace.unlock(false) }
            }
            !ready -> {
                TopBar(w.secretSpace, { controller.pop() })
                Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Ink, strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(w.preparing, color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
            resetting -> {
                TopBar(w.changePin, { resetting = false })
                SetPinFlow(w, w.pinNote) {
                    resetting = false
                    SecretSpace.unlock(false)
                }
            }
            !hasPin -> {
                TopBar(w.secretSpace, { controller.pop() })
                when (setup) {
                    0 -> SecretIntro(w) { setup = 1 }
                    1 -> SetPinFlow(w, w.pinNote) { setup = if (lock != null && lock.available) 2 else 3 }
                    2 -> BiometricOffer(w, onYes = { SecretSpace.biometric = true; setup = 3 }, onNo = { setup = 3 })
                    else -> LaunchedEffect(Unit) { SecretSpace.unlock(false) }
                }
            }
            !unlocked -> {
                TopBar(w.secretSpace, { controller.pop() })
                SecretLock(w) { forgot = true }
            }
            else -> SecretHome(controller, s, me, w)
        }
    }
    if (forgot) {
        AlertDialog(
            onDismissRequest = { forgot = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(w.forgot, color = TextPrimary) },
            text = { Text(if (lock != null && lock.available) w.forgotBody else w.forgotNoLock, color = TextSecondary) },
            confirmButton = {
                if (lock != null && lock.available) {
                    TextButton(onClick = {
                        forgot = false
                        lock.authenticate(w.forgot, w.confirm) { ok -> if (ok) resetting = true }
                    }) { Text(w.confirm, color = TextPrimary) }
                }
            },
            dismissButton = { TextButton(onClick = { forgot = false }) { Text(w.cancel, color = TextSecondary) } },
        )
    }
}

@Composable
private fun SecretIntro(w: E2eWords, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(84.dp).clip(CircleShape).background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
            Icon(QMusicIcons.Lock, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text(w.introTitle, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(w.introBody, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(w.introHow, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Text(w.limitsTitle, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(w.limits, color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Box(Modifier.width(260.dp)) { WhiteButton(w.start, onClick = onStart) }
    }
}

@Composable
private fun BiometricOffer(w: E2eWords, onYes: () -> Unit, onNo: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(QMusicIcons.Lock, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(18.dp))
        Text(w.useBiometric, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(w.biometricNote, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Box(Modifier.width(240.dp)) { WhiteButton(w.turnOn, onClick = onYes) }
        TextButton(onClick = onNo) { Text(w.skip, color = TextSecondary) }
    }
}

/** Open: the secret conversations (invitations on top), New, the settings, Lock. */
@Composable
private fun SecretHome(controller: ChatController, s: ChatStrings, me: ChatUser, w: E2eWords) {
    val all by controller.secretThreads.collectAsState()
    val decoy by SecretSpace.decoy.collectAsState()
    // The conversation with oneself is the notes (on top), not one of the list.
    val threads = if (decoy) emptyList() else all.filter { it.other.uid != me.uid }
    val now = rememberNow()
    var menu by remember { mutableStateOf(false) }
    // Nothing touched for five minutes: it locks.
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            if (Clock.System.now().toEpochMilliseconds() - SecretSpace.lastTouch > SecretSpace.IDLE_LOCK_MS) SecretSpace.lock()
        }
    }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                SecretSpace.lastTouch = Clock.System.now().toEpochMilliseconds()
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                w.secret,
                {
                    SecretSpace.lock()
                    controller.closeSecret()
                },
                actions = {
                    IconButton(onClick = { controller.push(ChatRoute.SecretSettings) }) { Icon(QMusicIcons.Settings, contentDescription = w.settings, tint = TextPrimary) }
                    IconButton(onClick = {
                        SecretSpace.lock()
                        controller.closeSecret()
                    }) { Icon(QMusicIcons.Lock, contentDescription = w.lockNow, tint = TextPrimary) }
                },
            )
            if (!decoy) {
                Row(
                    Modifier.fillMaxWidth().clickable { controller.openSecretNotes(me) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(QMusicIcons.Edit, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(w.notes, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(w.notesNote, color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
            if (threads.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(QMusicIcons.Lock, contentDescription = null, tint = TextMuted, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(w.empty, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(w.emptyNote, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(threads, key = { it.id }) { t ->
                        if (t.invite) {
                            InviteRow(t, w, onAccept = { controller.acceptInvite(t) }, onDecline = { controller.declineInvite(t) })
                        } else {
                            ThreadRow(
                                t, me, s,
                                onAvatar = { if (t.isGroup) controller.openGroupInfo(t.other) else controller.openProfile(t.other) },
                                actions = if (t.isGroup) emptyList() else listOf(w.takeOut to { controller.setSecret(t, false) }),
                                now = now,
                            ) { controller.openConversation(t.other) }
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(18.dp)).background(Ink).clickable { menu = true }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(QMusicIcons.Add, contentDescription = null, tint = InkInverse, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(w.newItem, color = InkInverse, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
                DropdownMenuItem(text = { Text(w.newSecretGroup, color = TextPrimary) }, leadingIcon = { Icon(QMusicIcons.Group, null, tint = TextPrimary) }, onClick = { menu = false; controller.push(ChatRoute.SecretNewGroup) })
                DropdownMenuItem(text = { Text(w.newSecretChat, color = TextPrimary) }, leadingIcon = { Icon(QMusicIcons.Chat, null, tint = TextPrimary) }, onClick = { menu = false; controller.push(ChatRoute.SecretStartChat) })
                DropdownMenuItem(text = { Text(w.moveChat, color = TextPrimary) }, leadingIcon = { Icon(QMusicIcons.Archive, null, tint = TextPrimary) }, onClick = { menu = false; controller.push(ChatRoute.SecretMove) })
            }
        }
    }
}

/** An invitation to a secret group: its name only, and Accept / Decline. */
@Composable
private fun InviteRow(t: ChatThread, w: E2eWords, onAccept: () -> Unit, onDecline: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(DarkSurfaceVariant).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChatAvatar(t.other, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(w.invitation, color = TextSecondary, fontSize = 12.sp)
                Text(t.other.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(w.inviteNote, color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { WhiteButton(w.accept, busy = busy) { busy = true; onAccept() } }
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).border(1.dp, TextMuted, RoundedCornerShape(26.dp)).clickable(enabled = !busy) { busy = true; onDecline() },
                contentAlignment = Alignment.Center,
            ) { Text(w.decline, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * Of [uids], those whose app is older than end-to-end encryption (no key published): a secret chat would reach them
 * as an ordinary one, so they are asked in once they update.
 */
@Composable
internal fun rememberOldVersions(uids: List<String>): Set<String> {
    val ready by E2e.api.ready.collectAsState()
    val state = androidx.compose.runtime.produceState(emptySet<String>(), uids, ready) {
        if (!ready) return@produceState
        value = kotlinx.coroutines.coroutineScope {
            uids.map { u -> async { u to E2e.api.allHaveKeys(listOf(u)) } }.map { it.await() }
        }.filter { !it.second }.map { it.first }.toSet()
    }
    return state.value
}

/** A friend to pick, with a tick when [picked]; [note]: cannot be picked, and why. */
@Composable
private fun PersonRow(user: ChatUser, picked: Boolean?, note: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(if (note == null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp).then(if (note != null) Modifier.alpha(0.5f) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(user, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(user.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (note != null) Text(note, color = TextSecondary, fontSize = 13.sp)
            else if (user.username.isNotEmpty()) Text("@" + user.username, color = TextSecondary, fontSize = 13.sp)
        }
        if (picked != null && note == null) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).then(if (picked) Modifier.background(Ink) else Modifier.border(1.5.dp, TextSecondary, CircleShape)),
                contentAlignment = Alignment.Center,
            ) { if (picked) Icon(QMusicIcons.Check, contentDescription = null, tint = InkInverse, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
internal fun SecretNewGroupPage(controller: ChatController) {
    val w = remember(ChatMusic.english) { E2eWords(ChatMusic.english) }
    val decoy by SecretSpace.decoy.collectAsState()
    val all by controller.friends.collectAsState()
    val friends = if (decoy) emptyList() else all.filter { it.uid != CLAUDE_BOT_UID && !it.isGroup }
    var name by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var life by remember { mutableStateOf(0L) }
    SecureScreen()
    Column(Modifier.fillMaxSize().background(BgRoot)) {
        TopBar(w.newSecretGroup, { controller.pop() })
        Field(name, { name = it.take(50) }, w.groupName, modifier = Modifier.padding(horizontal = 16.dp))
        ChoiceRow(w.selfDelete, w.selfDeleteNote, listOf(0L to w.never, 24 * 3600_000L to w.day, 7 * 24 * 3600_000L to w.week, 30 * 24 * 3600_000L to w.month), life) { life = it }
        Text(w.onlyFriends, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        if (friends.isEmpty()) {
            Text(w.noFriends, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
        }
        val old = rememberOldVersions(friends.map { it.uid })
        if (old.isNotEmpty()) Text(w.oldVersionNote, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(friends, key = { it.uid }) { f ->
                PersonRow(f, f.uid in picked, if (f.uid in old) w.oldVersion else null) { if (f.uid in picked) picked.remove(f.uid) else picked.add(f.uid) }
            }
        }
        Box(Modifier.navigationBarsPadding().padding(16.dp)) {
            WhiteButton(w.create, enabled = name.isNotBlank() && picked.isNotEmpty() && !decoy, busy = busy) {
                busy = true
                controller.createSecretGroup(name, friends.filter { it.uid in picked && it.uid !in old }, if (life > 0) Clock.System.now().toEpochMilliseconds() + life else 0L)
            }
        }
    }
}

@Composable
internal fun SecretStartChatPage(controller: ChatController) {
    val w = remember(ChatMusic.english) { E2eWords(ChatMusic.english) }
    val decoy by SecretSpace.decoy.collectAsState()
    val all by controller.friends.collectAsState()
    val friends = if (decoy) emptyList() else all.filter { it.uid != CLAUDE_BOT_UID && !it.isGroup }
    SecureScreen()
    Column(Modifier.fillMaxSize().background(BgRoot)) {
        TopBar(w.newSecretChat, { controller.pop() })
        Text(w.pickFriend, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        if (friends.isEmpty()) Text(w.noFriends, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
        val old = rememberOldVersions(friends.map { it.uid })
        if (old.isNotEmpty()) Text(w.oldVersionNote, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(friends, key = { it.uid }) { f -> PersonRow(f, null, if (f.uid in old) w.oldVersion else null) { controller.startSecretChat(f) } }
        }
    }
}

@Composable
internal fun SecretMovePage(controller: ChatController, s: ChatStrings, me: ChatUser) {
    val w = remember(ChatMusic.english) { E2eWords(ChatMusic.english) }
    val decoy by SecretSpace.decoy.collectAsState()
    val list by controller.threads.collectAsState()
    val threads = if (decoy) emptyList() else list.orEmpty().filter { it.other.uid != CLAUDE_BOT_UID }
    var asking by remember { mutableStateOf<ChatThread?>(null) }
    SecureScreen()
    Column(Modifier.fillMaxSize().background(BgRoot)) {
        TopBar(w.moveChat, { controller.pop() })
        Text(w.pickChat, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(threads, key = { it.id }) { t -> ThreadRow(t, me, s, onAvatar = { asking = t }) { asking = t } }
        }
    }
    asking?.let { t ->
        AlertDialog(
            onDismissRequest = { asking = null },
            containerColor = DarkSurfaceVariant,
            title = { Text(t.other.name, color = TextPrimary) },
            text = { Text(w.moveConfirm, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    controller.setSecret(t, true)
                    asking = null
                    controller.pop()
                }) { Text(w.move, color = TextPrimary) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(w.cancel, color = TextSecondary) } },
        )
    }
}

// ==================================================================== settings

@Composable
internal fun SecretSettingsPage(controller: ChatController) {
    val w = remember(ChatMusic.english) { E2eWords(ChatMusic.english) }
    val scope = rememberCoroutineScope()
    val lock = rememberAppLock()
    val secretThreads by controller.secretThreads.collectAsState()
    var page by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    var askOff by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    SecureScreen()
    Column(Modifier.fillMaxSize().background(BgRoot)) {
        when (page) {
            "change" -> {
                var checked by remember { mutableStateOf(false) }
                TopBar(w.changePin, { page = "" })
                if (!checked) {
                    var busy by remember { mutableStateOf(false) }
                    var message by remember { mutableStateOf<String?>(null) }
                    var shake by remember { mutableStateOf(0) }
                    PinScreen(w.currentPin, null, busy, message, shake, onPin = { pin ->
                        busy = true
                        scope.launch {
                            when (val r = E2e.api.checkPin(pin)) {
                                PinResult.Ok -> checked = true
                                is PinResult.Wrong -> { message = w.wrong(r.left); shake++ }
                                is PinResult.Wait -> { message = w.wait(r.ms); shake++ }
                                else -> { message = w.failed; shake++ }
                            }
                            busy = false
                        }
                    })
                } else {
                    SetPinFlow(w, w.pinNote) {
                        page = ""
                        info = w.pinChanged
                    }
                }
            }
            "decoy" -> {
                TopBar(w.decoyPin, { page = "" })
                var message by remember { mutableStateOf<String?>(null) }
                Column {
                    if (message != null) Text(message!!, color = Color(0xFFE57373), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
                    ChoosePin(w, w.decoyNote) { pin ->
                        scope.launch {
                            // The decoy may not be the real PIN.
                            if (E2e.api.checkPin(pin) == PinResult.Ok) {
                                message = w.decoySame
                            } else {
                                E2e.api.setDecoyPin(pin)
                                page = ""
                                info = w.decoySet
                                refresh++
                            }
                        }
                    }
                }
            }
            else -> {
                TopBar(w.settings, { controller.pop() })
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SettingRow(w.changePin, null) { page = "change" }
                    if (lock != null && lock.available) {
                        var bio by remember { mutableStateOf(SecretSpace.biometric) }
                        SettingSwitch(w.useBiometric, w.biometricNote, bio) { on -> bio = on; SecretSpace.biometric = on }
                    }
                    var auto by remember { mutableStateOf(SecretSpace.autoLockMs) }
                    ChoiceRow(w.autoLock, w.autoLockNote, listOf(0L to w.atOnce, 60_000L to w.oneMin, 300_000L to w.fiveMin), auto) { v -> auto = v; SecretSpace.autoLockMs = v }
                    var ttl by remember { mutableStateOf(SecretSpace.defaultTtl) }
                    ChoiceRow(w.disappearing, w.disappearingNote, listOf(0L to w.off, 24 * 3600_000L to w.day, 7 * 24 * 3600_000L to w.week), ttl) { v -> ttl = v; SecretSpace.defaultTtl = v }
                    var caller by remember { mutableStateOf(SecretSpace.showCallerName) }
                    SettingSwitch(w.callerName, w.callerNameNote, caller) { on -> caller = on; SecretSpace.showCallerName = on }
                    val hasDecoy = remember(refresh) { E2e.api.hasDecoy() }
                    if (hasDecoy) {
                        SettingRow(w.decoyRemove, w.decoyNote) {
                            E2e.api.setDecoyPin(null)
                            refresh++
                        }
                    } else {
                        SettingRow(w.decoyPin, w.decoyNote) { page = "decoy" }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(w.limitsTitle, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
                    Text(w.limits, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        w.turnOff,
                        color = Color(0xFFE57373),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().clickable { askOff = true }.padding(16.dp),
                    )
                    Spacer(Modifier.navigationBarsPadding().height(24.dp))
                }
            }
        }
    }
    info?.let { text ->
        LaunchedEffect(text) {
            controller.reportInfo(text)
            info = null
        }
    }
    if (askOff) {
        AlertDialog(
            onDismissRequest = { askOff = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(w.turnOff, color = TextPrimary) },
            text = { Text(w.turnOffBody, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    askOff = false
                    secretThreads.forEach { t -> controller.setSecret(t, false) }
                    scope.launch {
                        E2e.api.clearPin()
                        SecretSpace.lock()
                        controller.closeSecret()
                    }
                }) { Text(w.turnOff, color = Color(0xFFE57373)) }
            },
            dismissButton = { TextButton(onClick = { askOff = false }) { Text(w.cancel, color = TextSecondary) } },
        )
    }
}

@Composable
private fun SettingRow(title: String, note: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, color = TextPrimary, fontSize = 15.sp)
        if (note != null) Text(note, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun ChoiceRow(title: String, note: String, choices: List<Pair<Long, String>>, value: Long, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 15.sp)
                Text(note, color = TextSecondary, fontSize = 12.sp)
            }
            Text(choices.firstOrNull { it.first == value }?.second ?: choices.first().second, color = TextSecondary, fontSize = 14.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = DarkSurfaceVariant) {
            choices.forEach { (v, label) -> DropdownMenuItem(text = { Text(label, color = TextPrimary) }, onClick = { open = false; onPick(v) }) }
        }
    }
}

// ==================================================================== encryption, the safety code

/** What encryption is, the backup PIN, encrypting new chats; and bringing the key back on a new phone. */
@Composable
internal fun EncryptionPage(controller: ChatController) {
    val w = remember(ChatMusic.english) { E2eWords(ChatMusic.english) }
    val needsRestore by E2e.api.needsRestore.collectAsState()
    val hasPin by E2e.api.hasPin.collectAsState()
    var settingPin by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var linkResult by remember { mutableStateOf<LinkResult?>(null) }
    val linker = rememberQrScanner { text -> scope.launch { linkResult = E2e.api.sendLink(text) } }
    Column(Modifier.fillMaxSize().background(BgRoot)) {
        when {
            needsRestore -> {
                TopBar(w.e2eTitle, { controller.pop() })
                BringKey(w) {}
            }
            settingPin -> {
                TopBar(w.backupPin, { settingPin = false })
                SetPinFlow(w, w.pinNote) { settingPin = false }
            }
            else -> {
                TopBar(w.e2eTitle, { controller.pop() })
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
                            Icon(QMusicIcons.Lock, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(w.e2eBody, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                    var auto by remember { mutableStateOf(SecretSpace.encryptNewChats) }
                    SettingSwitch(w.encryptNew, w.encryptNewNote, auto) { on -> auto = on; SecretSpace.encryptNewChats = on }
                    SettingRow(if (hasPin) w.changePin else w.makeBackup, if (hasPin) w.backupOn else w.backupOff) { settingPin = true }
                    if (linker != null) SettingRow(w.linkDevice, w.linkDeviceNote) { linker() }
                    linkResult?.let { r ->
                        Text(
                            when (r) {
                                LinkResult.Ok -> w.linkSent
                                LinkResult.NotALink -> w.linkNotALink
                                LinkResult.OtherAccount -> w.linkOtherAccount
                                LinkResult.NoKey -> w.linkNoKey
                                LinkResult.Failed -> w.failed
                            },
                            color = if (r == LinkResult.Ok) Color(0xFF1DAA61) else Color(0xFFE57373),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(w.limitsTitle, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
                    Text(w.limits, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    Spacer(Modifier.navigationBarsPadding().height(24.dp))
                }
            }
        }
    }
}

/** The 60-digit safety code and its QR: checked side by side, or scanned. */
@Composable
internal fun SafetyCodePage(controller: ChatController, user: ChatUser) {
    val w = remember(ChatMusic.english) { E2eWords(ChatMusic.english) }
    val scope = rememberCoroutineScope()
    var code by remember(user.uid) { mutableStateOf<SafetyNumber?>(null) }
    var loaded by remember(user.uid) { mutableStateOf(false) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(user.uid) {
        code = E2e.api.safetyNumber(user.uid)
        loaded = true
    }
    val scanner = rememberQrScanner { text ->
        scope.launch {
            result = E2e.api.checkScanned(user.uid, text)
            code = E2e.api.safetyNumber(user.uid)
        }
    }
    Column(Modifier.fillMaxSize().background(BgRoot)) {
        TopBar(w.safetyCode, { controller.pop() })
        val c = code
        when {
            !loaded -> Centered { CircularProgressIndicator(color = Ink, strokeWidth = 2.dp) }
            c == null -> Text(w.noCode, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChatAvatar(user, 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(user.name, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (c.verified) {
                        Spacer(Modifier.width(8.dp))
                        Text(w.verified, color = Color(0xFF1DAA61), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Box(Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
                    QrCode(c.qr, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(18.dp))
                c.groups.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                        row.forEach { g -> Text(g, color = TextPrimary, fontSize = 19.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(w.safetyNote, color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
                result?.let { ok ->
                    Spacer(Modifier.height(12.dp))
                    Text(if (ok) w.scanOk else w.scanBad, color = if (ok) Color(0xFF1DAA61) else Color(0xFFE57373), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(20.dp))
                if (scanner != null) Box(Modifier.width(260.dp)) { WhiteButton(w.scan, onClick = scanner) }
                if (!c.verified) {
                    TextButton(onClick = {
                        E2e.api.markVerified(user.uid)
                        scope.launch { code = E2e.api.safetyNumber(user.uid) }
                    }) { Text(w.markVerified, color = TextSecondary) }
                }
            }
        }
    }
}

package com.nuvelo.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.WebBack
import com.qmusic.ui.chat.AppHome
import com.qmusic.ui.chat.ChatAccount
import com.qmusic.ui.chat.ChatApp
import com.qmusic.ui.chat.ChatController
import com.qmusic.ui.chat.ChatStrings
import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.WebPush
import com.qmusic.ui.chat.rememberChatBackend
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The web is the messages only: the phone's messages section, full screen, as the home page. */
@Composable
fun WebChat() {
    val backend = rememberChatBackend()
    val scope = rememberCoroutineScope()
    val controller = remember(backend) { backend?.let { ChatController(it, scope) } }
    DisposableEffect(Unit) {
        AppHome.messagesFirst.value = true
        // Pages change as on a computer (a quick fade), and Esc goes back as the browser's back does.
        com.qmusic.ui.motion.PageMotion.web = true
        com.qmusic.ui.chat.ChatPlatform.web = true
        onEscape { WebBack.dispatchBack() }
        onHistoryBack { WebBack.onBrowserBack() }
        com.qmusic.ui.chat.E2eSelfTest.install()
        onVisibility { visible -> com.qmusic.ui.chat.WebPage.visible.value = visible }
        // A sign-in made by Nuvelo's server in the link (#t=...): used to try the page with the test account.
        takeTokenFromLink()?.let { token -> authCustom(token) }
        onDispose {}
    }
    if (controller == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nuvelo") }
        return
    }
    val strings = ChatStrings.Turkish
    val account by controller.account.collectAsState()
    val ready = account as? ChatAccount.Ready
    // What went wrong is also written to the console (to find it later).
    LaunchedEffect(controller) { controller.error.collect { e -> if (e != null) logJs("error: $e") } }
    // A notification tapped: its conversation opens (the page was open, or it was opened by the tap).
    LaunchedEffect(controller) {
        try {
            onPushOpen { json -> openFrom(controller, json) }
            takeOpenFromLink()?.let { openFrom(controller, it) }
        } catch (e: Throwable) {
            logJs("open: $e")
        }
    }
    // Notifications already allowed on this browser: its address goes to the account.
    LaunchedEffect(ready?.me?.uid) {
        if (ready == null) return@LaunchedEffect
        val token = runCatching { pushToken().await<kotlin.js.JsString?>()?.toString() }.getOrNull()
        if (token != null && token != WebPush.current) {
            WebPush.current = token
            com.qmusic.ui.chat.FirebaseChatBackend.get().onNewPushToken()
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // A computer (or a tablet across): the list beside the open conversation, and Enter sends.
        val wide = maxWidth >= 840.dp
        val inCall = controller.calls?.state?.collectAsState()?.value != null
        // A call takes the whole page (nothing else drawn under it, so its picture can show through).
        if (!inCall) androidx.compose.runtime.CompositionLocalProvider(
            com.qmusic.ui.chat.LocalWideLayout provides wide,
            com.qmusic.ui.chat.LocalEnterSends provides (wide || !touchScreen()),
            com.qmusic.ui.chat.LocalMouse provides !touchScreen(),
        ) {
            ChatApp(
                controller = controller,
                s = strings,
                shareables = { emptyList() },
                onOpenShare = { item -> openShared(item.kind.name, item.id) },
                onExit = {},
            )
        }
        // A call (ringing, or on): over everything, as on the phone.
        controller.calls?.let { service ->
            val call by service.state.collectAsState()
            if (call != null) {
                val ask = com.qmusic.ui.chat.rememberCallPermissions()
                com.qmusic.ui.chat.CallScreen(
                    service = service,
                    s = strings,
                    onAccept = { video -> ask(video) { granted -> if (granted) service.accept() else service.decline() } },
                )
            }
        }
        if (ready != null) NotificationsAsk()
    }
}

private fun openFrom(controller: ChatController, json: String) {
    val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
    fun s(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
    val uid = s("sender").ifEmpty { return }
    controller.openConversation(ChatUser(uid, s("name").ifEmpty { "?" }, s("username"), s("photo").ifEmpty { null }))
}

/**
 * Once signed in: asked whether new messages may be told (a tap must ask the browser). On an iPhone outside the home
 * screen, how to add it there first (notifications come only then).
 */
@Composable
private fun NotificationsAsk() {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val asked = storeGet("nuvelo.pushAsked")?.toDoubleOrNull() ?: 0.0
        // Asked within three days already ("Later"): not again yet.
        if (nowMs() - asked < 3 * 24 * 3600_000.0) return@LaunchedEffect
        kotlinx.coroutines.delay(2_500)
        state = try { if (iosBrowser()) "ios" else pushState().text() } catch (e: Throwable) { logJs("push: $e"); null }
    }
    val s = state
    if (dismissed || (s != "default" && s != "ios")) return
    fun later() {
        storeSet("nuvelo.pushAsked", nowMs().toString())
        dismissed = true
    }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)).clickable(onClick = ::later), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(28.dp).widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(DarkSurfaceVariant).clickable(enabled = false) {}.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (s == "ios") "Bildirimler için ana ekrana ekle" else "Mesajlar gelince haber verelim mi?",
                color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (s == "ios") {
                    "iPhone'da bildirimler, Nuvelo ana ekrandan açılınca gelir. Safari'de alttaki Paylaş düğmesine bas, \"Ana Ekrana Ekle\"yi seç, sonra Nuvelo'yu ana ekrandan aç."
                } else {
                    "Nuvelo kapalıyken de yeni mesajlar bildirim olarak gelsin."
                },
                color = TextSecondary, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (s == "ios") "Tamam" else "Sonra",
                    color = TextSecondary, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = ::later).padding(horizontal = 18.dp, vertical = 10.dp),
                )
                if (s != "ios") {
                    Text(
                        "Aç",
                        color = InkInverse, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Ink).clickable {
                            // Asked right in the tap (browsers allow it only then).
                            val asking = pushEnable()
                            dismissed = true
                            storeSet("nuvelo.pushAsked", nowMs().toString())
                            scope.launch {
                                val token = runCatching { asking.await<kotlin.js.JsString?>()?.toString() }.getOrNull() ?: return@launch
                                WebPush.current = token
                                com.qmusic.ui.chat.FirebaseChatBackend.get().onNewPushToken()
                            }
                        }.padding(horizontal = 22.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** A screen touched with fingers (a phone or tablet): Enter makes a new line there, as on the phone. */
@JsFun("() => window.matchMedia('(pointer: coarse)').matches")
private external fun touchScreen(): Boolean

/** Esc pressed anywhere on the page (not while a text box is being written in with a selection open). */
@JsFun("(f) => document.addEventListener('keydown', (e) => { if (e.key === 'Escape') f(); })")
private external fun onEscape(f: () -> Unit)

@JsFun("() => Date.now()")
private external fun nowMs(): Double

@JsFun("(f) => document.addEventListener('visibilitychange', () => f(document.visibilityState === 'visible'))")
private external fun onVisibility(f: (Boolean) -> Unit)

@JsFun("() => { const m = /^#t=(.+)$/.exec(location.hash); if (!m) return null; history.replaceState(history.state, '', location.pathname + location.search); return decodeURIComponent(m[1]); }")
private external fun takeTokenFromLink(): String?

/** A notification opened the page: whose conversation (from the link's ?open=...), as JSON. */
@JsFun(
    """() => {
        const q = new URLSearchParams(location.search); const open = q.get('open'); if (!open) return null;
        history.replaceState(history.state, '', location.pathname);
        return JSON.stringify({ sender: open, name: q.get('name') || '', username: q.get('username') || '', photo: q.get('photo') || '' });
    }""",
)
private external fun takeOpenFromLink(): String?

/** A song or a video someone sent opens on YouTube (the web has no player of its own). */
@JsFun("(kind, id) => window.open((kind === 'SONG' ? 'https://music.youtube.com/watch?v=' : 'https://www.youtube.com/watch?v=') + id, '_blank')")
private external fun openShared(kind: String, id: String)

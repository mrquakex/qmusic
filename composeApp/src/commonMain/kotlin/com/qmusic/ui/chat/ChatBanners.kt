package com.qmusic.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** How the phone's message notifications behave (kept on the phone, set in Settings → Notifications). */
object NotificationPrefs {
    /** With the phone locked a message shows neither who wrote nor what; unlocked, it shows in full. */
    val hideOnLock = MutableStateFlow(false)

    /** With the app open, a message from another conversation comes as a strip at the top of the app. */
    val inAppBanner = MutableStateFlow(true)

    /** Something changed: the app keeps it (set by the app). */
    var onChanged: (() -> Unit)? = null

    fun setHideOnLock(on: Boolean) {
        hideOnLock.value = on
        onChanged?.invoke()
    }

    fun setInAppBanner(on: Boolean) {
        inAppBanner.value = on
        onChanged?.invoke()
    }
}

/** A message shown as a strip at the top while the app is open: who (the conversation), [author] in a group, what. */
data class ChatBanner(val id: Long, val conversation: ChatUser, val author: String, val text: String)

object ChatBanners {
    val current = MutableStateFlow<ChatBanner?>(null)
}

/**
 * The strip of a message that came while the app is open (in another conversation, the videos or the music): it slides
 * down from the top for a few seconds; a tap opens the conversation, a swipe up sends it away.
 */
@Composable
fun ChatBannerOverlay() {
    val banner by ChatBanners.current.collectAsState()
    // What was shown stays drawn while it slides away.
    var last by remember { mutableStateOf<ChatBanner?>(null) }
    if (banner != null) last = banner
    LaunchedEffect(banner?.id) {
        val id = banner?.id ?: return@LaunchedEffect
        delay(BANNER_SHOWN_MS)
        if (ChatBanners.current.value?.id == id) ChatBanners.current.value = null
    }
    AnimatedVisibility(
        visible = banner != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val b = last ?: return@AnimatedVisibility
        var pulled by remember { mutableStateOf(0f) }
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .shadow(14.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1C1C22))
                    .pointerInput(b.id) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (pulled < -24f) ChatBanners.current.value = null
                                pulled = 0f
                            },
                        ) { change, amount ->
                            change.consume()
                            pulled += amount
                        }
                    }
                    .clickable {
                        ChatBanners.current.value = null
                        ChatLaunch.request.value = ChatLaunchRequest(user = b.conversation)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatAvatar(b.conversation, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        b.conversation.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (if (b.author.isNotBlank()) b.author + ": " else "") + b.text,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val BANNER_SHOWN_MS = 4_000L

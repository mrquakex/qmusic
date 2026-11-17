package com.qmusic.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.ScheduledMessage
import com.qmusic.ui.chat.ScheduledMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Messages written now and sent later (a long press on Send): kept on the phone and sent by the phone at their time,
 * with an alarm, so they go even when the app is closed and stay end-to-end encrypted. After a restart of the phone
 * (or an update of the app) the alarms are set again; one whose time passed while the phone was off goes at once.
 */
object ScheduledSend {
    private const val PREFS = "nuvelo_scheduled"
    const val ACTION_SEND = "com.qmusic.app.SCHEDULED_SEND"
    const val EXTRA_ID = "scheduled_id"

    fun init(context: Context) {
        val app = context.applicationContext
        ScheduledMessages.supported = true
        ScheduledMessages.all.value = load(app)
        ScheduledMessages.schedule = { m ->
            val next = (ScheduledMessages.all.value.filter { it.id != m.id } + m).sortedBy { it.at }
            save(app, next)
            arm(app, m)
        }
        ScheduledMessages.cancel = { id ->
            disarm(app, id)
            save(app, ScheduledMessages.all.value.filter { it.id != id })
        }
        ScheduledMessages.sendNow = { id ->
            ScheduledMessages.all.value.firstOrNull { it.id == id }?.let { m ->
                disarm(app, id)
                scope.launch { send(app, m) }
            }
        }
        rearm(app)
    }

    /** All of them again (after a restart): the alarms do not survive one. */
    fun rearm(context: Context) {
        load(context).forEach { arm(context, it) }
    }

    private fun intent(context: Context, id: String) = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, ScheduledSendReceiver::class.java).setAction(ACTION_SEND).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun arm(context: Context, m: ScheduledMessage) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = m.at.coerceAtLeast(System.currentTimeMillis() + 1_000)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        runCatching {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context, m.id))
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context, m.id))
        }
    }

    private fun disarm(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(intent(context, id))
    }

    /** Sends it and takes it off the list (it waits on the phone as any message does when there is no connection). */
    internal suspend fun send(context: Context, m: ScheduledMessage): Boolean {
        val sent = com.qmusic.ui.chat.ChatNotificationActions.reply(context, m.peer, m.text)
        if (sent) save(context, load(context).filter { it.id != m.id })
        return sent
    }

    internal fun find(context: Context, id: String) = load(context).firstOrNull { it.id == id }

    private fun load(context: Context): List<ScheduledMessage> = runCatching {
        val arr = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("all", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ScheduledMessage(
                id = o.getString("id"),
                chatId = o.getString("chatId"),
                peer = ChatUser(o.getString("uid"), o.optString("name"), o.optString("username"), o.optString("photo").ifBlank { null }),
                text = o.getString("text"),
                at = o.getLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, list: List<ScheduledMessage>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject().put("id", m.id).put("chatId", m.chatId).put("uid", m.peer.uid).put("name", m.peer.name)
                    .put("username", m.peer.username).put("photo", m.peer.photo ?: "").put("text", m.text).put("at", m.at),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("all", arr.toString()).apply()
        ScheduledMessages.all.value = list
    }

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** The alarm of a scheduled message (send it), or the phone restarted / the app updated (set the alarms again). */
class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ScheduledSend.ACTION_SEND -> {
                val id = intent.getStringExtra(ScheduledSend.EXTRA_ID) ?: return
                val m = ScheduledSend.find(app, id) ?: return
                val pending = goAsync()
                ScheduledSend.scope.launch {
                    try {
                        // Not sent (signed out, or the account not ready in time): tried again in a few minutes.
                        val ok = withTimeoutOrNull(9_000) { ScheduledSend.send(app, m) } == true
                        if (!ok) {
                            app.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                System.currentTimeMillis() + 5 * 60_000L,
                                PendingIntent.getBroadcast(
                                    app, id.hashCode(),
                                    Intent(app, ScheduledSendReceiver::class.java).setAction(ScheduledSend.ACTION_SEND).putExtra(ScheduledSend.EXTRA_ID, id),
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                                ),
                            )
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ScheduledSend.rearm(app)
        }
    }
}

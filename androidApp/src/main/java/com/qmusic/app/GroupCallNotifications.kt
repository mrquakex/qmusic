package com.qmusic.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.GroupCallLaunch
import com.qmusic.ui.chat.GroupCallRequest

/**
 * A group call rings like a phone call: full screen over the lock screen, the ringtone until it is answered, declined
 * or 40 seconds pass, with Join and Decline. Not answered: a "missed group call" notification.
 */
object GroupCallNotifications {
    private const val RING_ID = 2011
    private const val RING_FOR_MS = 40_000L
    private const val PREFS = "qmusic_group_calls"
    const val EXTRA_GROUP = "group_call_group"
    const val EXTRA_GROUP_NAME = "group_call_name"
    const val EXTRA_GROUP_PHOTO = "group_call_photo"
    const val EXTRA_VIDEO = "group_call_video"
    const val EXTRA_JOIN = "group_call_join"

    private fun tr(context: Context) =
        context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"

    private fun open(context: Context, group: ChatUser, video: Boolean, join: Boolean, code: Int): PendingIntent = PendingIntent.getActivity(
        context,
        code,
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_GROUP, group.uid)
            .putExtra(EXTRA_GROUP_NAME, group.name)
            .putExtra(EXTRA_GROUP_PHOTO, group.photo.orEmpty())
            .putExtra(EXTRA_VIDEO, video)
            .putExtra(EXTRA_JOIN, join)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun showRinging(context: Context, data: Map<String, String>) {
        val groupId = data["chatId"] ?: return
        val group = ChatUser(groupId, data["name"].orEmpty().ifBlank { "Nuvelo" }, "", data["photo"]?.ifBlank { null })
        val video = data["video"] == "1"
        val starter = data["starter"].orEmpty()
        val callKey = groupId + ":" + (data["callAt"] ?: System.currentTimeMillis().toString())
        // A call the person already joined here (from inside the app) does not ring.
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("in_call", null) == groupId) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ringing", callKey).apply()
        val tr = tr(context)
        // The same loud channel as one-to-one calls (ringtone, vibration, over the lock screen).
        CallNotifications.ensureChannels(context)
        val caller = Person.Builder().setName(group.name).setKey(groupId).setIcon(NotificationAvatars.icon(groupId, group.name, group.photo)).setImportant(true).build()
        val kind = if (video) (if (tr) "görüntülü" else "video") else (if (tr) "sesli" else "voice")
        val text = if (tr) "$starter grup araması başlattı ($kind)" else "$starter started a $kind group call"
        val decline = PendingIntent.getBroadcast(
            context, 21, Intent(context, GroupCallActionReceiver::class.java).setAction(GroupCallActionReceiver.DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CallNotifications.ringChannelId(context))
            .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
            .setLargeIcon(NotificationAvatars.bitmap(groupId, group.name, group.photo))
            .setContentTitle(group.name)
            .setContentText(text)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, open(context, group, video, join = true, code = 22)).setIsVideo(video))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(open(context, group, video, join = false, code = 23), true)
            .setContentIntent(open(context, group, video, join = false, code = 24))
            .setTimeoutAfter(RING_FOR_MS)
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        runCatching { context.getSystemService(NotificationManager::class.java).notify(RING_ID, notification) }
        // A little after the ringing stops: "missed", unless it was answered or declined meanwhile.
        val extras = PersistableBundle().apply {
            putString("key", callKey)
            putString("group", groupId)
            putString("name", group.name)
            putString("photo", group.photo.orEmpty())
            putString("starter", starter)
            putInt("video", if (video) 1 else 0)
        }
        runCatching {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, GroupCallMissedJob::class.java))
                    .setMinimumLatency(RING_FOR_MS + 2_000)
                    .setOverrideDeadline(RING_FOR_MS + 60_000)
                    .setExtras(extras)
                    .build(),
            )
        }
    }

    /** Answered or declined: the ringing stops and it will not count as missed. */
    fun handled(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("ringing").apply()
        context.getSystemService(NotificationManager::class.java).cancel(RING_ID)
        runCatching { context.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
    }

    /** The person is in [groupId]'s call (null when they left): its ringing stops, a new ring for it is not shown. */
    fun inCall(context: Context, groupId: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("in_call", groupId).apply()
        if (groupId != null) handled(context)
    }

    internal fun ringingKey(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ringing", null)

    internal fun showMissed(context: Context, group: ChatUser, starter: String, video: Boolean) {
        val tr = tr(context)
        CallNotifications.ensureMissedChannel(context)
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(
                ("gmissed" + group.uid).hashCode(),
                NotificationCompat.Builder(context, CallNotifications.MISSED_CHANNEL_ID)
                    .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
                    .setContentTitle(if (tr) "Cevapsız grup araması" else "Missed group call")
                    .setContentText(group.name + (if (starter.isNotBlank()) " · $starter" else "") + " · " + if (video) (if (tr) "Görüntülü" else "Video") else (if (tr) "Sesli" else "Voice"))
                    .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                    .setAutoCancel(true)
                    .setContentIntent(ChatPush.openChat(context, group))
                    .build(),
            )
        }
    }

    /** Opened from the notification (the full screen, or Join): the app opens the group, joining when asked. */
    fun openIfAsked(context: Context, intent: Intent?) {
        val groupId = intent?.getStringExtra(EXTRA_GROUP) ?: return
        val group = ChatUser(groupId, intent.getStringExtra(EXTRA_GROUP_NAME).orEmpty(), "", intent.getStringExtra(EXTRA_GROUP_PHOTO)?.ifBlank { null })
        val join = intent.getBooleanExtra(EXTRA_JOIN, false)
        val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
        intent.removeExtra(EXTRA_GROUP)
        if (join) handled(context) else context.getSystemService(NotificationManager::class.java).cancel(RING_ID)
        com.qmusic.ui.chat.ChatCallActions.wake(context)
        GroupCallLaunch.request.value = GroupCallRequest(group, video, join)
    }

    private const val JOB_ID = 4711
}

/** Decline, from the ringing notification. */
class GroupCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DECLINE) GroupCallNotifications.handled(context)
    }

    companion object {
        const val DECLINE = "com.qmusic.app.GROUP_CALL_DECLINE"
    }
}

/** After the ringing: if nobody answered or declined it here, the missed call notification. */
class GroupCallMissedJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val extras = params.extras
        val key = extras.getString("key")
        if (key != null && GroupCallNotifications.ringingKey(this) == key) {
            GroupCallNotifications.handled(this)
            GroupCallNotifications.showMissed(
                this,
                ChatUser(extras.getString("group").orEmpty(), extras.getString("name").orEmpty(), "", extras.getString("photo")?.ifBlank { null }),
                extras.getString("starter").orEmpty(),
                extras.getInt("video") == 1,
            )
        }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}

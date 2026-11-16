package com.qmusic.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
actual fun rememberStickerMaker(onStep: (StickerMaking) -> Unit, onMade: (GifItem?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            onStep(StickerMaking.CUTTING)
            val png = withContext(Dispatchers.Default) { runCatching { StickerMaker.make(context, uri) }.getOrNull() }
            if (png == null) {
                onStep(StickerMaking.IDLE)
                onMade(null)
                return@launch
            }
            onStep(StickerMaking.UPLOADING)
            val made = StickerMaker.upload(png)
            onStep(StickerMaking.IDLE)
            onMade(made)
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** Cuts a photo into a sticker (512 x 512 PNG, see-through around the subject) and keeps the person's stickers. */
object StickerMaker {
    private const val TAG = "QeyfStickers"
    private const val SIDE = 512
    private const val RECENT_KEPT = 30

    /** The sticker of the photo at [uri]: its subject cut out, or (when that cannot be) the photo in a circle. */
    suspend fun make(context: Context, uri: Uri): ByteArray? {
        val photo = decode(context, uri) ?: return null
        val subject = runCatching { cutOut(photo) }.onFailure { Log.w(TAG, "cut-out: ${it.message}") }.getOrNull()
        val shape = subject?.let(::trim) ?: circle(photo)
        val square = fit(shape)
        return ByteArrayOutputStream().use { out ->
            square.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > 1280) decoder.setTargetSampleSize((longest / 1280).coerceAtLeast(1))
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } ?: return null
        return bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** The photo's subject on a see-through background (Google's on-phone subject segmentation). */
    private suspend fun cutOut(photo: Bitmap): Bitmap? {
        val options = com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = com.google.mlkit.vision.segmentation.subject.SubjectSegmentation.getClient(options)
        return try {
            withTimeoutOrNull(20_000) {
                segmenter.process(com.google.mlkit.vision.common.InputImage.fromBitmap(photo, 0)).await().foregroundBitmap
            }
        } finally {
            segmenter.close()
        }
    }

    /** Cropped to what is not see-through (the subject alone); null when nothing was found. */
    private fun trim(bitmap: Bitmap): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((pixels[y * w + x] ushr 24) > 16) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return null
        // A subject smaller than a twentieth of the photo is no subject.
        if ((right - left) * (bottom - top) < w * h / 20) return null
        return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }

    /** The photo's middle in a circle (when no subject could be cut out). */
    private fun circle(photo: Bitmap): Bitmap {
        val side = minOf(photo.width, photo.height)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawOval(RectF(0f, 0f, side.toFloat(), side.toFloat()), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(photo, ((side - photo.width) / 2).toFloat(), ((side - photo.height) / 2).toFloat(), paint)
        return out
    }

    /** Fitted into a 512 square with a little room around it. */
    private fun fit(shape: Bitmap): Bitmap {
        val room = SIDE * 0.92f
        val scale = room / max(shape.width, shape.height)
        val w = (shape.width * scale).roundToInt().coerceAtLeast(1)
        val h = (shape.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(shape, w, h, true)
        val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(scaled, ((SIDE - w) / 2).toFloat(), ((SIDE - h) / 2).toFloat(), null)
        return out
    }

    /** Sent to Qeyf's media server; the sticker that points at it. */
    suspend fun upload(png: ByteArray): GifItem? = withContext(Dispatchers.IO) {
        runCatching {
            val user = FirebaseAuth.getInstance().currentUser ?: return@runCatching null
            val token = user.getIdToken(false).await().token ?: return@runCatching null
            val url = MediaUpload.upload(token, "u_${user.uid}", "png", "image/png", png.size.toLong(), { png.inputStream() }) {}
            GifItem(id = "mine_" + url.hashCode().toUInt().toString(16), preview = url, url = url, width = SIDE, height = SIDE)
        }.onFailure { Log.w(TAG, "upload: ${it.message}") }.getOrNull()
    }

    // ---------------------------------------------------------------- the person's stickers (chatPrefs/{uid}/stickers)

    private var started = false

    private fun ref(context: Context) = runCatching {
        FirebaseChatBackend.get(context) ?: return null
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        val configured = FirebaseApp.getInstance().options.databaseUrl
        val db = if (configured.isNullOrBlank()) FirebaseDatabase.getInstance(DATABASE_URL) else FirebaseDatabase.getInstance()
        db.getReference("chatPrefs").child(uid).child("stickers")
    }.getOrNull()

    private fun key(item: GifItem) = Integer.toHexString(item.url.hashCode()) + "_" + item.url.length

    /** Keeps [Stickers] filled from the account, and its changes written there. */
    fun start(context: Context) {
        if (started) return
        val c = context.applicationContext
        val ref = ref(c) ?: return
        started = true
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                fun list(name: String) = snap.child(name).children.mapNotNull { e ->
                    val url = e.child("u").getValue(String::class.java) ?: return@mapNotNull null
                    (e.child("t").getValue(Long::class.java) ?: 0L) to GifItem(
                        id = e.key ?: url,
                        preview = e.child("p").getValue(String::class.java) ?: url,
                        url = url,
                        width = e.child("w").getValue(Int::class.java) ?: 0,
                        height = e.child("h").getValue(Int::class.java) ?: 0,
                    )
                }.sortedByDescending { it.first }.map { it.second }
                Stickers.recent.value = list("recent").take(RECENT_KEPT)
                Stickers.favorites.value = list("fav")
                Stickers.mine.value = list("mine")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "stickers: ${error.message}")
                started = false
            }
        })
        fun entry(item: GifItem) = mapOf("u" to item.url, "p" to item.preview, "w" to item.width, "h" to item.height, "t" to ServerValue.TIMESTAMP)
        Stickers.used = { item ->
            ref.child("recent").child(key(item)).setValue(entry(item))
            // Only the last few are kept.
            Stickers.recent.value.drop(RECENT_KEPT - 1).forEach { old -> if (old.url != item.url) ref.child("recent").child(key(old)).removeValue() }
        }
        Stickers.toggleFavorite = { item ->
            if (Stickers.isFavorite(item)) ref.child("fav").child(key(item)).removeValue() else ref.child("fav").child(key(item)).setValue(entry(item))
        }
        Stickers.addMine = { item -> ref.child("mine").child(key(item)).setValue(entry(item)) }
        Stickers.removeMine = { item -> ref.child("mine").child(key(item)).removeValue() }
    }

    private const val DATABASE_URL = "https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app"
}

package com.qmusic.ui.motion

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.qmusic.ui.PlatformPredictiveBackHandler
import kotlinx.coroutines.launch

/**
 * The app's motion, in one place: how long things take and how they ease, after Material's motion system. Every page,
 * tab and section change goes through here, so they all move alike. (The phone's "remove animations" setting slows
 * or stops all of them by itself.)
 */
object Motion {
    /** Small things: a press, a fade of a label. */
    const val SHORT = 150

    /** A tab, a section. */
    const val MEDIUM = 300

    /** A page coming in or going. */
    const val LONG = 400

    /** Most movement: starts briskly, lands softly. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Coming in: arrives fast, settles slowly. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Going away: leaves slowly at first, then quickly. */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> enter(duration: Int = LONG, delay: Int = 0): FiniteAnimationSpec<T> = tween(duration, delay, EmphasizedDecelerate)
    fun <T> exit(duration: Int = SHORT + 50): FiniteAnimationSpec<T> = tween(duration, 0, EmphasizedAccelerate)
}

/**
 * A page opening over another (forward) or closing back to it: the new page slides in a quarter of the way from the
 * side as it fades in, while the one under it drifts back a little and fades; back is the same in reverse. The page on
 * top is drawn above the other the whole way.
 */
fun <S> AnimatedContentTransitionScope<S>.pageTransform(forward: Boolean): ContentTransform {
    // As Android and WhatsApp do it: the page on top slides the whole width, solid (no see-through, so the two pages
    // never show through one another and nothing flashes empty); the page under it drifts a quarter aside, dimming.
    val slide = tween<IntOffset>(PAGE_MS, easing = Motion.Emphasized)
    val dim = tween<Float>(PAGE_MS, easing = Motion.Emphasized)
    return if (forward) {
        slideInHorizontally(slide) { it }
            .togetherWith(slideOutHorizontally(slide) { -it / 4 } + fadeOut(dim, targetAlpha = 0.4f))
            .apply { targetContentZIndex = 1f }
    } else {
        (slideInHorizontally(slide) { -it / 4 } + fadeIn(dim, initialAlpha = 0.4f))
            .togetherWith(slideOutHorizontally(slide) { it })
            .apply { targetContentZIndex = -1f }
    }.using(SizeTransform(clip = false))
}

private const val PAGE_MS = 350

/** Between tabs (siblings, no direction): the old fades out quickly, the new fades in and grows a touch (fade-through). */
fun <S> AnimatedContentTransitionScope<S>.fadeThrough(): ContentTransform =
    (fadeIn(Motion.enter(210, 90)) + scaleIn(Motion.enter(210, 90), initialScale = 0.96f))
        .togetherWith(fadeOut(tween(90, easing = Motion.EmphasizedAccelerate)))
        .using(SizeTransform(clip = false))

/**
 * Shows the page for [target] (null: none), moving between pages as [pageTransform] does: forward when [depth] (how
 * many pages are open) grew or stayed, back when it shrank. [contentKey]: what counts as another page. [backProgress]:
 * the system's back gesture (0-1) shrinks and moves the page on top with the finger before it goes.
 */
@Composable
fun <T> PageHost(
    target: T,
    depth: Int,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any? = { it },
    backProgress: Float = 0f,
    /** Each page is painted with this, so the page on top hides the one under it while it slides. */
    background: androidx.compose.ui.graphics.Color = com.qmusic.ui.theme.BgRoot,
    /** False where "no page" ([target] null) means the screen under the host shows through. */
    paintEmpty: Boolean = true,
    content: @Composable (T) -> Unit,
) {
    var lastDepth by remember { mutableIntStateOf(depth) }
    val forward = depth >= lastDepth
    SideEffect { lastDepth = depth }
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        contentAlignment = Alignment.TopStart,
        contentKey = contentKey,
        transitionSpec = { pageTransform(forward) },
        label = "page",
    ) { page ->
        val onTop = contentKey(page) == contentKey(target)
        Box(
            (if (onTop && backProgress > 0f) Modifier.predictiveBack(backProgress) else Modifier)
                .then(if (page != null || paintEmpty) Modifier.fillMaxSize().background(background) else Modifier),
        ) { content(page) }
    }
}

/** The page following the back gesture: a little smaller, nudged to the side, its corners rounding, as Android does. */
fun Modifier.predictiveBack(progress: Float): Modifier = graphicsLayer {
    val p = progress.coerceIn(0f, 1f)
    val scale = 1f - 0.1f * p
    scaleX = scale
    scaleY = scale
    translationX = 24.dp.toPx() * p
    shape = RoundedCornerShape(28.dp * p)
    clip = p > 0f
}

/**
 * The system back while [enabled], with its gesture: returns how far the gesture is (0-1), which eases back to 0 when
 * it is let go without going back; [onBack] when it completes (or Back is pressed).
 */
@Composable
fun rememberPredictiveBack(enabled: Boolean, onBack: () -> Unit): Float {
    var progress by remember { mutableFloatStateOf(0f) }
    val settle = remember { Animatable(0f) }
    var settling by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    PlatformPredictiveBackHandler(
        enabled = enabled,
        onProgress = { p ->
            settling = false
            progress = p
        },
        onCancel = {
            val from = progress
            progress = 0f
            settling = true
            scope.launch {
                settle.snapTo(from)
                settle.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                settling = false
            }
        },
        onBack = {
            progress = 0f
            onBack()
        },
    )
    return if (settling) settle.value else progress
}

/**
 * A press that shows: the thing sinks a little under the finger and springs back when let go (it does not take the
 * touch: the click still happens as before).
 */
fun Modifier.pressScale(pressedScale: Float = 0.96f): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        if (pressed) tween(Motion.SHORT, easing = Motion.Emphasized) else spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

/**
 * A whole section (the messages, the videos, the music) coming on screen: it fades in and settles from a touch smaller.
 * [key] is which section is shown; returns the modifier for its root.
 */
@Composable
fun Modifier.sectionEnter(key: Any): Modifier {
    val appear = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(key) }
    LaunchedEffect(key) {
        if (key != shown) {
            shown = key
            appear.snapTo(0f)
            appear.animateTo(1f, tween(Motion.MEDIUM + 40, easing = Motion.EmphasizedDecelerate))
        }
    }
    return this.graphicsLayer {
        val v = appear.value
        alpha = v
        val s = 0.97f + 0.03f * v
        scaleX = s
        scaleY = s
    }
}


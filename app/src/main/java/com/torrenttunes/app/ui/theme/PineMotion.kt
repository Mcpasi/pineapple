package com.torrenttunes.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Pineapple motion system.
 *
 * One vocabulary for every animated surface: standard eased-out motion for
 * content entering/leaving, spring-based motion for things that reply to a
 * finger or a viewport, and opacity-only crossfades for artwork and list
 * rows. Durations stay short on purpose — the player must never feel slow.
 */
object PineMotion {
    val Short = 140
    val Medium = 220
    val Long = 320

    // "Decelerate" easing — Android's standard item-entry curve.
    val EaseOut = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val EaseIn = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)
    val EaseInOut = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
    val Standard = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /** Gentle spring for tap feedback and track-change artwork swaps. */
    val SoftSpring = spring<Float>(stiffness = 600f, dampingRatio = 0.9f)

    /** Snappier spring for mini-player <-> full-player handoff. */
    val SnapSpring = spring<Float>(stiffness = 850f, dampingRatio = 0.82f)
}

@Composable
fun rememberPineTween(durationMs: Int = PineMotion.Medium) =
    remember(durationMs) { tween<Float>(durationMillis = durationMs, easing = PineMotion.Standard) }

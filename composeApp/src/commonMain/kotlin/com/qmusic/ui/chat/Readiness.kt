package com.qmusic.ui.chat

import androidx.compose.runtime.Composable

/** A phone setting that keeps calls or messages from reaching the person. */
enum class ReadinessIssue { NOTIFICATIONS, FULL_SCREEN, BATTERY }

/** What is missing on this phone (checked again each time the app comes back), and the way to fix each. */
class Readiness(val issues: List<ReadinessIssue>, val fix: (ReadinessIssue) -> Unit)

@Composable
expect fun rememberReadiness(): Readiness

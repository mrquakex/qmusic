package com.qmusic.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * End-to-end encryption, as the screens see it (the phone's part, E2eEngine, fills [api]).
 *
 * Every account has a key pair (its public half in publicKeys/{uid}); every encrypted conversation a random key, kept
 * in chatKeys/{chatId}/{uid}/{version} locked for each member with their public key. Messages, previews, edits and
 * the media's own keys go encrypted with it; the servers keep only what they cannot read.
 *
 * The private key leaves the phone only locked with the person's 6-digit PIN and a secret the key vault on our server
 * gives to the right PIN alone (10 wrong tries and it is gone for good), so a new phone gets the old messages back.
 */
interface E2eApi {
    /** This phone has its key pair (false: being made, or waiting for the PIN to bring the old one back). */
    val ready: StateFlow<Boolean>

    /** A backup of the key exists but is not on this phone: the PIN brings it back (or a new key starts over). */
    val needsRestore: StateFlow<Boolean>

    /** The PIN is set (for the backup and the secret space). */
    val hasPin: StateFlow<Boolean>

    /** Changes when keys arrive or conversations turn encrypted: what shows them is worked out again. */
    val changes: StateFlow<Int>

    /** The conversation's key version when it is encrypted, null when it is not (known only once watched). */
    fun version(chatId: String): Int?

    /** Starts following whether [chatId] is encrypted and fetches its keys. */
    fun watch(chatId: String)

    /** Turns encryption on (or a new key after members changed) for [members]; fails when one has no key yet. */
    suspend fun enable(chatId: String, members: List<String>, shareHistoryWith: List<String> = emptyList()): Result<Unit>

    /** Whether every one of [uids] can take encrypted messages (has a key). */
    suspend fun allHaveKeys(uids: List<String>): Boolean

    /** The 60-digit safety number of this person and [otherUid] (12 groups of 5), null without both keys. */
    suspend fun safetyNumber(otherUid: String): SafetyNumber?

    /** Marks [otherUid]'s current key as checked in person (a scanned code that matched). */
    fun markVerified(otherUid: String)

    /** A scanned safety code: true when it is this conversation's. */
    suspend fun checkScanned(otherUid: String, scanned: String): Boolean

    /** People whose key changed since this phone last saw it (a line in the conversation says so). */
    val keyChanged: StateFlow<Set<String>>

    fun dismissKeyChange(uid: String)

    /** Sets (or changes) the PIN: kept on the phone for the lock, and the key backed up with it. */
    suspend fun setPin(pin: String): Result<Unit>

    /** Checks the PIN on the phone; on a new phone, with the key vault (and brings the key back). */
    suspend fun checkPin(pin: String): PinResult

    /** Brings the key back on this phone with the PIN. */
    suspend fun restore(pin: String): PinResult

    /** Gives up the old key (a forgotten PIN): a new key pair, the old encrypted messages no longer readable here. */
    suspend fun startOver(): Result<Unit>

    /** Removes the PIN and the backup (the secret space off). */
    suspend fun clearPin(): Result<Unit>

    /** A decoy PIN: opens an empty secret space (null removes it). */
    fun setDecoyPin(pin: String?)

    fun hasDecoy(): Boolean
}

data class SafetyNumber(val digits: String, val qr: String, val verified: Boolean) {
    val groups: List<String> get() = digits.chunked(5)
}

sealed interface PinResult {
    data object Ok : PinResult
    /** The decoy PIN: an empty secret space opens. */
    data object Decoy : PinResult
    data class Wrong(val left: Int) : PinResult
    /** Too many wrong tries: wait this long. */
    data class Wait(val ms: Long) : PinResult
    /** The backup is locked for good (10 wrong tries at the vault). */
    data object Gone : PinResult
    data class Failed(val reason: String) : PinResult
}

object E2e {
    var api: E2eApi = NoE2e

    /** A message whose key is not on this phone (yet, or any more). */
    const val UNREADABLE = "\u0000e2e-unreadable"
}

private object NoE2e : E2eApi {
    override val ready = MutableStateFlow(false)
    override val needsRestore = MutableStateFlow(false)
    override val hasPin = MutableStateFlow(false)
    override val changes = MutableStateFlow(0)
    override fun version(chatId: String): Int? = null
    override fun watch(chatId: String) {}
    override suspend fun enable(chatId: String, members: List<String>, shareHistoryWith: List<String>) = Result.failure<Unit>(IllegalStateException("none"))
    override suspend fun allHaveKeys(uids: List<String>) = false
    override suspend fun safetyNumber(otherUid: String): SafetyNumber? = null
    override fun markVerified(otherUid: String) {}
    override suspend fun checkScanned(otherUid: String, scanned: String) = false
    override val keyChanged = MutableStateFlow<Set<String>>(emptySet())
    override fun dismissKeyChange(uid: String) {}
    override suspend fun setPin(pin: String) = Result.failure<Unit>(IllegalStateException("none"))
    override suspend fun checkPin(pin: String): PinResult = PinResult.Failed("none")
    override suspend fun restore(pin: String): PinResult = PinResult.Failed("none")
    override suspend fun startOver() = Result.failure<Unit>(IllegalStateException("none"))
    override suspend fun clearPin() = Result.failure<Unit>(IllegalStateException("none"))
    override fun setDecoyPin(pin: String?) {}
    override fun hasDecoy() = false
}

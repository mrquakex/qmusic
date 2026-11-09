package com.qmusic.ui

import com.qmusic.ui.chat.ChatAccount
import com.qmusic.ui.chat.ChatBackend
import com.qmusic.ui.chat.ChatController
import com.qmusic.ui.chat.ChatMessage
import com.qmusic.ui.chat.ChatRoute
import com.qmusic.ui.chat.ChatThread
import com.qmusic.ui.chat.ChatUser
import com.qmusic.ui.chat.SharedItem
import com.qmusic.ui.chat.UsernameTaken
import com.qmusic.ui.chat.chatIdOf
import com.qmusic.ui.chat.isValidUsername
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val ME = ChatUser("aaa", "Ben", "ben")
private val ALI = ChatUser("bbb", "Ali", "ali")
private val AYSE = ChatUser("ccc", "Ayşe", "ayse")

private class FakeChat : ChatBackend {
    override val account = MutableStateFlow<ChatAccount>(ChatAccount.Ready(ME))
    val threadList = MutableStateFlow(
        listOf(
            ChatThread("aaa_bbb", ALI, "selam", 2, "bbb", unread = true, otherReadAt = 0),
            ChatThread("aaa_ccc", AYSE, "naber", 1, "aaa", unread = false, otherReadAt = 0),
        ),
    )
    val blockedIds = MutableStateFlow<Set<String>>(emptySet())
    val sent = mutableListOf<Triple<ChatUser, String, SharedItem?>>()
    var takenNames = setOf("ali")

    override suspend fun signIn() = Result.success(Unit)
    override fun signOut() { account.value = ChatAccount.SignedOut }
    override suspend fun saveProfile(name: String, username: String): Result<Unit> =
        if (username in takenNames) Result.failure(UsernameTaken()) else Result.success(Unit)
    override suspend fun findUsers(prefix: String) = Result.success(listOf(ME, ALI, AYSE).filter { it.username.startsWith(prefix) })
    override fun threads(): Flow<List<ChatThread>> = threadList
    override fun messages(chatId: String): Flow<List<ChatMessage>> = flowOf(emptyList())
    override fun otherReadAt(chatId: String, otherUid: String): Flow<Long> = flowOf(0L)
    override suspend fun send(other: ChatUser, text: String, share: SharedItem?): Result<Unit> {
        sent += Triple(other, text, share)
        return Result.success(Unit)
    }
    override suspend fun markRead(chatId: String) {}
    override suspend fun deleteMessage(chatId: String, messageId: String) = Result.success(Unit)
    override fun blocked(): Flow<Set<String>> = blockedIds
    override suspend fun setBlocked(uid: String, blocked: Boolean): Result<Unit> {
        blockedIds.value = if (blocked) blockedIds.value + uid else blockedIds.value - uid
        return Result.success(Unit)
    }
    override suspend fun deleteAccount() = Result.success(Unit)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {
    @Test
    fun aPairOfPeopleHasOneConversationWhoeverStartsIt() {
        assertEquals("aaa_bbb", chatIdOf("bbb", "aaa"))
        assertEquals(chatIdOf("aaa", "bbb"), chatIdOf("bbb", "aaa"))
    }

    @Test
    fun usernamesAreShortLowercaseAndPlain() {
        assertTrue(isValidUsername("mr.quake_1"))
        assertFalse(isValidUsername("ab"))
        assertFalse(isValidUsername("Mrquake"))
        assertFalse(isValidUsername("ali veli"))
        assertFalse(isValidUsername("a".repeat(21)))
    }

    @Test
    fun theUnreadCountAndTheListLeaveOutBlockedPeople() = runTest(UnconfinedTestDispatcher()) {
        val backend = FakeChat()
        val c = ChatController(backend, backgroundScope)
        assertEquals(1, c.unreadCount.value)
        assertEquals(2, c.threads.value?.size)
        backend.blockedIds.value = setOf("bbb")
        assertEquals(0, c.unreadCount.value)
        assertEquals(listOf("ccc"), c.threads.value?.map { it.other.uid })
    }

    @Test
    fun aPersonFoundBySearchOpensTheirConversationInsteadOfTheSearch() = runTest(UnconfinedTestDispatcher()) {
        val c = ChatController(FakeChat(), backgroundScope)
        c.openNewChat()
        c.setQuery("@a")
        advanceTimeBy(400)
        // The person searching is not among the results.
        assertEquals(listOf("ali", "ayse"), c.found.value?.map { it.username })
        c.openConversation(ALI)
        assertEquals(listOf<ChatRoute>(ChatRoute.Conversation(ALI)), c.routes.value)
        assertTrue(c.pop())
        assertFalse(c.pop())
    }

    @Test
    fun emptyOrTooLongMessagesAreNotSent() = runTest(UnconfinedTestDispatcher()) {
        val backend = FakeChat()
        val c = ChatController(backend, backgroundScope)
        assertFalse(c.send(ALI, "   "))
        assertFalse(c.send(ALI, "x".repeat(ChatController.MAX_LENGTH + 1)))
        assertTrue(c.send(ALI, "  merhaba  "))
        assertEquals("merhaba", backend.sent.single().second)
    }

    @Test
    fun aTakenUsernameIsSaidSo() = runTest(UnconfinedTestDispatcher()) {
        val backend = FakeChat()
        val c = ChatController(backend, backgroundScope)
        c.saveProfile("Ali", "ali")
        assertTrue(c.usernameTaken.value)
        c.saveProfile("Ali", "ali2")
        assertFalse(c.usernameTaken.value)
    }

    @Test
    fun blockingClosesTheConversation() = runTest(UnconfinedTestDispatcher()) {
        val c = ChatController(FakeChat(), backgroundScope)
        c.openConversation(ALI)
        c.setBlocked(ALI, true)
        assertTrue(c.routes.value.isEmpty())
        assertEquals(setOf("bbb"), c.blocked.value)
    }
}

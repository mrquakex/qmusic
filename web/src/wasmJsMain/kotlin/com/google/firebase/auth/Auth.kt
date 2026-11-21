package com.google.firebase.auth

import com.google.android.gms.tasks.Task
import com.nuvelo.web.authDeleteUser
import com.nuvelo.web.authListen
import com.nuvelo.web.authOut
import com.nuvelo.web.authToken
import com.nuvelo.web.text
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Firebase sign-in as the phone's code uses it, over the browser's Firebase. */
class FirebaseUser internal constructor(val uid: String, val displayName: String?, photo: String?) {
    val photoUrl: Uri? = photo?.takeIf { it.isNotEmpty() }?.let(::Uri)

    fun getIdToken(forceRefresh: Boolean): Task<GetTokenResult> = Task.of { GetTokenResult(authToken(forceRefresh).text()) }

    fun delete(): Task<Unit> = Task.of { authDeleteUser().await<kotlin.js.JsAny?>(); Unit }
}

class Uri(private val text: String) {
    override fun toString(): String = text
}

class GetTokenResult(val token: String?)

class FirebaseAuth private constructor() {
    var currentUser: FirebaseUser? = null
        private set

    /** Whether the browser has said who is signed in yet (it reads it from its storage first). */
    private var known = false
    private val listeners = mutableListOf<(FirebaseAuth) -> Unit>()

    init {
        authListen { json ->
            com.nuvelo.web.logJs("auth: " + (json?.take(60) ?: "signed out"))
            currentUser = json?.let {
                val o = Json.parseToJsonElement(it).jsonObject
                FirebaseUser(o["uid"]!!.jsonPrimitive.content, o["name"]?.jsonPrimitive?.content, o["photo"]?.jsonPrimitive?.content)
            }
            known = true
            listeners.toList().forEach { l -> l(this) }
        }
    }

    fun addAuthStateListener(listener: (FirebaseAuth) -> Unit) {
        listeners += listener
        if (known) listener(this)
    }

    fun signOut() {
        Task.of { authOut().await<kotlin.js.JsAny?>() }
    }

    companion object {
        private val one by lazy { FirebaseAuth() }
        fun getInstance(): FirebaseAuth = one
    }
}

package com.qmusic.ui.chat

import com.nuvelo.web.blobToBytes
import com.nuvelo.web.bytesToBlob
import com.nuvelo.web.openSealedB64
import com.nuvelo.web.sealBlob
import com.nuvelo.web.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A check of this page's encryption against the phone's (vectors made with Java's own cryptography, as Android's):
 * window.nuveloSelfTest(json) leaves its findings in window.__selftest.
 */
internal object E2eSelfTest {
    fun install() {
        exposeSelfTest { json -> CoroutineScope(Dispatchers.Default).launch { selfTestDone(runCatching { run(json) }.getOrElse { "error: " + it.message }) } }
        // The backend's writes tried directly (what the send button and "add friend" do): their errors in full.
        exposeWriteTest { what ->
            CoroutineScope(Dispatchers.Default).launch {
                val b = FirebaseChatBackend.get()
                val owner = ChatUser(CLAUDE_OWNER_UID, "Mustafa", "mustafa")
                val r = when (what) {
                    "send" -> b.send(owner, "Web testi: tarayıcıdan giden mesaj", null, null)
                    "friend" -> b.requestFriend(owner)
                    "call" -> runCatching { b.calls.call(ChatUser("zzWebCallTest", "Arama Testi", "aramatesti"), true) }
                    "hang" -> runCatching { b.calls.hangUp() }
                    "link" -> b.send(owner, "Link testi: https://github.com", null, null)
                    "preview" -> runCatching { b.linkPreview("https://github.com")?.title ?: error("no card") }.map { }
                    "status" -> b.postTextStatus("Web'den durum testi", 0xFF1E3A5F).mapCatching {
                        kotlinx.coroutines.delay(1500)
                        val mine = kotlinx.coroutines.withTimeout(8000) { b.myStatuses().first { it.isNotEmpty() } }
                        mine.forEach { b.deleteStatus(it.id) }
                    }
                    "group" -> b.createGroup("Web test grubu", listOf(owner)).mapCatching { g ->
                        kotlinx.coroutines.delay(1500)
                        b.leaveGroup(g.uid).getOrThrow()
                    }
                    else -> Result.failure(IllegalArgumentException(what))
                }
                selfTestDone(r.fold({ "ok" }, { e -> "failed: " + e.toString() + " / " + e.cause?.toString() }))
            }
        }
    }

    private fun pattern(n: Int) = ByteArray(n) { i -> (i * 31 + 7).toByte() }

    private suspend fun run(json: String): String {
        val v = Json.parseToJsonElement(json).jsonObject
        fun s(k: String) = v[k]!!.jsonPrimitive.content
        val out = LinkedHashMap<String, String>()
        // Java's PKCS#8 read here, and its public half worked out again.
        val webPriv = E2eCrypto.privateFrom(E2eCrypto.unb64(s("webPriv")))
        val webPub = E2eCrypto.publicFrom(E2eCrypto.unb64(s("webPub")))
        out["pkcs8 read"] = (E2eCrypto.b64(EcPublic(com.nuvelo.web.ecPublicOf(E2eCrypto.b64(webPriv.d)).let { E2eCrypto.unb64(it) }).encoded) == s("webPub")).toString()
        val pair = KeyPair(webPub, EcPrivate(webPriv.d, webPub.raw))
        val secret = E2eCrypto.unwrap(pair, s("epk"), s("c"), "chat1/web/1")
        out["unwrap"] = secret.contentEquals(pattern(32)).toString()
        out["text"] = E2eCrypto.open(secret, E2eCrypto.unb64(s("text")), "chat1".encodeToByteArray()).decodeToString()
        out["stream"] = E2eCrypto.unb64(openSealedB64(E2eCrypto.b64(secret), s("stream"))).contentEquals(pattern(150_000)).toString()
        // The other way: wrapped and sealed here, for Java to open.
        val phonePub = E2eCrypto.publicFrom(E2eCrypto.unb64(s("phonePub")))
        val w = E2eCrypto.wrap(phonePub, pattern(32), "chat2/phone/1")
        out["epk2"] = w.getString("epk")
        out["c2"] = w.getString("c")
        out["text2"] = E2eCrypto.b64(E2eCrypto.seal(pattern(32), "Web'den selam ğüşİ 😀".encodeToByteArray(), "chat2".encodeToByteArray()))
        val plain = bytesToBlob(pattern(70_000), "application/octet-stream")
        val sealed = Json.parseToJsonElement(sealBlob(plain).text()).jsonObject
        out["stream2"] = E2eCrypto.b64(blobToBytes(sealed["id"]!!.jsonPrimitive.content.toInt()))
        out["streamKey2"] = sealed["key"]!!.jsonPrimitive.content
        out["webPkcs8"] = E2eCrypto.b64(pair.private.encoded)
        out["hkdf"] = E2eCrypto.hex(E2eCrypto.hkdf("ikm".encodeToByteArray(), "salt".encodeToByteArray(), "info", 42))
        out["fp"] = E2eCrypto.fingerprint(webPub.encoded)
        return JsonObject(out.mapValues { JsonPrimitive(it.value) }).toString()
    }
}

@JsFun("(f) => { window.nuveloSelfTest = (json) => { window.__selftest = null; f(json); }; }")
private external fun exposeSelfTest(f: (String) -> Unit)

@JsFun("(r) => { window.__selftest = r; }")
private external fun selfTestDone(r: String)

@JsFun("(f) => { window.nuveloWriteTest = (w) => { window.__selftest = null; f(w); }; }")
private external fun exposeWriteTest(f: (String) -> Unit)

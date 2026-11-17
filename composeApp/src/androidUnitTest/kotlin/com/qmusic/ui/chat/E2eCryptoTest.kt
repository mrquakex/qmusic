package com.qmusic.ui.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The end-to-end encryption's building blocks, against the standards' own test vectors where there are some. */
class E2eCryptoTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ------------------------------------------------------------------ PBKDF2-HMAC-SHA256 (RFC 7914 / known vectors)

    @Test
    fun pbkdf2MatchesKnownVectors() {
        val salt = "salt".toByteArray()
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", E2eCrypto.hex(E2eCrypto.pbkdf2("password", salt, 1, 32)))
        assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43", E2eCrypto.hex(E2eCrypto.pbkdf2("password", salt, 2, 32)))
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a", E2eCrypto.hex(E2eCrypto.pbkdf2("password", salt, 4096, 32)))
    }

    @Test
    fun pbkdf2LongerOutputStartsWithTheShorter() {
        val salt = E2eCrypto.bytes(16)
        val short = E2eCrypto.pbkdf2("123456", salt, 1000, 32)
        val long = E2eCrypto.pbkdf2("123456", salt, 1000, 64)
        assertContentEquals(short, long.copyOfRange(0, 32))
        assertNotEquals(E2eCrypto.hex(long.copyOfRange(0, 32)), E2eCrypto.hex(long.copyOfRange(32, 64)))
    }

    @Test
    fun pbkdf2JdkAgrees() {
        // The platform's own PBKDF2 (Android 8+ and every JDK) gives the same bytes.
        val salt = E2eCrypto.bytes(16)
        val spec = javax.crypto.spec.PBEKeySpec("987654".toCharArray(), salt, 5000, 512)
        val jdk = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        assertContentEquals(jdk, E2eCrypto.pbkdf2("987654", salt, 5000, 64))
    }

    // ------------------------------------------------------------------ HKDF-SHA256 (RFC 5869, test case 3)

    @Test
    fun hkdfMatchesRfc5869() {
        val okm = E2eCrypto.hkdf(ByteArray(22) { 0x0b }, ByteArray(0), "", 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", E2eCrypto.hex(okm))
    }

    // ------------------------------------------------------------------ AES-256-GCM

    @Test
    fun sealOpensAndRefusesTampering() {
        val key = E2eCrypto.bytes(32)
        val plain = "Merhaba, şifreli dünya 🔒".toByteArray()
        val sealed = E2eCrypto.seal(key, plain, "chat1".toByteArray())
        assertContentEquals(plain, E2eCrypto.open(key, sealed, "chat1".toByteArray()))
        // Each seal has its own nonce: the same text never looks the same twice.
        assertNotEquals(E2eCrypto.b64(sealed), E2eCrypto.b64(E2eCrypto.seal(key, plain, "chat1".toByteArray())))
        val bent = sealed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { E2eCrypto.open(key, bent, "chat1".toByteArray()) }
        // Moved to another conversation: refused.
        assertFailsWith<Exception> { E2eCrypto.open(key, sealed, "chat2".toByteArray()) }
        assertFailsWith<Exception> { E2eCrypto.open(E2eCrypto.bytes(32), sealed, "chat1".toByteArray()) }
    }

    @Test
    fun base64RoundTrips() {
        repeat(50) { n ->
            val b = E2eCrypto.bytes(n)
            assertContentEquals(b, E2eCrypto.unb64(E2eCrypto.b64(b)))
        }
        assertEquals("AAEC/w==", E2eCrypto.b64(byteArrayOf(0, 1, 2, -1)))
    }

    // ------------------------------------------------------------------ locking a key for someone (ECIES)

    @Test
    fun wrapOpensOnlyForItsRecipientAndPlace() {
        val alice = E2eCrypto.newPair()
        val bob = E2eCrypto.newPair()
        val chatKey = E2eCrypto.bytes(32)
        val w = E2eCrypto.wrap(alice.public, chatKey, "grpX/alice/3")
        assertContentEquals(chatKey, E2eCrypto.unwrap(alice, w.getString("epk"), w.getString("c"), "grpX/alice/3"))
        assertFailsWith<Exception> { E2eCrypto.unwrap(bob, w.getString("epk"), w.getString("c"), "grpX/alice/3") }
        // The same lock given as another version or member: refused.
        assertFailsWith<Exception> { E2eCrypto.unwrap(alice, w.getString("epk"), w.getString("c"), "grpX/alice/4") }
        assertFailsWith<Exception> { E2eCrypto.unwrap(alice, w.getString("epk"), w.getString("c"), "grpX/bob/3") }
    }

    @Test
    fun publicKeyRoundTripsAndFingerprints() {
        val p = E2eCrypto.newPair()
        val again = E2eCrypto.publicFrom(p.public.encoded)
        assertContentEquals(p.public.encoded, again.encoded)
        assertEquals(32, E2eCrypto.fingerprint(p.public.encoded).length)
        assertEquals(E2eCrypto.fingerprint(p.public.encoded), E2eCrypto.fingerprint(again.encoded))
        assertNotEquals(E2eCrypto.fingerprint(p.public.encoded), E2eCrypto.fingerprint(E2eCrypto.newPair().public.encoded))
        val priv = E2eCrypto.privateFrom(p.private.encoded)
        val w = E2eCrypto.wrap(p.public, byteArrayOf(1, 2, 3), "c")
        assertContentEquals(byteArrayOf(1, 2, 3), E2eCrypto.unwrap(java.security.KeyPair(again, priv), w.getString("epk"), w.getString("c"), "c"))
    }

    // ------------------------------------------------------------------ files in pieces (STREAM)

    private fun sealBytes(key: ByteArray, plain: ByteArray) = ByteArrayOutputStream().also { E2eStream.seal(key, ByteArrayInputStream(plain), it) }.toByteArray()
    private fun openBytes(key: ByteArray, sealed: ByteArray) = ByteArrayOutputStream().also { E2eStream.open(key, ByteArrayInputStream(sealed), it) }.toByteArray()

    @Test
    fun streamRoundTripsEverySize() {
        val key = E2eCrypto.bytes(32)
        for (size in listOf(0, 1, 100, 65_535, 65_536, 65_537, 131_072, 200_001, 1_000_000)) {
            val plain = E2eCrypto.bytes(size)
            val sealed = sealBytes(key, plain)
            assertEquals(E2eStream.sealedSize(size.toLong()), sealed.size.toLong(), "size $size")
            assertContentEquals(plain, openBytes(key, sealed), "size $size")
        }
    }

    @Test
    fun streamRefusesTamperingTruncationAndReordering() {
        val key = E2eCrypto.bytes(32)
        val plain = E2eCrypto.bytes(200_000)
        val sealed = sealBytes(key, plain)
        val bent = sealed.copyOf().also { it[100_000] = (it[100_000].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { openBytes(key, bent) }
        // Cut at a piece's end: the piece left last was not sealed as the last one.
        val piece = 65_536 + 16
        assertFailsWith<Exception> { openBytes(key, sealed.copyOfRange(0, 8 + piece * 2)) }
        // Two pieces swapped.
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, 8 + piece, swapped, 8, piece)
        System.arraycopy(sealed, 8, swapped, 8 + piece, piece)
        assertFailsWith<Exception> { openBytes(key, swapped) }
        assertFailsWith<Exception> { openBytes(E2eCrypto.bytes(32), sealed) }
    }

    // ------------------------------------------------------------------ message content as JSON

    @Test
    fun contentJsonRoundTrips() {
        val content = mapOf(
            "text" to "selam",
            "images" to listOf(mapOf("id" to "m1-0", "url" to "https://x/m/a.bin", "w" to 1080L, "h" to 720L, "key" to "k==", "thumb" to "")),
            "location" to mapOf("lat" to 41.0082, "lng" to 28.9784, "live" to true, "until" to 1_800_000_000_000L),
            "mentions" to mapOf("uidA" to true),
            "skipped" to null,
        )
        val back = E2eEngine.fromJson(org.json.JSONObject(E2eEngine.toJson(content).toString()))
        assertEquals("selam", back["text"])
        @Suppress("UNCHECKED_CAST")
        val image = (back["images"] as List<Map<String, Any?>>).single()
        assertEquals(1080L, image["w"])
        assertEquals("k==", image["key"])
        @Suppress("UNCHECKED_CAST")
        val loc = back["location"] as Map<String, Any?>
        assertEquals(41.0082, loc["lat"])
        assertEquals(1_800_000_000_000L, loc["until"])
        assertEquals(true, loc["live"])
        assertTrue("skipped" !in back)
    }

    // ------------------------------------------------------------------ reading a message (sealed or not)

    @Test
    fun msgNodeReadsLikeASnapshot() {
        val node = MsgNode(
            "m1",
            mapOf(
                "from" to "u1",
                "at" to 1700L,
                "images" to listOf(mapOf("id" to "a", "w" to 10L), null, mapOf("id" to "c", "w" to 30L)),
                "location" to mapOf("lat" to 41L, "lng" to 29.5),
                "viewOnce" to true,
            ),
        )
        assertEquals("u1", node.child("from").getValue(String::class.java))
        assertEquals(1700L, node.child("at").getValue(Long::class.java))
        assertEquals(1700, node.child("at").getValue(Int::class.java))
        // A whole number stored for a place reads as a Double (Firebase gives 41 back as a Long).
        assertEquals(41.0, node.child("location").child("lat").getValue(Double::class.java))
        assertEquals(true, node.child("viewOnce").getValue(Boolean::class.java))
        assertEquals(listOf("a", "c"), node.child("images").children.map { it.child("id").getValue(String::class.java) })
        assertEquals(false, node.child("missing").exists())
        assertEquals(null, node.child("missing").child("deeper").getValue(String::class.java))
    }

    // ------------------------------------------------------------------ live location sums

    @Test
    fun distancesAndLabels() {
        // Istanbul (Sultanahmet) to Ankara (Kızılay): about 350 km in a straight line.
        val d = distanceMeters(41.0055, 28.9769, 39.9208, 32.8541)
        assertTrue(d in 345_000.0..355_000.0, "was $d")
        assertEquals(0.0, distanceMeters(41.0, 29.0, 41.0, 29.0))
        assertEquals("850 m uzakta", distanceLabel(850.0, english = false))
        assertEquals("2,4 km uzakta", distanceLabel(2_400.0, english = false))
        assertEquals("2.4 km away", distanceLabel(2_400.0, english = true))
    }
}

@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package com.nuvelo.web

import kotlin.js.JsAny
import kotlin.wasm.unsafe.withScopedMemoryAllocator
import kotlinx.coroutines.await

// Bytes between the browser and Kotlin: copied through the module's own memory (fast, unlike one call per byte).

@JsFun("(a) => a.length")
private external fun jsLength(a: JsAny): Int

@JsFun("(src, size, dst) => { new Int8Array(wasmExports.memory.buffer, dst, size).set(src); }")
private external fun jsCopyIn(src: JsAny, size: Int, dst: Int)

@JsFun("(src, size) => new Int8Array(wasmExports.memory.buffer, src, size).slice()")
private external fun jsCopyOut(src: Int, size: Int): JsAny

/** A browser Int8Array as a Kotlin ByteArray. */
fun JsAny.toByteArray(): ByteArray {
    val size = jsLength(this)
    if (size == 0) return ByteArray(0)
    return withScopedMemoryAllocator { allocator ->
        val ptr = allocator.allocate(size)
        jsCopyIn(this, size, ptr.address.toInt())
        ByteArray(size) { i -> (ptr + i).loadByte() }
    }
}

/** A Kotlin ByteArray as a browser Int8Array. */
fun ByteArray.toJs(): JsAny {
    if (isEmpty()) return jsCopyOut(0, 0)
    return withScopedMemoryAllocator { allocator ->
        val ptr = allocator.allocate(size)
        for (i in indices) (ptr + i).storeByte(this[i])
        jsCopyOut(ptr.address.toInt(), size)
    }
}

/** The bytes of a kept file. */
suspend fun blobToBytes(id: Int): ByteArray = blobBytes(id).await<JsAny>().toByteArray()

/** Keeps [bytes] as a file in the browser (to send, or to show). */
fun bytesToBlob(bytes: ByteArray, type: String): Int = blobFromBytes(bytes.toJs(), type)

/** Waits for a promise of text. */
suspend fun kotlin.js.Promise<kotlin.js.JsString>.text(): String = await<kotlin.js.JsString>().toString()

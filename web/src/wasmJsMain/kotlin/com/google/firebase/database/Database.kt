package com.google.firebase.database

import com.google.android.gms.tasks.Task
import com.nuvelo.web.dbChildOn
import com.nuvelo.web.dbGet
import com.nuvelo.web.dbOff
import com.nuvelo.web.dbOn
import com.nuvelo.web.dbOnDisconnect
import com.nuvelo.web.dbPushKey
import com.nuvelo.web.dbRemove
import com.nuvelo.web.dbSet
import com.nuvelo.web.dbTransaction
import com.nuvelo.web.dbUpdate
import com.nuvelo.web.text
import kotlin.reflect.KClass
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/*
 * The part of Firebase's Android database API the phone's messages code uses, over Firebase's JavaScript SDK (see
 * web/bridge): the same calls, the same values (Long, Double, String, Boolean, Map, List), so that code runs here
 * as it is.
 */

// ------------------------------------------------------------------ values

/** A snapshot's data: a leaf value, or children in the order the database gave them. */
internal sealed interface Node {
    class Leaf(val value: Any) : Node
    class Parent(val children: List<Pair<String, Node>>) : Node
}

internal fun parseTree(json: String): Node? = toNode(Json.parseToJsonElement(json))

private fun toNode(e: JsonElement): Node? = when (e) {
    is JsonNull -> null
    is JsonPrimitive -> Node.Leaf(primitive(e))
    is JsonObject -> {
        val c = e["c"]
        if (c is JsonArray) {
            Node.Parent(c.mapNotNull { pair -> val p = pair.jsonArray; toNode(p[1])?.let { p[0].jsonPrimitive.content to it } })
        } else {
            // A plain object (a transaction's current value): children as they are.
            Node.Parent(e.entries.mapNotNull { (k, v) -> plainNode(v)?.let { k to it } })
        }
    }
    is JsonArray -> Node.Parent(e.mapIndexedNotNull { i, v -> plainNode(v)?.let { i.toString() to it } })
}

private fun plainNode(e: JsonElement): Node? = when (e) {
    is JsonNull -> null
    is JsonPrimitive -> Node.Leaf(primitive(e))
    is JsonObject -> Node.Parent(e.entries.mapNotNull { (k, v) -> plainNode(v)?.let { k to it } })
    is JsonArray -> Node.Parent(e.mapIndexedNotNull { i, v -> plainNode(v)?.let { i.toString() to it } })
}

private fun primitive(p: JsonPrimitive): Any {
    if (p.isString) return p.content
    p.booleanOrNull?.let { return it }
    val s = p.content
    return if (s.any { it == '.' || it == 'e' || it == 'E' }) s.toDouble().let { d -> if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 9.0E15) d.toLong() else d } else s.toLongOrNull() ?: s.toDouble()
}

/** A node as the Android SDK gives values: Map (ordered), List (for children 0..n), or the leaf. */
internal fun Node.toValue(): Any? = when (this) {
    is Node.Leaf -> value
    is Node.Parent -> {
        val ints = children.map { it.first.toIntOrNull()?.takeIf { n -> n >= 0 && (n == 0 || !it.first.startsWith("0")) } }
        val max = ints.maxOfOrNull { it ?: -1 } ?: -1
        if (children.isNotEmpty() && ints.all { it != null } && max < 2 * children.size) {
            val list = MutableList<Any?>(max + 1) { null }
            children.forEachIndexed { i, (_, n) -> list[ints[i]!!] = n.toValue() }
            list
        } else {
            LinkedHashMap<String, Any?>().also { m -> children.forEach { (k, n) -> m[k] = n.toValue() } }
        }
    }
}

/** A Kotlin value as JSON for the database (Map, List, String, numbers, Boolean, null; server values are maps). */
internal fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Double -> JsonPrimitive(v)
    is Float -> JsonPrimitive(v.toDouble())
    is Short -> JsonPrimitive(v.toInt())
    is Byte -> JsonPrimitive(v.toInt())
    is Number -> JsonPrimitive(v.toDouble())
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    is List<*> -> JsonArray(v.map { toJson(it) })
    is Array<*> -> JsonArray(v.map { toJson(it) })
    is Set<*> -> JsonArray(v.map { toJson(it) })
    is Enum<*> -> JsonPrimitive(v.name)
    else -> JsonPrimitive(v.toString())
}

internal fun jsonText(v: Any?): String = toJson(v).toString()

@Suppress("UNCHECKED_CAST")
internal fun <T : Any> convert(v: Any?, type: KClass<T>): T? {
    if (v == null) return null
    return when (type) {
        String::class -> v as? String
        Long::class -> (v as? Number)?.toLong()
        Int::class -> (v as? Number)?.toInt()
        Double::class -> (v as? Number)?.toDouble()
        Float::class -> (v as? Number)?.toFloat()
        Boolean::class -> v as? Boolean
        else -> v
    } as T?
}

// ------------------------------------------------------------------ errors, listeners

class DatabaseException(message: String) : Exception(message)

class DatabaseError(val message: String) {
    val code: Int get() = -1
    fun toException(): DatabaseException = DatabaseException(message)
}

interface ValueEventListener {
    fun onDataChange(snapshot: DataSnapshot)
    fun onCancelled(error: DatabaseError)
}

interface ChildEventListener {
    fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?)
    fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?)
    fun onChildRemoved(snapshot: DataSnapshot)
    fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?)
    fun onCancelled(error: DatabaseError)
}

object ServerValue {
    val TIMESTAMP: Map<String, String> = mapOf(".sv" to "timestamp")
    fun increment(delta: Long): Any = mapOf(".sv" to mapOf("increment" to delta))
    fun increment(delta: Double): Any = mapOf(".sv" to mapOf("increment" to delta))
}

// ------------------------------------------------------------------ snapshots

class DataSnapshot internal constructor(private val path: String, private val node: Node?) {
    val key: String? get() = path.substringAfterLast('/').ifEmpty { null }
    val ref: DatabaseReference get() = DatabaseReference(path)
    val value: Any? get() = node?.toValue()

    fun exists(): Boolean = node != null

    fun child(childPath: String): DataSnapshot {
        var n: Node? = node
        for (seg in childPath.split('/').filter { it.isNotEmpty() }) {
            n = (n as? Node.Parent)?.children?.firstOrNull { it.first == seg }?.second
        }
        return DataSnapshot(join(path, childPath), n)
    }

    fun hasChild(childPath: String): Boolean = child(childPath).exists()
    fun hasChildren(): Boolean = (node as? Node.Parent)?.children?.isNotEmpty() == true
    val childrenCount: Long get() = ((node as? Node.Parent)?.children?.size ?: 0).toLong()

    val children: Iterable<DataSnapshot>
        get() = (node as? Node.Parent)?.children?.map { (k, n) -> DataSnapshot(join(path, k), n) }.orEmpty()

    fun <T : Any> getValue(type: KClass<T>): T? = convert(value, type)
    fun getValue(): Any? = value

    override fun toString(): String = "DataSnapshot { key = $key, value = $value }"
}

internal fun join(a: String, b: String): String = (a.trimEnd('/') + "/" + b.trim('/')).trim('/')

class MutableData internal constructor(initial: Any?) {
    var value: Any? = initial
    fun <T : Any> getValue(type: KClass<T>): T? = convert(value, type)
}

object Transaction {
    class Result internal constructor(internal val success: Boolean, internal val data: MutableData?)

    interface Handler {
        fun doTransaction(currentData: MutableData): Result
        fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?)
    }

    fun success(data: MutableData): Result = Result(true, data)
    fun abort(): Result = Result(false, null)
}

// ------------------------------------------------------------------ queries and references

/** Every listener added, by what it listens to (so removing it finds it). */
private val active = HashMap<Pair<String, Any>, MutableList<Int>>()

open class Query internal constructor(internal val path: String, internal val spec: List<Pair<String, Any?>> = emptyList()) {
    private val specJson: String? get() = if (spec.isEmpty()) null else JsonArray(spec.map { (op, a) -> JsonArray(listOf(JsonPrimitive(op), toJson(a))) }).toString()
    private val listenKey: String get() = path + "?" + specJson.orEmpty()

    private fun with(op: String, a: Any? = null) = Query(path, spec + (op to a))

    fun orderByChild(child: String): Query = with("orderByChild", child)
    fun orderByKey(): Query = with("orderByKey")
    fun orderByValue(): Query = with("orderByValue")
    fun startAt(v: String): Query = with("startAt", v)
    fun startAt(v: Double): Query = with("startAt", v)
    fun endAt(v: String): Query = with("endAt", v)
    fun endAt(v: Double): Query = with("endAt", v)
    fun equalTo(v: String): Query = with("equalTo", v)
    fun equalTo(v: Double): Query = with("equalTo", v)
    fun equalTo(v: Boolean): Query = with("equalTo", v)
    fun limitToFirst(n: Int): Query = with("limitToFirst", n)
    fun limitToLast(n: Int): Query = with("limitToLast", n)

    fun keepSynced(keep: Boolean) {}

    fun addValueEventListener(listener: ValueEventListener): ValueEventListener {
        val id = dbOn(path, specJson, { json -> listener.onDataChange(DataSnapshot(path, parseTree(json))) }, { msg -> listener.onCancelled(DatabaseError(msg)) })
        active.getOrPut(listenKey to listener) { mutableListOf() }.add(id)
        return listener
    }

    fun addListenerForSingleValueEvent(listener: ValueEventListener) {
        val task = get()
        task.addOnCompleteListener { t ->
            val s = t.result
            if (s != null) listener.onDataChange(s) else listener.onCancelled(DatabaseError(t.exception?.message ?: "failed"))
        }
    }

    fun addChildEventListener(listener: ChildEventListener): ChildEventListener {
        val id = dbChildOn(path) { type, key, json ->
            val s = DataSnapshot(join(path, key), parseTree(json))
            when (type) {
                "added" -> listener.onChildAdded(s, null)
                "changed" -> listener.onChildChanged(s, null)
                "removed" -> listener.onChildRemoved(s)
            }
        }
        active.getOrPut(listenKey to listener) { mutableListOf() }.add(id)
        return listener
    }

    fun removeEventListener(listener: ValueEventListener) = remove(listener)
    fun removeEventListener(listener: ChildEventListener) = remove(listener)

    private fun remove(listener: Any) {
        active.remove(listenKey to listener)?.forEach { dbOff(it) }
    }

    fun get(): Task<DataSnapshot> = Task.of { DataSnapshot(path, dbGet(path, specJson).text().let(::parseTree)) }

    val ref: DatabaseReference get() = DatabaseReference(path)
}

class DatabaseReference internal constructor(path: String) : Query(path.trim('/')) {
    val key: String? get() = path.substringAfterLast('/').ifEmpty { null }
    val parent: DatabaseReference? get() = if (path.isEmpty()) null else DatabaseReference(path.substringBeforeLast('/', ""))
    val root: DatabaseReference get() = DatabaseReference("")

    fun child(childPath: String): DatabaseReference = DatabaseReference(join(path, childPath))

    /** A new child with a fresh key (nothing written yet). */
    fun push(): DatabaseReference = child(dbPushKey(path))

    fun setValue(value: Any?): Task<Unit> = Task.of {
        if (value == null) dbRemove(path).await<kotlin.js.JsAny?>() else dbSet(path, jsonText(value)).await<kotlin.js.JsAny?>()
        Unit
    }

    fun updateChildren(update: Map<String, Any?>): Task<Unit> = Task.of {
        dbUpdate(path, jsonText(update)).await<kotlin.js.JsAny?>()
        Unit
    }

    fun removeValue(): Task<Unit> = Task.of {
        dbRemove(path).await<kotlin.js.JsAny?>()
        Unit
    }

    fun runTransaction(handler: Transaction.Handler) {
        Task.of {
            val out = dbTransaction(path) { current ->
                val data = MutableData(parseTree(current)?.toValue())
                val r = handler.doTransaction(data)
                if (r.success) jsonText(r.data?.value) else "\u0000abort"
            }.text()
            val o = Json.parseToJsonElement(out) as JsonObject
            val committed = (o["committed"] as? JsonPrimitive)?.booleanOrNull == true
            val v = o["v"]?.let { if (it is JsonNull) null else toNode(it) }
            committed to DataSnapshot(path, v)
        }.addOnCompleteListener { t ->
            val r = t.result
            if (r != null) handler.onComplete(null, r.first, r.second) else handler.onComplete(DatabaseError(t.exception?.message ?: "failed"), false, null)
        }
    }

    fun onDisconnect(): OnDisconnect = OnDisconnect(path)

    override fun toString(): String = "https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app/$path"
    override fun equals(other: Any?): Boolean = other is DatabaseReference && other.path == path
    override fun hashCode(): Int = path.hashCode()
}

class OnDisconnect internal constructor(private val path: String) {
    fun setValue(value: Any?): Task<Unit> = Task.of { dbOnDisconnect(path, if (value == null) "remove" else "set", jsonText(value)).await<kotlin.js.JsAny?>(); Unit }
    fun updateChildren(update: Map<String, Any?>): Task<Unit> = Task.of { dbOnDisconnect(path, "update", jsonText(update)).await<kotlin.js.JsAny?>(); Unit }
    fun removeValue(): Task<Unit> = Task.of { dbOnDisconnect(path, "remove", null).await<kotlin.js.JsAny?>(); Unit }
    fun cancel(): Task<Unit> = Task.of { dbOnDisconnect(path, "cancel", null).await<kotlin.js.JsAny?>(); Unit }
}

class FirebaseDatabase private constructor() {
    val reference: DatabaseReference get() = DatabaseReference("")
    fun getReference(path: String): DatabaseReference = DatabaseReference(path)
    fun getReference(): DatabaseReference = reference
    fun setPersistenceEnabled(enabled: Boolean) {}

    companion object {
        private val one = FirebaseDatabase()
        fun getInstance(): FirebaseDatabase = one
        fun getInstance(url: String): FirebaseDatabase = one
    }
}

package com.qmusic.ui.chat.games

/**
 * Keeps local Commit-Reveal salts alive across app restarts until a round resolves.
 * Android wires [persistLoad]/[persistSave] to SharedPreferences("nuvelo_game_salts") using applicationContext.
 */
object GameSaltStore {
    private val mem = HashMap<String, String>()
    var persistLoad: ((String) -> String?)? = null
    var persistSave: ((String, String?) -> Unit)? = null

    fun put(key: String, value: String) {
        mem[key] = value
        persistSave?.invoke(key, value)
    }

    fun get(key: String): String? = mem[key] ?: persistLoad?.invoke(key)?.also { mem[key] = it }

    fun remove(key: String) {
        mem.remove(key)
        persistSave?.invoke(key, null)
    }

    fun keyOf(gameId: String, round: Int, uid: String): String = "g_${gameId}_${round}_${uid}"
}

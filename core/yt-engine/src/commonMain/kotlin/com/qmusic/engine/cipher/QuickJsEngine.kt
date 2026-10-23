package com.qmusic.engine.cipher

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Wrapper around the QuickJS engine for executing JavaScript code.
 * Allocates a dedicated thread with a 32 MB native stack, 192 MB memory limit,
 * and 8 MB JS stack size. Provides mock DOM globals for YouTube player scripts.
 */
class QuickJsEngine(
    private val jsThread: CoroutineDispatcher = createQuickJsDispatcher(),
) : AutoCloseable {
    companion object {
        private const val EVALUATION_TIMEOUT_MS = 10_000L
        private const val NATIVE_MEMORY_LIMIT_BYTES = 192L * 1024L * 1024L
        private const val NATIVE_STACK_LIMIT_BYTES = 8L * 1024L * 1024L
        private const val MAX_EVALUATION_RESULT_LENGTH = 16 * 1024 * 1024
        private const val MAX_FUNCTION_INPUT_LENGTH = 64 * 1024
        private const val MAX_FUNCTION_RESULT_LENGTH = 256 * 1024
        private val JS_IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

        internal fun jsStringLiteral(s: String): String =
            buildString(s.length + 2) {
                append('"')
                for (c in s) {
                    when (c) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> {
                            if (c.code < 0x20) {
                                append("\\u%04x".format(c.code))
                            } else {
                                append(c)
                            }
                        }
                    }
                }
                append('"')
            }
    }

    private val mutex = Mutex()
    private var quickJs: QuickJs? = null

    /**
     * Initialize the QuickJS runtime with custom limits.
     */
    suspend fun initialize() = withContext(jsThread) {
        mutex.withLock {
            if (quickJs == null) {
                quickJs = QuickJs.create(jsThread).also {
                    it.evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS
                    it.memoryLimit = NATIVE_MEMORY_LIMIT_BYTES
                    it.maxStackSize = NATIVE_STACK_LIMIT_BYTES
                }
            }
        }
    }

    /**
     * Execute JavaScript code and return the result.
     */
    suspend fun evaluate(code: String, maxResultLength: Int = MAX_FUNCTION_RESULT_LENGTH): String =
        withContext(jsThread) {
            require(maxResultLength in 1..MAX_EVALUATION_RESULT_LENGTH) { "Invalid QuickJS result limit" }
            mutex.withLock {
                val runtime = quickJs ?: throw IllegalStateException("QuickJS not initialized")
                val boundedCode = """
                    (function() {
                      const value = ($code);
                      if (value == null) return "";
                      const text = String(value);
                      return text.length <= $maxResultLength ? text : "";
                    })()
                """.trimIndent()
                runtime.evaluate<String?>(boundedCode).orEmpty()
            }
        }

    /**
     * Execute JavaScript for side effects.
     */
    suspend fun execute(code: String) = withContext(jsThread) {
        mutex.withLock {
            val runtime = quickJs ?: throw IllegalStateException("QuickJS not initialized")
            runtime.evaluate<Any?>("$code\n;undefined;")
        }
    }

    /**
     * Execute a named JavaScript function with a single string parameter.
     */
    suspend fun callFunction(functionName: String, input: String): String? = withContext(jsThread) {
        require(JS_IDENTIFIER.matches(functionName)) { "Invalid JavaScript function name" }
        if (input.length > MAX_FUNCTION_INPUT_LENGTH) return@withContext null
        mutex.withLock {
            val runtime = quickJs ?: throw IllegalStateException("QuickJS not initialized")
            val inputLiteral = jsStringLiteral(input)
            runtime.evaluate<String?>(
                """
                (function() {
                  const value = $functionName($inputLiteral);
                  if (value == null) return null;
                  const text = String(value);
                  return text.length <= $MAX_FUNCTION_RESULT_LENGTH ? text : null;
                })()
                """.trimIndent()
            )
        }
    }

    /**
     * Set up the global environment for YouTube player execution with mock DOM globals.
     */
    suspend fun setupYoutubeGlobals() = withContext(jsThread) {
        val setupCode = """
            if (typeof globalThis.XMLHttpRequest === "undefined") {
                globalThis.XMLHttpRequest = { prototype: {} };
            }
            if (typeof URL === "undefined") {
                globalThis.location = {
                    hash: "",
                    host: "www.youtube.com",
                    hostname: "www.youtube.com",
                    href: "https://www.youtube.com/watch?v=qmusic-app",
                    origin: "https://www.youtube.com",
                    password: "",
                    pathname: "/watch",
                    port: "",
                    protocol: "https:",
                    search: "?v=qmusic-app",
                    username: "",
                };
            } else {
                globalThis.location = new URL("https://www.youtube.com/watch?v=qmusic-app");
            }
            if (typeof globalThis.document === "undefined") {
                globalThis.document = Object.create(null);
            }
            if (typeof globalThis.navigator === "undefined") {
                globalThis.navigator = Object.create(null);
            }
            if (typeof globalThis.self === "undefined") {
                globalThis.self = globalThis;
            }
            if (typeof globalThis.window === "undefined") {
                globalThis.window = globalThis;
            }
            if (typeof globalThis.Intl === "undefined") {
                const NumberFormat = function(locale, options) {
                    this.options = options || {};
                };
                NumberFormat.supportedLocalesOf = function(locales) {
                    return Array.isArray(locales) ? locales : [locales];
                };
                NumberFormat.prototype.format = function(value) {
                    let formatted = String(value);
                    const minimumDigits = this.options.minimumIntegerDigits || 0;
                    while (formatted.length < minimumDigits) formatted = "0" + formatted;
                    return formatted;
                };
                const DateTimeFormat = function() {};
                DateTimeFormat.prototype.resolvedOptions = function() {
                    return { timeZone: "UTC" };
                };
                DateTimeFormat.prototype.format = function(value) {
                    return String(value);
                };
                globalThis.Intl = { NumberFormat, DateTimeFormat };
            }
        """.trimIndent()
        execute(setupCode)
    }

    /**
     * Load YouTube player JavaScript code into the engine.
     */
    suspend fun loadPlayerScript(playerCode: String) = withContext(jsThread) {
        setupYoutubeGlobals()
        execute(playerCode)
    }

    /**
     * Clean up and release native QuickJS resources.
     */
    suspend fun dispose() = withContext(jsThread) {
        mutex.withLock {
            quickJs?.close()
            quickJs = null
        }
    }

    override fun close() {
        quickJs?.close()
        quickJs = null
    }
}

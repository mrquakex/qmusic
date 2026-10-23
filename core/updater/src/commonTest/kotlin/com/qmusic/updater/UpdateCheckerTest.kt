package com.qmusic.updater

import com.qmusic.updater.model.UpdateInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {

    @OptIn(InternalAPI::class)
    private class MockHttpEngine(
        private val handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>,
    ) : HttpClientEngineBase("MockHttpEngine") {
        override val config: io.ktor.client.engine.HttpClientEngineConfig =
            io.ktor.client.engine.HttpClientEngineConfig()

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val (status, body) = handler(data)
            val callJob = kotlinx.coroutines.Job().apply { complete() }
            return HttpResponseData(
                statusCode = status,
                requestTime = GMTDate(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                version = HttpProtocolVersion.HTTP_1_1,
                body = ByteReadChannel(body.encodeToByteArray()),
                callContext = callJob,
            )
        }
    }

    private fun createMockClient(handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>): HttpClient {
        return HttpClient(MockHttpEngine(handler)) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true
                })
            }
        }
    }

    @Test
    fun testSemVerComparisonNewerVersions() {
        assertTrue(GithubReleaseUpdateChecker.isNewerVersion("v1.2.0", "1.0.0"))
        assertTrue(GithubReleaseUpdateChecker.isNewerVersion("1.10.0", "1.9.0"))
        assertTrue(GithubReleaseUpdateChecker.isNewerVersion("2.0.0", "1.99.99"))
        assertTrue(GithubReleaseUpdateChecker.isNewerVersion("1.0.1", "1.0.0"))
        assertTrue(GithubReleaseUpdateChecker.isNewerVersion("v2.0.0", "v1.9.9"))
        assertTrue(GithubReleaseUpdateChecker.isNewerVersion("1.0.0", "0.9.9"))
    }

    @Test
    fun testSemVerComparisonOlderOrEqualVersions() {
        assertFalse(GithubReleaseUpdateChecker.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(GithubReleaseUpdateChecker.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(GithubReleaseUpdateChecker.isNewerVersion("1.0.0", "v1.0.0"))
        assertFalse(GithubReleaseUpdateChecker.isNewerVersion("1.0.0", "v1.2.0"))
        assertFalse(GithubReleaseUpdateChecker.isNewerVersion("0.9.9", "1.0.0"))
        assertFalse(GithubReleaseUpdateChecker.isNewerVersion("1.0", "1.0.0"))
    }

    @Test
    fun testSemVerComparisonPrereleaseVersions() {
        assertTrue(GithubReleaseUpdateChecker.isNewerVersion("1.2.0", "1.2.0-beta"))
        assertTrue(GithubReleaseUpdateChecker.isNewerVersion("1.2.0-rc2", "1.2.0-rc1"))
        assertFalse(GithubReleaseUpdateChecker.isNewerVersion("1.2.0-beta", "1.2.0"))
    }

    @Test
    fun testCheckForUpdateWhenNewerVersionAvailable() = runTest {
        val jsonPayload = """
            {
              "tag_name": "v2.1.0",
              "name": "v2.1.0 - Major Performance Boost",
              "body": "### Features\n- High-res audio support\n- Offline caching",
              "html_url": "https://github.com/qmusic/qmusic-app/releases/tag/v2.1.0",
              "assets": [
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://github.com/qmusic/qmusic-app/releases/download/v2.1.0/checksums.txt"
                },
                {
                  "name": "qmusic-v2.1.0.apk",
                  "browser_download_url": "https://github.com/qmusic/qmusic-app/releases/download/v2.1.0/qmusic-v2.1.0.apk"
                }
              ]
            }
        """.trimIndent()

        val mockClient = createMockClient { _ ->
            Pair(HttpStatusCode.OK, jsonPayload)
        }

        val checker = GithubReleaseUpdateChecker(
            config = UpdateConfig(endpointUrl = "https://api.github.com/repos/qmusic/qmusic-app/releases/latest"),
            httpClient = mockClient
        )

        val result = checker.checkForUpdate(currentVersion = "1.0.0")
        assertTrue(result.isSuccess)
        val info = result.getOrNull()
        assertNotNull(info)
        assertEquals("2.1.0", info.versionName)
        assertEquals("https://github.com/qmusic/qmusic-app/releases/download/v2.1.0/qmusic-v2.1.0.apk", info.downloadUrl)
        assertTrue(info.changelog.contains("High-res audio support"))
        assertFalse(info.isMandatory)
    }

    @Test
    fun testCheckForUpdateWhenAlreadyUpToDateReturnsNull() = runTest {
        val jsonPayload = """
            {
              "tag_name": "v1.0.0",
              "name": "v1.0.0",
              "body": "Initial release",
              "html_url": "https://github.com/qmusic/qmusic-app/releases/tag/v1.0.0",
              "assets": [
                {
                  "name": "qmusic-v1.0.0.apk",
                  "browser_download_url": "https://github.com/qmusic/qmusic-app/releases/download/v1.0.0/qmusic-v1.0.0.apk"
                }
              ]
            }
        """.trimIndent()

        val mockClient = createMockClient { _ ->
            Pair(HttpStatusCode.OK, jsonPayload)
        }

        val checker = GithubReleaseUpdateChecker(
            config = UpdateConfig(),
            httpClient = mockClient
        )

        val result = checker.checkForUpdate(currentVersion = "1.0.0")
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun testCheckForUpdatePrefersUniversalApk() = runTest {
        val jsonPayload = """
            {
              "tag_name": "v1.5.0",
              "body": "Multiple architectures release",
              "html_url": "https://github.com/qmusic/qmusic-app/releases/tag/v1.5.0",
              "assets": [
                {
                  "name": "qmusic-arm64-v8a.apk",
                  "browser_download_url": "https://github.com/qmusic/qmusic-app/releases/download/v1.5.0/qmusic-arm64-v8a.apk"
                },
                {
                  "name": "qmusic-universal.apk",
                  "browser_download_url": "https://github.com/qmusic/qmusic-app/releases/download/v1.5.0/qmusic-universal.apk"
                }
              ]
            }
        """.trimIndent()

        val mockClient = createMockClient { _ ->
            Pair(HttpStatusCode.OK, jsonPayload)
        }

        val checker = GithubReleaseUpdateChecker(
            config = UpdateConfig(),
            httpClient = mockClient
        )

        val result = checker.checkForUpdate(currentVersion = "1.4.0")
        assertTrue(result.isSuccess)
        val info = result.getOrNull()
        assertNotNull(info)
        assertEquals("https://github.com/qmusic/qmusic-app/releases/download/v1.5.0/qmusic-universal.apk", info.downloadUrl)
    }

    @Test
    fun testCheckForUpdateFallsBackToHtmlUrlWhenNoApk() = runTest {
        val jsonPayload = """
            {
              "tag_name": "v1.5.0",
              "body": "Source-only release",
              "html_url": "https://github.com/qmusic/qmusic-app/releases/tag/v1.5.0",
              "assets": []
            }
        """.trimIndent()

        val mockClient = createMockClient { _ ->
            Pair(HttpStatusCode.OK, jsonPayload)
        }

        val checker = GithubReleaseUpdateChecker(
            config = UpdateConfig(),
            httpClient = mockClient
        )

        val result = checker.checkForUpdate(currentVersion = "1.4.0")
        assertTrue(result.isSuccess)
        val info = result.getOrNull()
        assertNotNull(info)
        assertEquals("https://github.com/qmusic/qmusic-app/releases/tag/v1.5.0", info.downloadUrl)
    }

    @Test
    fun testCheckForUpdateDetectsMandatoryFlag() = runTest {
        val jsonPayload = """
            {
              "tag_name": "v3.0.0",
              "body": "[MANDATORY] Critical security upgrade required for all users.",
              "html_url": "https://github.com/qmusic/qmusic-app/releases/tag/v3.0.0",
              "assets": [
                {
                  "name": "qmusic-v3.0.0.apk",
                  "browser_download_url": "https://github.com/qmusic/qmusic-app/releases/download/v3.0.0/qmusic-v3.0.0.apk"
                }
              ]
            }
        """.trimIndent()

        val mockClient = createMockClient { _ ->
            Pair(HttpStatusCode.OK, jsonPayload)
        }

        val checker = GithubReleaseUpdateChecker(
            config = UpdateConfig(),
            httpClient = mockClient
        )

        val result = checker.checkForUpdate(currentVersion = "2.9.0")
        assertTrue(result.isSuccess)
        val info = result.getOrNull()
        assertNotNull(info)
        assertTrue(info.isMandatory)
    }

    @Test
    fun testCheckForUpdateHandlesHttpErrorSafely() = runTest {
        val mockClient = createMockClient { _ ->
            Pair(HttpStatusCode.NotFound, "Not Found")
        }

        val checker = GithubReleaseUpdateChecker(
            config = UpdateConfig(),
            httpClient = mockClient
        )

        val result = checker.checkForUpdate(currentVersion = "1.0.0")
        assertTrue(result.isFailure)
    }

    @Test
    fun testCheckForUpdateHandlesMalformedJsonSafely() = runTest {
        val mockClient = createMockClient { _ ->
            Pair(HttpStatusCode.OK, "{ broken json content ...")
        }

        val checker = GithubReleaseUpdateChecker(
            config = UpdateConfig(),
            httpClient = mockClient
        )

        val result = checker.checkForUpdate(currentVersion = "1.0.0")
        assertTrue(result.isFailure)
    }

    @Test
    fun testCheckForUpdateHandlesReleaseArrayEndpoint() = runTest {
        val jsonPayload = """
            [
              {
                "tag_name": "v1.8.0",
                "body": "First item in releases list",
                "html_url": "https://github.com/qmusic/qmusic-app/releases/tag/v1.8.0",
                "assets": [
                  {
                    "name": "qmusic-v1.8.0.apk",
                    "browser_download_url": "https://github.com/qmusic/qmusic-app/releases/download/v1.8.0/qmusic-v1.8.0.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val mockClient = createMockClient { _ ->
            Pair(HttpStatusCode.OK, jsonPayload)
        }

        val checker = GithubReleaseUpdateChecker(
            config = UpdateConfig(endpointUrl = "https://api.github.com/repos/qmusic/qmusic-app/releases"),
            httpClient = mockClient
        )

        val result = checker.checkForUpdate(currentVersion = "1.0.0")
        assertTrue(result.isSuccess)
        val info = result.getOrNull()
        assertNotNull(info)
        assertEquals("1.8.0", info.versionName)
    }
}

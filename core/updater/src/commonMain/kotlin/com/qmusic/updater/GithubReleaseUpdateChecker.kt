package com.qmusic.updater

import com.qmusic.updater.model.UpdateInfo
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class GithubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val id: Long? = null,
    @SerialName("version_code") val versionCode: Long? = null,
    @SerialName("is_mandatory") val isMandatory: Boolean? = null,
    val mandatory: Boolean? = null,
    val assets: List<GithubAssetDto> = emptyList(),
)

@Serializable
internal data class GithubAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    @SerialName("content_type") val contentType: String? = null,
    val size: Long = 0L,
)

internal data class SemVer(
    val parts: List<Int>,
    val prerelease: String? = null,
) : Comparable<SemVer> {
    val major: Int get() = parts.getOrElse(0) { 0 }
    val minor: Int get() = parts.getOrElse(1) { 0 }
    val patch: Int get() = parts.getOrElse(2) { 0 }

    fun toVersionCode(): Long =
        major * 1_000_000L + minor * 1_000L + patch.toLong()

    override fun compareTo(other: SemVer): Int {
        val maxLen = maxOf(parts.size, other.parts.size)
        for (i in 0 until maxLen) {
            val p1 = parts.getOrElse(i) { 0 }
            val p2 = other.parts.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        if (prerelease == null && other.prerelease == null) return 0
        if (prerelease == null && other.prerelease != null) return 1
        if (prerelease != null && other.prerelease == null) return -1
        return comparePrereleases(prerelease!!, other.prerelease!!)
    }

    companion object {
        fun parse(version: String): SemVer? {
            val clean = version.trim().removePrefix("v").removePrefix("V")
            if (clean.isBlank()) return null

            val withoutBuild = clean.substringBefore('+')
            val corePart = withoutBuild.substringBefore('-')
            val prereleasePart = if (withoutBuild.contains('-')) {
                withoutBuild.substringAfter('-').takeIf { it.isNotBlank() }
            } else null

            val segments = corePart.split('.')
            val numericParts = segments.map { it.toIntOrNull() ?: 0 }
            if (numericParts.isEmpty()) return null

            return SemVer(numericParts, prereleasePart)
        }

        private fun comparePrereleases(pre1: String, pre2: String): Int {
            val parts1 = pre1.split('.')
            val parts2 = pre2.split('.')
            val maxLen = maxOf(parts1.size, parts2.size)
            for (i in 0 until maxLen) {
                val p1 = parts1.getOrNull(i)
                val p2 = parts2.getOrNull(i)
                if (p1 == null) return -1
                if (p2 == null) return 1
                val n1 = p1.toIntOrNull()
                val n2 = p2.toIntOrNull()
                val cmp = when {
                    n1 != null && n2 != null -> n1.compareTo(n2)
                    n1 != null && n2 == null -> -1
                    n1 == null && n2 != null -> 1
                    else -> p1.compareTo(p2)
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

class GithubReleaseUpdateChecker(
    private val config: UpdateConfig = UpdateConfig(),
    private val httpClient: HttpClient = createDefaultHttpClient(),
) : UpdateChecker {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun isNewerVersion(candidate: String, current: String): Boolean =
        Companion.isNewerVersion(candidate, current)

    private fun resolveDownloadUrl(assets: List<GithubAssetDto>, releaseHtmlUrl: String?): String? {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val universalApk = apkAssets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
        val prefixApk = if (config.apkNamePrefix.isNotBlank()) {
            apkAssets.firstOrNull { it.name.contains(config.apkNamePrefix, ignoreCase = true) }
        } else null
        val bestApk = universalApk ?: prefixApk ?: apkAssets.firstOrNull()

        return bestApk?.browserDownloadUrl?.takeIf { it.isNotBlank() }
            ?: releaseHtmlUrl?.takeIf { it.isNotBlank() }
    }

    override suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo?> = runCatching {
        val response: HttpResponse = httpClient.get(config.endpointUrl) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "QMusic-App")
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("HTTP request failed with status: ${response.status.value} ${response.status.description}")
        }
        val bodyText = response.bodyAsText().trim()
        val releaseDto = if (bodyText.startsWith("[")) {
            json.decodeFromString<List<GithubReleaseDto>>(bodyText).firstOrNull()
                ?: throw IllegalStateException("Release list from endpoint is empty")
        } else {
            json.decodeFromString<GithubReleaseDto>(bodyText)
        }

        val tagName = releaseDto.tagName?.trim()
            ?: throw IllegalStateException("GitHub release does not contain tag_name")

        if (!isNewerVersion(candidate = tagName, current = currentVersion)) {
            return@runCatching null
        }

        val downloadUrl = resolveDownloadUrl(releaseDto.assets, releaseDto.htmlUrl)
            ?: throw IllegalStateException("No download URL or release URL found for $tagName")

        val semVer = SemVer.parse(tagName)
        val vCode = releaseDto.versionCode ?: releaseDto.id ?: semVer?.toVersionCode() ?: 0L
        val vName = tagName.removePrefix("v").removePrefix("V").ifBlank { tagName }
        val changelog = releaseDto.body ?: ""
        val isMandatory = releaseDto.isMandatory
            ?: releaseDto.mandatory
            ?: (changelog.contains("[mandatory]", ignoreCase = true) || changelog.contains("mandatory: true", ignoreCase = true))

        UpdateInfo(
            versionName = vName,
            versionCode = vCode,
            changelog = changelog,
            downloadUrl = downloadUrl,
            isMandatory = isMandatory,
        )
    }

    companion object {
        fun isNewerVersion(candidate: String, current: String): Boolean {
            val candSemVer = SemVer.parse(candidate) ?: return false
            val currSemVer = SemVer.parse(current) ?: SemVer(listOf(0, 0, 0))
            return candSemVer > currSemVer
        }

        fun createDefaultHttpClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true
                })
            }
        }
    }
}

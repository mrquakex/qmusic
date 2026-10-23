package com.qmusic.updater

data class UpdateConfig(
    val endpointUrl: String = DEFAULT_RELEASE_ENDPOINT,
    val apkNamePrefix: String = "qmusic",
    val checkPrereleases: Boolean = false,
) {
    companion object {
        const val DEFAULT_OWNER: String = "mrquakex"
        const val DEFAULT_REPO: String = "qmusic"
        const val DEFAULT_RELEASE_ENDPOINT: String =
            "https://api.github.com/repos/$DEFAULT_OWNER/$DEFAULT_REPO/releases/latest"

        fun forGithubRepo(owner: String, repo: String): UpdateConfig =
            UpdateConfig(endpointUrl = "https://api.github.com/repos/$owner/$repo/releases/latest")
    }
}

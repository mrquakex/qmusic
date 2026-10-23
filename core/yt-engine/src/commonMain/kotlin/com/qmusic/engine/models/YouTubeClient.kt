package com.qmusic.engine.models

import kotlinx.serialization.Serializable

@Serializable
data class YouTubeLocale(
    val gl: String = "TR",
    val hl: String = "tr",
)

@Serializable
data class InnertubeContext(
    val client: Client,
    val thirdParty: ThirdParty? = null,
) {
    @Serializable
    data class Client(
        val clientName: String,
        val clientVersion: String,
        val osVersion: String? = null,
        val gl: String = "TR",
        val hl: String = "tr",
        val visitorData: String? = null,
        val userAgent: String? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val timeZone: String? = null,
        val utcOffsetMinutes: Int? = null,
        val androidSdkVersion: Int? = null,
    )

    @Serializable
    data class ThirdParty(
        val embedUrl: String? = null,
    )
}

@Serializable
data class YouTubeClient(
    val clientName: String,
    val clientVersion: String,
    val apiKey: String,
    val userAgent: String,
    val osVersion: String? = null,
    val referer: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val timeZone: String? = null,
    val utcOffsetMinutes: Int? = null,
    val xClientName: Int? = null,
    val androidSdkVersion: Int? = null,
) {
    fun toContext(
        locale: YouTubeLocale = YouTubeLocale(),
        visitorData: String? = null,
    ) = InnertubeContext(
        client = InnertubeContext.Client(
            clientName = clientName,
            clientVersion = clientVersion,
            osVersion = osVersion,
            gl = locale.gl,
            hl = locale.hl,
            visitorData = visitorData,
            userAgent = userAgent,
            deviceMake = deviceMake,
            deviceModel = deviceModel,
            osName = osName,
            timeZone = timeZone,
            utcOffsetMinutes = utcOffsetMinutes,
            androidSdkVersion = androidSdkVersion,
        ),
    )

    companion object {
        const val REFERER_YOUTUBE_MUSIC = "https://music.youtube.com/"

        const val USER_AGENT_WEB =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        const val USER_AGENT_ANDROID =
            "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip"
        const val USER_AGENT_ANDROID_VR =
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        const val USER_AGENT_IOS =
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

        val ANDROID_MUSIC =
            YouTubeClient(
                clientName = "ANDROID_MUSIC",
                clientVersion = "7.27.52",
                apiKey = "AIzaSyAOghZGza2MQSZkY_zfZ370N-PUdXEo8AI",
                userAgent = USER_AGENT_ANDROID,
                osName = "Android",
                osVersion = "11",
                xClientName = 21,
            )

        val ANDROID =
            YouTubeClient(
                clientName = "ANDROID",
                clientVersion = "21.26.364",
                apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
                userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
                osName = "Android",
                osVersion = "11",
                xClientName = 3,
            )

        /** Quest client: its stream URLs are not gated behind a PO token, so full-length playback works. */
        val ANDROID_VR =
            YouTubeClient(
                clientName = "ANDROID_VR",
                clientVersion = "1.65.10",
                apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
                userAgent = USER_AGENT_ANDROID_VR,
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                osName = "Android",
                osVersion = "12L",
                xClientName = 28,
                androidSdkVersion = 32,
            )

        val WEB =
            YouTubeClient(
                clientName = "WEB",
                clientVersion = "2.20250312.04.00",
                apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3",
                userAgent = USER_AGENT_WEB,
            )

        val WEB_REMIX =
            YouTubeClient(
                clientName = "WEB_REMIX",
                clientVersion = "1.20260304.03.00",
                apiKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
                userAgent = USER_AGENT_WEB,
                referer = REFERER_YOUTUBE_MUSIC,
            )

        val IOS =
            YouTubeClient(
                clientName = "IOS",
                clientVersion = "20.10.4",
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
                userAgent = USER_AGENT_IOS,
                apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
                osName = "iPhone",
                osVersion = "18.3.2.22D82",
                timeZone = "UTC",
                utcOffsetMinutes = 0,
                xClientName = 5,
            )

        val TVHTML5 =
            YouTubeClient(
                clientName = "TVHTML5",
                clientVersion = "7.20250312.16.00",
                apiKey = "AIzaSyDCU8hByM-4DrUqRUYnGn-3llEO78bcxq8",
                userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
            )
    }
}

package com.qmusic.ui

import com.qmusic.ui.chat.ChatMusic

/**
 * What the screen reader (TalkBack) says for a button that is only an icon, in the app's language. Without it such a
 * button is read as just "button".
 */
object A11y {
    private fun t(en: String, tr: String) = if (ChatMusic.english) en else tr

    val back get() = t("Back", "Geri")
    val close get() = t("Close", "Kapat")
    val minimize get() = t("Minimize", "Küçült")
    val more get() = t("More options", "Diğer seçenekler")
    val delete get() = t("Delete", "Sil")
    val previous get() = t("Previous", "Önceki")
    val next get() = t("Next", "Sonraki")
    val play get() = t("Play", "Oynat")
    val pause get() = t("Pause", "Duraklat")
    val fullscreen get() = t("Full screen", "Tam ekran")
    val exitFullscreen get() = t("Exit full screen", "Tam ekrandan çık")
    val listView get() = t("List view", "Liste görünümü")
    val gridView get() = t("Grid view", "Izgara görünümü")
    val fillQuery get() = t("Put in the search box", "Arama kutusuna yaz")
}

package com.qmusic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopIconTest {
    @Test
    fun generatesMultiResolutionQMusicIco() {
        val resourceIco = File("src/desktopMain/resources/qmusic.ico")
        DesktopIcon.writeIcoFile(resourceIco)
        assertTrue(resourceIco.exists(), "qmusic.ico should be created")
        assertTrue(resourceIco.length() > 4096L, "qmusic.ico should contain multi-resolution PNG frames")

        val homeBase = File(System.getProperty("user.home"), ".qmusic").apply { mkdirs() }
        val homeIco = File(homeBase, "qmusic.ico")
        DesktopIcon.writeIcoFile(homeIco)
        assertTrue(homeIco.exists(), "~/.qmusic/qmusic.ico should be created")
    }
}

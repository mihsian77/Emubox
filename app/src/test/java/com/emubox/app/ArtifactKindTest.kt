package com.emubox.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 发布文件的平台归类。
 *
 * 锁住两条容易改坏的口径：
 * 1. `android.zip` 必须归安卓（不少模拟器把 apk 打进 zip 发，只按后缀会漏）；
 * 2. `source-1.0.tar.gz` 必须归源码（后缀是 gz，先按后缀判会落进「其他」）。
 */
class ArtifactKindTest {

    @Test
    fun apkIsAndroid() {
        assertEquals(ArtifactKind.ANDROID, artifactKindOf("Termux_0.118.1.apk"))
        assertEquals(ArtifactKind.ANDROID, artifactKindOf("app-release.APKS"))
    }

    @Test
    fun androidZipIsAndroid() {
        assertEquals(ArtifactKind.ANDROID, artifactKindOf("emubox-android.zip"))
    }

    @Test
    fun ipaIsApple() {
        assertEquals(ArtifactKind.APPLE, artifactKindOf("Delta.ipa"))
    }

    @Test
    fun desktopAndConsoleAreSplit() {
        assertEquals(ArtifactKind.DESKTOP, artifactKindOf("setup-x64.exe"))
        assertEquals(ArtifactKind.DESKTOP, artifactKindOf("App-1.2.dmg"))
        assertEquals(ArtifactKind.CONSOLE, artifactKindOf("game.nsp"))
        assertEquals(ArtifactKind.CONSOLE, artifactKindOf("rom.xci"))
    }

    @Test
    fun sourceArchiveIsSource() {
        assertEquals(ArtifactKind.SOURCE, artifactKindOf("source-1.0.tar.gz"))
        assertEquals(ArtifactKind.SOURCE, artifactKindOf("project-src.zip"))
    }

    @Test
    fun unknownFallsBackToOther() {
        assertEquals(ArtifactKind.OTHER, artifactKindOf("checksums.txt"))
        assertEquals(ArtifactKind.OTHER, artifactKindOf("no-extension"))
    }
}

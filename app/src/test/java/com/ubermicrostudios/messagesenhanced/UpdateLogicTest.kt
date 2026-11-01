package com.ubermicrostudios.messagesenhanced

import android.content.Intent
import android.os.Build
import androidx.work.Data
import com.ubermicrostudios.messagesenhanced.update.ApkIdentity
import com.ubermicrostudios.messagesenhanced.update.AppRelease
import com.ubermicrostudios.messagesenhanced.update.ReleaseParser
import com.ubermicrostudios.messagesenhanced.update.SemVer
import com.ubermicrostudios.messagesenhanced.update.UpdateChecker
import com.ubermicrostudios.messagesenhanced.update.UpdateDownloadSpec
import com.ubermicrostudios.messagesenhanced.update.UpdatePolicy
import com.ubermicrostudios.messagesenhanced.update.UpdateProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    @Test
    fun semverOrdersMajorMinorPatchAndAcceptsALeadingV() {
        assertEquals(SemVer(1, 2, 3), SemVer.parse("v1.2.3"))
        assertEquals(SemVer(1, 2, 3), SemVer.parse("1.2.3"))
        assertEquals(SemVer(1, 2, 3), SemVer.parse("V1.2.3"))
        assertEquals(SemVer(1, 2, 3), SemVer.parse("  v1.2.3  "))
        assertNull(SemVer.parse("1.2"))
        assertNull(SemVer.parse("v1.2.3-beta"))
        assertNull(SemVer.parse("v1.2.3.4"))
        assertNull(SemVer.parse(""))

        assertTrue(SemVer(1, 2, 4) > SemVer(1, 2, 3))
        assertTrue(SemVer(1, 3, 0) > SemVer(1, 2, 9))
        assertTrue(SemVer(2, 0, 0) > SemVer(1, 9, 9))
        assertEquals(0, SemVer(0, 6, 0).compareTo(SemVer(0, 6, 0)))
        assertTrue(SemVer.isNewer("v0.6.1", "0.6.0"))
        assertFalse(SemVer.isNewer("v0.6.0", "0.6.0"))
        assertFalse(SemVer.isNewer("v0.5.9", "0.6.0"))
        assertFalse(SemVer.isNewer("not-a-version", "0.6.0"))
        assertNull(SemVer.parse("2147483648.0.0"))
        assertNull(SemVer.parse("v1.9999999999.0"))
        assertEquals(SemVer(2147483647, 0, 0), SemVer.parse("2147483647.0.0"))
    }

    @Test
    fun rejectInstallRequiresTheSamePackageANewerCodeAndTheSameSigners() {
        val installed = ApkIdentity("app", 11L, setOf("aa", "bb"))
        assertNull(UpdatePolicy.rejectInstall(installed, ApkIdentity("app", 12L, setOf("bb", "aa"))))
        assertEquals(
            "This update is for a different app.",
            UpdatePolicy.rejectInstall(installed, ApkIdentity("other", 12L, setOf("aa", "bb"))),
        )
        assertEquals(
            "This update is for a different app.",
            UpdatePolicy.rejectInstall(installed, ApkIdentity(" ", 12L, setOf("aa", "bb"))),
        )
        assertEquals(
            "This update is not newer than the installed version.",
            UpdatePolicy.rejectInstall(installed, ApkIdentity("app", 11L, setOf("aa", "bb"))),
        )
        assertEquals(
            "This update is not newer than the installed version.",
            UpdatePolicy.rejectInstall(installed, ApkIdentity("app", 10L, setOf("aa", "bb"))),
        )
        assertEquals(
            "This update is signed with a different key.",
            UpdatePolicy.rejectInstall(installed, ApkIdentity("app", 12L, setOf("aa"))),
        )
        assertEquals(
            "This update is signed with a different key.",
            UpdatePolicy.rejectInstall(installed, ApkIdentity("app", 12L, emptySet())),
        )
        assertEquals(
            "This update is signed with a different key.",
            UpdatePolicy.rejectInstall(
                installed.copy(signers = emptySet()),
                ApkIdentity("app", 12L, setOf("aa", "bb")),
            ),
        )
    }

    @Test
    fun rejectInstallAcceptsARotatedKeyWhenTheArchiveLineageIncludesTheCurrentSigner() {
        val installed = ApkIdentity("app", 11L, setOf("old"))
        val rotated = ApkIdentity(
            packageName = "app",
            versionCode = 12L,
            signers = setOf("new"),
            signingHistory = setOf("old", "new"),
            hasPastSigningCertificates = true,
        )
        assertNull(UpdatePolicy.rejectInstall(installed, rotated, allowSigningLineage = true))
        assertEquals(
            "This update is signed with a different key.",
            UpdatePolicy.rejectInstall(installed, rotated, allowSigningLineage = false),
        )
        assertEquals(
            "This update is signed with a different key.",
            UpdatePolicy.rejectInstall(
                installed,
                rotated.copy(signingHistory = setOf("new"), hasPastSigningCertificates = true),
                allowSigningLineage = true,
            ),
        )
        assertEquals(
            "This update is signed with a different key.",
            UpdatePolicy.rejectInstall(
                installed,
                rotated.copy(hasPastSigningCertificates = false),
                allowSigningLineage = true,
            ),
        )
        assertTrue(UpdatePolicy.lineageIncludesCurrentSigner(installed, rotated))
        assertFalse(
            UpdatePolicy.lineageIncludesCurrentSigner(
                installed,
                rotated.copy(signingHistory = setOf("new")),
            ),
        )
        val both = ApkIdentity("app", 11L, setOf("old", "mid"))
        assertFalse(
            UpdatePolicy.lineageIncludesCurrentSigner(
                both,
                rotated.copy(signingHistory = setOf("old", "new"), hasPastSigningCertificates = true),
            ),
        )
        assertTrue(
            UpdatePolicy.lineageIncludesCurrentSigner(
                both,
                ApkIdentity(
                    packageName = "app",
                    versionCode = 12L,
                    signers = setOf("new"),
                    signingHistory = setOf("old", "mid", "new"),
                    hasPastSigningCertificates = true,
                ),
            ),
        )
    }

    @Test
    fun releaseJsonReadsTagNotesAndTheApkAsset() {
        val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val json = """
            {
              "tag_name": "v1.2.3",
              "name": "ignored title",
              "body": "Fixes\n\n- one",
              "assets": [
                {
                  "name": "notes.txt",
                  "browser_download_url": "https://example.test/notes.txt",
                  "size": 12
                },
                {
                  "name": "messages-enhanced-native-v1.2.3.apk",
                  "browser_download_url": "https://example.test/app.apk",
                  "size": 4096,
                  "digest": "sha256:$sha",
                  "extra": true
                },
                {
                  "name": "other.apk",
                  "browser_download_url": "https://example.test/other.apk",
                  "size": 1
                }
              ]
            }
        """.trimIndent()
        val parsed = ReleaseParser.parse(json)
        assertEquals(SemVer(1, 2, 3), parsed?.version)
        assertEquals("v1.2.3", parsed?.tag)
        assertEquals("Fixes\n\n- one", parsed?.notes)
        val apk = parsed?.apk
        assertEquals("messages-enhanced-native-v1.2.3.apk", apk?.name)
        assertEquals("https://example.test/app.apk", apk?.downloadUrl)
        assertEquals(4096L, apk?.size)
        assertEquals(sha, apk?.sha256)
        assertEquals(false, apk?.digestInvalid)
    }

    @Test
    fun releaseJsonTreatsAMissingOrBadDigestAndAMissingApk() {
        val noDigest = """
            {"tag_name":"v2.0.0","body":"","assets":[
              {"name":"App.APK","browser_download_url":"https://example.test/a.apk","size":9}
            ]}
        """.trimIndent()
        val plain = ReleaseParser.parse(noDigest)
        assertEquals(SemVer(2, 0, 0), plain?.version)
        assertNull(plain?.apk?.sha256)
        assertEquals(false, plain?.apk?.digestInvalid)
        assertEquals("App.APK", plain?.apk?.name)

        val bad = """
            {"tag_name":"v2.0.1","body":"n","assets":[
              {"name":"a.apk","browser_download_url":"https://example.test/a.apk","size":9,"digest":"md5:abcd"}
            ]}
        """.trimIndent()
        val invalid = ReleaseParser.parse(bad)?.apk
        assertNull(invalid?.sha256)
        assertEquals(true, invalid?.digestInvalid)
        assertNull(ReleaseParser.sha256Hex("sha256:abcd"))
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ReleaseParser.sha256Hex("SHA256:0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"),
        )

        val noApk = """{"tag_name":"v3.0.0","body":"notes","assets":[{"name":"readme.txt","size":1}]}"""
        val parsed = ReleaseParser.parse(noApk)
        assertEquals(SemVer(3, 0, 0), parsed?.version)
        assertEquals("notes", parsed?.notes)
        assertNull(parsed?.apk)

        assertNull(ReleaseParser.parse("""{"tag_name":"nightly","assets":[]}"""))
        assertNull(ReleaseParser.parse("not json"))
    }

    @Test
    fun autoCheckIsDueEverySixHoursAndNotBefore() {
        val interval = UpdatePolicy.CHECK_INTERVAL_MS
        assertEquals(6L * 60L * 60L * 1000L, interval)
        assertEquals(interval, UpdateChecker.CHECK_INTERVAL_MS)
        assertTrue(UpdatePolicy.autoCheckDue(lastCheckAt = 0L, now = interval))
        assertTrue(UpdatePolicy.autoCheckDue(lastCheckAt = 1_000L, now = 1_000L + interval))
        assertFalse(UpdatePolicy.autoCheckDue(lastCheckAt = 0L, now = interval - 1L))
        assertFalse(UpdatePolicy.autoCheckDue(lastCheckAt = 5_000L, now = 5_000L))
        assertFalse(UpdatePolicy.autoCheckDue(lastCheckAt = 5_000L, now = 4_000L))
    }

    @Test
    fun silentInstallAndDownloadedSourceFollowTheApiCutoff() {
        assertFalse(UpdatePolicy.requestSilentInstall(Build.VERSION_CODES.R))
        assertTrue(UpdatePolicy.requestSilentInstall(Build.VERSION_CODES.S))
        assertTrue(UpdatePolicy.requestSilentInstall(34))
        assertFalse(UpdatePolicy.markDownloadedFileSource(33))
        assertTrue(UpdatePolicy.markDownloadedFileSource(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertTrue(UpdatePolicy.markDownloadedFileSource(35))
    }

    @Test
    fun downloadPercentClampsAndTreatsAnUnknownLengthAsIndeterminate() {
        assertNull(UpdatePolicy.downloadPercent(read = 10L, total = 0L))
        assertNull(UpdatePolicy.downloadPercent(read = 10L, total = -1L))
        assertEquals(0, UpdatePolicy.downloadPercent(read = 0L, total = 100L))
        assertEquals(0, UpdatePolicy.downloadPercent(read = -5L, total = 100L))
        assertEquals(33, UpdatePolicy.downloadPercent(read = 1L, total = 3L))
        assertEquals(50, UpdatePolicy.downloadPercent(read = 50L, total = 100L))
        assertEquals(100, UpdatePolicy.downloadPercent(read = 100L, total = 100L))
        assertEquals(100, UpdatePolicy.downloadPercent(read = 250L, total = 100L))
        assertEquals(40, UpdateProgress.Downloading(read = 40L, total = 100L).percent)
        assertNull(UpdateProgress.Downloading(read = 40L, total = -1L).percent)
    }

    @Test
    fun relaunchOnlyAfterAnUpdateThisAppStarted() {
        assertEquals(Intent.ACTION_MY_PACKAGE_REPLACED, UpdatePolicy.PACKAGE_REPLACED_ACTION)
        val replaced = UpdatePolicy.PACKAGE_REPLACED_ACTION
        assertTrue(UpdatePolicy.shouldRelaunch(replaced, triggeredByApp = true))
        assertFalse(UpdatePolicy.shouldRelaunch(replaced, triggeredByApp = false))
        assertFalse(UpdatePolicy.shouldRelaunch(Intent.ACTION_BOOT_COMPLETED, triggeredByApp = true))
        assertFalse(UpdatePolicy.shouldRelaunch(action = null, triggeredByApp = true))
    }

    @Test
    fun autoUpdateSkipsTheOfferAndTheDefaultKeepsIt() {
        assertFalse(UpdatePolicy.installsWithoutOffer(autoUpdate = false))
        assertTrue(UpdatePolicy.installsWithoutOffer(autoUpdate = true))
    }

    @Test
    fun backgroundCheckIsTwelveHoursAndNamesTheWorkers() {
        assertEquals(12L, UpdatePolicy.BACKGROUND_CHECK_HOURS)
        assertEquals("update_download_install", UpdatePolicy.DOWNLOAD_WORK_NAME)
        assertEquals("update_check", UpdatePolicy.CHECK_WORK_NAME)
        assertTrue(UpdatePolicy.DOWNLOAD_WORK_NAME != UpdatePolicy.CHECK_WORK_NAME)
    }

    @Test
    fun sessionCallbackIsAcceptedForThisProcessOrAFreshOne() {
        assertTrue(UpdatePolicy.acceptSessionCallback(currentToken = 0, callbackToken = 4))
        assertTrue(UpdatePolicy.acceptSessionCallback(currentToken = 3, callbackToken = 3))
        assertFalse(UpdatePolicy.acceptSessionCallback(currentToken = 3, callbackToken = 2))
        assertFalse(UpdatePolicy.acceptSessionCallback(currentToken = 3, callbackToken = 0))
    }

    @Test
    fun downloadSpecRoundTripsTheReleaseCoordinates() {
        val release = AppRelease(
            version = SemVer(0, 6, 3),
            tag = "v0.6.3",
            notes = "leave these behind",
            apkName = "messages.apk",
            downloadUrl = "https://example.test/messages.apk",
            size = 4096L,
            sha256 = "abc123",
        )
        val spec = UpdateDownloadSpec.from(release)
        val restored = UpdateDownloadSpec.from(spec.toWorkData())?.toRelease()
        assertEquals(SemVer(0, 6, 3), restored?.version)
        assertEquals("v0.6.3", restored?.tag)
        assertEquals("", restored?.notes)
        assertEquals("messages.apk", restored?.apkName)
        assertEquals("https://example.test/messages.apk", restored?.downloadUrl)
        assertEquals(4096L, restored?.size)
        assertEquals("abc123", restored?.sha256)

        val noHash = UpdateDownloadSpec.from(spec.copy(sha256 = null).toWorkData())
        assertNull(noHash?.sha256)
        assertEquals(spec.copy(sha256 = null).downloadUrl, noHash?.downloadUrl)

        assertNull(UpdateDownloadSpec("nope", "t", "a.apk", "https://e.test/a.apk", 1L, null).toRelease())
        assertNull(UpdateDownloadSpec.from(Data.EMPTY))
    }
}

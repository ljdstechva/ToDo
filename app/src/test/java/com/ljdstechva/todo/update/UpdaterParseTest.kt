package com.ljdstechva.todo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterParseTest {

    @Test fun versionCodeFromTag() {
        assertEquals(12, Updater.versionCodeOf("v1.0.12"))
        assertEquals(3, Updater.versionCodeOf("1.0.3"))
        assertEquals(140, Updater.versionCodeOf(" v2.1.140 "))
        assertNull(Updater.versionCodeOf("latest"))
        assertNull(Updater.versionCodeOf("v1.0"))
    }

    @Test fun apkNameMatchesTheReleaseAsset() =
        assertEquals("ToDo-v1.0.7.apk", Updater.apkName("v1.0.7"))

    @Test fun parsesReleasesAndPicksTheNewest() {
        val body = """
            [
              {"tag_name": "v1.0.9", "name": "draft", "draft": true, "body": "",
               "assets": [{"name": "ToDo-v1.0.9.apk", "browser_download_url": "https://x/9.apk", "size": 9}]},
              {"tag_name": "v1.0.8", "name": "To-Do v1.0.8", "draft": false, "body": "Notes 8", "published_at": "2026-10-29T01:00:00Z",
               "assets": [{"name": "notes.txt", "browser_download_url": "https://x/n.txt", "size": 1},
                          {"name": "ToDo-v1.0.8.apk", "browser_download_url": "https://x/8.apk", "size": 4200000}]},
              {"tag_name": "v1.0.7", "name": null, "draft": false, "body": null,
               "assets": [{"name": "ToDo-v1.0.7.apk", "browser_download_url": "https://x/7.apk", "size": 4100000}]},
              {"tag_name": "v1.0.6", "name": "no apk", "draft": false, "assets": []},
              {"tag_name": "nightly", "name": "odd tag", "draft": false,
               "assets": [{"name": "x.apk", "browser_download_url": "https://x/x.apk", "size": 1}]}
            ]
        """.trimIndent()
        val releases = Updater.parseReleases(body)
        assertEquals(listOf("v1.0.8", "v1.0.7"), releases.map { it.tag })
        val newest = Updater.newest(releases)!!
        assertEquals(8, newest.versionCode)
        assertEquals("https://x/8.apk", newest.apkUrl)
        assertEquals(4_200_000L, newest.size)
        assertEquals("Notes 8", newest.notes)
        val seven = releases.first { it.tag == "v1.0.7" }
        assertEquals("v1.0.7", seven.title)
        assertEquals("", seven.notes)
    }

    @Test fun emptyListHasNoNewest() = assertNull(Updater.newest(Updater.parseReleases("[]")))
}

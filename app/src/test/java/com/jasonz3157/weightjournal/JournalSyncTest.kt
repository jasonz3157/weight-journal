package com.jasonz3157.weightjournal

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class JournalSyncTest {
    private val firstDay = "2026-11-22"
    private val secondDay = "2026-11-23"
    private val sample = """
        {"version":1,"entries":{
          "height":{"value":175,"clock":1795472400000,"id":"height-device"},
          "2026-11-22":{"value":71.28,"clock":1795305600000,"id":"first-device"},
          "2026-11-23":{"value":null,"clock":1795392000000,"id":"removed-device"}
        }}
    """.trimIndent()

    @Test fun siteSnapshotRoundTripsIncludingTombstones() {
        val site = Journal.parse(sample)
        assertEquals(site, Journal.parse(site.toJson()))
        assertEquals(175.0, site.height ?: 0.0, 0.001)
        assertEquals(71.28, site.records(LocalDate.parse(secondDay)).single().kg, 0.001)
        assertNull(site.entries.getValue(secondDay).value)
        assertSame(site, site.set(HEIGHT_KEY, 175.0))
        val updated = site.set(firstDay, 70.0, now = 10L)
        assertEquals(1795472400001L, updated.entries.getValue(firstDay).clock)
    }

    @Test fun siteConflictOrderPrefersClockThenIdThenValueWithNullLast() {
        val old = Journal(mapOf(firstDay to JournalEntry(70.0, 8, "c")))
        val higherId = Journal(mapOf(firstDay to JournalEntry(69.0, 8, "d")))
        val newer = Journal(mapOf(firstDay to JournalEntry(68.0, 9, "a")))
        val deletion = Journal(mapOf(firstDay to JournalEntry(null, 9, "a")))
        assertSame(higherId, higherId.merge(old))
        assertEquals(newer, higherId.merge(newer))
        assertEquals(deletion, newer.merge(deletion))
        assertSame(deletion, deletion.merge(newer))
    }

    @Test fun remoteConflictReadsNewestFileBeforeRetryingAndKeepsBothDevicesEdits() {
        val local = Journal().set(firstDay, 70.0, now = 8)
        val updatedRemote = Journal().set(secondDay, 69.0, now = 10)
        val expected = updatedRemote.merge(local)
        val requests = mutableListOf<Request>()
        val client = scriptedClient { request ->
            requests += request
            when (requests.size) {
                1 -> reply(request, 404)
                2 -> {
                    assertEquals("*", request.header("If-None-Match"))
                    reply(request, 412)
                }
                3 -> reply(request, 200, updatedRemote.toJson(), "ETag" to "\"v2\"")
                4 -> {
                    assertEquals("\"v2\"", request.header("If-Match"))
                    assertEquals(expected, Journal.parse(Buffer().also { request.body!!.writeTo(it) }.readUtf8()))
                    reply(request, 204)
                }
                else -> error("Unexpected request")
            }
        }
        val settings = DavSettings.validate("https://example.test/体重/", "user", "fixture")
        assertEquals(expected, client.exchange(settings) { local })
        assertEquals(listOf("GET", "PUT", "GET", "PUT"), requests.map { it.method })
        assertTrue(requests.all { it.url.encodedPath == "/%E4%BD%93%E9%87%8D/weight-journal.json" })
    }

    @Test fun nutstoreReadsAndWritesDirectlyAndMergesAfterLocking() {
        val local = Journal().set(firstDay, 68.4, now = 8)
        val changedRemote = Journal().set(secondDay, 69.0, now = 10)
        val token = "<opaquelocktoken:fixture>"
        val methods = mutableListOf<String>()
        val client = scriptedClient { request ->
            methods += request.method
            assertEquals("dav.jianguoyun.com", request.url.host)
            assertEquals("/dav/weight-journal.json", request.url.encodedPath)
            assertNull(request.header("X-WebDAV-Authorization"))
            assertTrue(request.header("Authorization")!!.startsWith("Basic "))
            when (methods.size) {
                1 -> reply(request, 200, Journal().toJson())
                2 -> {
                    assertEquals("0", request.header("Depth"))
                    reply(request, 200, "", "Lock-Token" to token)
                }
                3 -> reply(request, 200, changedRemote.toJson())
                4 -> {
                    assertEquals("($token)", request.header("If"))
                    val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertEquals(changedRemote.merge(local), Journal.parse(body))
                    reply(request, 204)
                }
                5 -> {
                    assertEquals(token, request.header("Lock-Token"))
                    reply(request, 204)
                }
                else -> error("Unexpected request")
            }
        }
        val settings = DavSettings.validate("https://dav.jianguoyun.com/dav/", "user", "fixture")
        assertEquals(changedRemote.merge(local), client.exchange(settings) { local })
        assertEquals(listOf("GET", "LOCK", "GET", "PUT", "UNLOCK"), methods)
    }

    @Test fun nutstoreDownloadsWithoutLockWhenThereAreNoLocalChanges() {
        val remote = Journal.parse(sample)
        val client = scriptedClient { request ->
            assertEquals("dav.jianguoyun.com", request.url.host)
            assertEquals("/dav/custom.JSON", request.url.encodedPath)
            assertEquals("GET", request.method)
            reply(request, 200, remote.toJson())
        }
        val settings = DavSettings.validate("https://dav.jianguoyun.com/dav/custom.JSON", "user", "fixture")
        assertEquals(remote, client.exchange(settings) { Journal() })
    }

    @Test fun nutstoreCreatesMissingFileWithTheNewLockToken() {
        val local = Journal().set(firstDay, 68.4, now = 8)
        val token = "<opaquelocktoken:new-file>"
        val methods = mutableListOf<String>()
        val client = scriptedClient { request ->
            methods += request.method
            when (request.method) {
                "GET" -> reply(request, 404)
                "LOCK" -> reply(request, 201, "", "Lock-Token" to token)
                "PUT" -> {
                    assertEquals("($token)", request.header("If"))
                    val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertEquals(local, Journal.parse(body))
                    reply(request, 201)
                }
                "UNLOCK" -> reply(request, 204)
                else -> error("Unexpected request")
            }
        }
        val settings = DavSettings.validate("https://dav.jianguoyun.com/dav/", "user", "fixture")
        assertEquals(local, client.exchange(settings) { local })
        assertEquals(listOf("GET", "LOCK", "PUT", "UNLOCK"), methods)
    }

    @Test fun nutstoreCanRetryAfterAFileWasCreatedButItsFirstUploadFailed() {
        val local = Journal().set(firstDay, 68.4, now = 8)
        val token = "<opaquelocktoken:retry>"
        val methods = mutableListOf<String>()
        val client = scriptedClient { request ->
            methods += request.method
            when (request.method) {
                "GET" -> reply(request, 200, "")
                "LOCK" -> reply(request, 200, "", "Lock-Token" to token)
                "PUT" -> {
                    assertEquals("($token)", request.header("If"))
                    val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertEquals(local, Journal.parse(body))
                    reply(request, 204)
                }
                "UNLOCK" -> reply(request, 204)
                else -> error("Unexpected request")
            }
        }
        val settings = DavSettings.validate("https://dav.jianguoyun.com/dav/", "user", "fixture")
        assertEquals(local, client.exchange(settings) { local })
        assertEquals(listOf("GET", "LOCK", "GET", "PUT", "UNLOCK"), methods)
    }

    @Test fun nutstoreAcceptsTheLockTokenFromTheDavResponseBody() {
        val value = Journal().set(firstDay, 68.4, now = 8)
        val token = "<urn:uuid:lock-body>"
        val methods = mutableListOf<String>()
        val lockBody = """
            <D:prop xmlns:D="DAV:">
                <D:lockdiscovery><D:activelock>
                    <D:lockscope><D:exclusive/></D:lockscope>
                    <D:locktype><D:write/></D:locktype>
                    <D:locktoken><D:href>urn:uuid:lock-body</D:href></D:locktoken>
                    <D:lockroot><D:href>/dav/weight-journal.json</D:href></D:lockroot>
                </D:activelock></D:lockdiscovery>
            </D:prop>
        """.trimIndent()
        val client = scriptedClient { request ->
            methods += request.method
            when (request.method) {
                "GET" -> reply(request, 200, Journal().toJson())
                "LOCK" -> reply(request, 200, lockBody)
                "PUT" -> {
                    assertEquals("($token)", request.header("If"))
                    reply(request, 204)
                }
                "UNLOCK" -> {
                    assertEquals(token, request.header("Lock-Token"))
                    reply(request, 204)
                }
                else -> error("Unexpected request")
            }
        }
        val settings = DavSettings.validate("https://dav.jianguoyun.com/dav/", "user", "fixture")
        assertEquals(value, client.exchange(settings) { value })
        assertEquals(listOf("GET", "LOCK", "GET", "PUT", "UNLOCK"), methods)
    }

    @Test fun nutstoreUsesItsUnquotedEtagAndRetriesIfTheRemoteFileChanges() {
        val value = Journal().set(firstDay, 68.4, now = 8)
        val changedRemote = Journal().set(secondDay, 69.0, now = 10)
        val methods = mutableListOf<String>()
        val client = scriptedClient { request ->
            methods += request.method
            when (methods.size) {
                1 -> reply(request, 200, Journal().toJson(), "ETag" to "rev-1")
                2 -> {
                    assertEquals("rev-1", request.header("If-Match"))
                    reply(request, 412)
                }
                3 -> reply(request, 200, changedRemote.toJson(), "ETag" to "rev-2")
                4 -> {
                    assertEquals("rev-2", request.header("If-Match"))
                    val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertEquals(changedRemote.merge(value), Journal.parse(body))
                    reply(request, 204)
                }
                else -> error("Unexpected request")
            }
        }
        val settings = DavSettings.validate("https://dav.jianguoyun.com/dav/", "user", "fixture")
        assertEquals(changedRemote.merge(value), client.exchange(settings) { value })
        assertEquals(listOf("GET", "PUT", "GET", "PUT"), methods)
    }

    @Test fun nutstoreRechecksTheDiscoveredVersionBeforeWriting() {
        val local = Journal().set(firstDay, 68.4, now = 8)
        val newer = Journal().set(secondDay, 69.0, now = 10)
        val requests = mutableListOf<String>()
        val properties = """
            <D:multistatus xmlns:D="DAV:">
                <D:response>
                    <D:href>/dav/other.json</D:href>
                    <D:propstat><D:prop><D:getetag>unrelated</D:getetag></D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                </D:response>
                <D:response>
                    <D:href>/dav/weight-journal.json</D:href>
                    <D:propstat><D:prop><D:getetag>rev-1</D:getetag></D:prop>
                        <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                    <D:propstat><D:prop><D:getetag>unavailable</D:getetag></D:prop>
                        <D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>
                </D:response>
            </D:multistatus>
        """.trimIndent()
        val client = scriptedClient { request ->
            requests += request.method
            when (requests.size) {
                1, 3 -> reply(request, 200, Journal().toJson())
                2 -> reply(request, 200)
                4 -> reply(request, 405)
                5 -> {
                    assertEquals("0", request.header("Depth"))
                    reply(request, 207, properties)
                }
                6 -> {
                    assertEquals("rev-1", request.header("If-Match"))
                    reply(request, 200, Journal().toJson())
                }
                7 -> reply(request, 200, "", "ETag" to "rev-2")
                8 -> reply(request, 200, newer.toJson(), "ETag" to "rev-2")
                9 -> {
                    assertEquals("rev-2", request.header("If-Match"))
                    val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertEquals(newer.merge(local), Journal.parse(body))
                    reply(request, 204)
                }
                else -> error("Unexpected request")
            }
        }
        val settings = DavSettings.validate("https://dav.jianguoyun.com/dav/", "user", "fixture")
        assertEquals(newer.merge(local), client.exchange(settings) { local })
        assertEquals(listOf("GET", "LOCK", "GET", "HEAD", "PROPFIND", "GET", "HEAD", "GET", "PUT"), requests)
    }

    @Test fun nutstoreNeverOverwritesWithoutALockOrAnEtag() {
        val local = Journal().set(firstDay, 68.4, now = 8)
        val methods = mutableListOf<String>()
        val client = scriptedClient { request ->
            methods += request.method
            val body = if (request.method == "GET") Journal().toJson() else ""
            reply(request, 200, body)
        }
        val settings = DavSettings.validate("https://dav.jianguoyun.com/dav/", "user", "fixture")
        assertThrows(IOException::class.java) { client.exchange(settings) { local } }
        assertEquals(listOf("GET", "LOCK", "GET", "HEAD", "PROPFIND"), methods)
    }

    @Test fun malformedRemoteAndWeakEtagsNeverCauseOverwrite() {
        val local = Journal().set(firstDay, 72.0, now = 9)
        val settings = DavSettings.validate("https://dav.example.test/weights.json", "user", "fixture")
        for (body in listOf("{}", Journal().toJson())) {
            var wrote = false
            val client = scriptedClient { request ->
                wrote = wrote || request.method == "PUT"
                reply(request, 200, body, "ETag" to "W/\"weak\"")
            }
            assertThrows(IOException::class.java) { client.exchange(settings) { local } }
            assertFalse(wrote)
        }
    }

    private fun scriptedClient(handler: (Request) -> Response) =
        WebDavClient(OkHttpClient.Builder().addInterceptor { chain -> handler(chain.request()) }.build())

    private fun reply(request: Request, code: Int, body: String = "", vararg headers: Pair<String, String>): Response =
        Response.Builder().request(request).message("fixture").protocol(Protocol.HTTP_1_1)
            .code(code).body(body.toResponseBody()).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
}

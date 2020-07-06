package io.meltec.amadeus

import io.ktor.http.*
import kotlin.test.*
import io.ktor.server.testing.*
import org.sqlite.SQLiteConfig

class ApplicationTest {
    @Test
    fun registerDisplayName() {
        // TODO: Move this to a generic setup block.
        val database = Database.sqlite("amadeus-test.db", SQLiteConfig().apply {
            enableRecursiveTriggers(true)
            enforceForeignKeys(true)
        })
        val amadeus = Amadeus(database, YoutubeDownloader(database))

        withTestApplication({ amadeus.configure(this, true) }) {
            val expected = "amazingName"
            cookiesSession {
                handleRequest(HttpMethod.Post, "/register") {
                    addHeader(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody(listOf("displayName" to expected).formUrlEncode())
                }.run {
                    assertEquals(HttpStatusCode.Found, response.status())
                    assertEquals("/room", response.headers["Location"])
                    assertEquals(null, response.content)
                }
                handleRequest(HttpMethod.Get, "/room").run {
                    assertEquals(HttpStatusCode.OK, response.status())
                    assertTrue { response.content?.contains(expected) ?: false }
                }
            }
        }
    }
}

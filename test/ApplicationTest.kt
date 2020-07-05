package io.meltec.amadeus

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.request.*
import io.ktor.routing.*
import io.ktor.http.*
import io.ktor.html.*
import kotlinx.html.*
import kotlinx.css.*
import io.ktor.content.*
import io.ktor.http.content.*
import io.ktor.sessions.*
import io.ktor.features.*
import org.slf4j.event.*
import io.ktor.util.date.*
import io.ktor.websocket.*
import io.ktor.http.cio.websocket.*
import java.time.*
import io.ktor.auth.*
import com.fasterxml.jackson.databind.*
import io.ktor.jackson.*
import kotlin.test.*
import io.ktor.server.testing.*
import org.sqlite.SQLiteConfig

class ApplicationTest {
    @Test
    fun testRoot() {
        // TODO: Move this to a generic setup block.
        val database = Database.sqlite("amadeus-test.db", SQLiteConfig().apply {
            enableRecursiveTriggers(true)
            enforceForeignKeys(true)
        })
        val amadeus = Amadeus(database)

        withTestApplication({ amadeus.configure(this, true) }) {
            handleRequest(HttpMethod.Get, "/").apply {
                assertEquals(HttpStatusCode.OK, response.status())
                assertEquals("HELLO WORLD!", response.content)
            }
        }
    }
}

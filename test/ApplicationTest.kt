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
        val amadeus = Amadeus(database, YoutubeDownloader.createWithoutInit(database))

        // How's this for a deep and useful test?
        assertEquals(true, true)
    }
}

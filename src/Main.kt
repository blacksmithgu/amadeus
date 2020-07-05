package io.meltec.amadeus

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.sqlite.SQLiteConfig

fun main(args: Array<String>) {
    val database = Database.sqlite("amadeus.db", SQLiteConfig().apply {
        enableRecursiveTriggers(true)
        enforceForeignKeys(true)
        setApplicationId(0xA3ADE05) // Vaguely 'Amadeus'
    })
    val amadeus = Amadeus(database)

    embeddedServer(Netty, port = 8080) {
        amadeus.configure(this, true)
    }.start(wait = true)
}

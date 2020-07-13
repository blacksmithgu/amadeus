package io.meltec.amadeus

import io.ktor.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import java.util.regex.Pattern

/** Configures Amadeus. */
fun Application.server() {
    val log = LoggerFactory.getLogger("main")

    // TODO: Some basic build automation around the application ID and user version - useful for automatic upgrades!
    val database = Database.sqlite(
        "amadeus.db",
        SQLiteConfig().apply {
            enableRecursiveTriggers(true)
            enforceForeignKeys(true)
        }
    )

    // TODO: Implement database migrations/updates.
    if (!database.initialized()) {
        log.warn("The database has not been initialized, creating a new database at `amadeus.db`")
        val schema = Amadeus::class.java.getResource("/schema.sql").readText()

        // Sqlite doesn't support multiple commands in one statement, so split by semicolon and execute them one
        // at a time. Wonderful. This is pretty hacky.
        val commands = schema.split(Pattern.compile(";\r?\n"))
        for (command in commands) {
            if (command.trim().isEmpty()) continue
            database.withJooq { it.execute(command.trim() + ";") }
        }
        log.warn("Initialized database `amadeus.db` with {} total statements", commands.size)
    } else {
        log.info("Sqlite database `amadeus.db` loaded (version ${database.version()})")
    }

    Amadeus(database, YoutubeDownloader.create(database)).configure(this, true)
}

/** Starts Amadeus. */
fun main() {
    embeddedServer(Netty, watchPaths = listOf("amadeus/build"), port = 8080, module = Application::server)
        .start(wait = true)
}

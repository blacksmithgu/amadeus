package io.meltec.amadeus

import org.sqlite.SQLiteConfig
import java.sql.Connection

// TODO: When eventually swapping to using an external database like Postgres, use the HikariCP thread pool as a
// connection source.
/**
 * A simple wrapper for a database (currently Sqlite) which provides ways to easily obtain a Connection, a jooq DSL
 * context, or directly query objects directly.
 */
class Database {
    private constructor(filename: String, config: SQLiteConfig) {
        this.config = config
        this.filename = filename
    }

    /**
     * Create a new connection to this database (using the internal connection configuration)
     * It is recommended to use [withConnection]
     */
    fun connection(): Connection {

    }

    /**
     * Run the given lambda with a connection, potentially reusing connections from a connection pool.
     * Do not retain a reference to this connection.
     */
    fun<T> withConnection(func: Connection -> T): T {
        return func(connection())
    }

    companion object {
        /** Attempt to create a database from the given sqlite configuration. */
        @JvmStatic
        fun sqlite(filename: String, config: SQLiteConfig): Database {
            return Database(filename, config)
        }
    }
}


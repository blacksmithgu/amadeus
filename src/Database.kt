package io.meltec.amadeus

import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.sqlite.SQLiteConfig
import java.sql.Connection

// TODO: When eventually swapping to using an external database like Postgres, use the HikariCP thread pool as a
// connection source.
/**
 * A simple wrapper for a database (currently Sqlite) which provides ways to easily obtain a Connection, a jooq DSL
 * context, or directly query objects directly.
 */
class Database {
    /** The sqlite configuration to use for generating connections. */
    private val config: SQLiteConfig
    /** The filename of the sqlite database. */
    private val filename: String
    /** The full JDBC path to the database. Computed field from the filename. */
    private val jdbcString: String

    private constructor(filename: String, config: SQLiteConfig) {
        this.filename = filename
        this.config = config
        this.jdbcString = "jdbc:sqlite:$filename"
    }

    /** Determines if this database has been initialized by querying if any tables exist. */
    fun initialized(): Boolean = withJooq { it.meta().tables.size > 0 }

    /** Obtains the current database version. */
    fun version(): Int = withJooq { it.fetchOne("PRAGMA user_version;").get(0) as Int }

    /**
     * Create a new connection to this database (using the internal connection configuration)
     * It is recommended to use [withConnection]
     */
    fun connection(): Connection = config.createConnection(this.jdbcString)

    /**
     * Run the given lambda with a connection, potentially reusing connections from a connection pool.
     * Do not retain a reference to this connection.
     */
    fun<T> withConnection(func: (conn: Connection) -> T): T {
        return func(connection())
    }

    /**
     * Run the given lambda with a jooq DSL context which is already connected to the database. Do not retain a
     * reference to this context.
     */
    fun<T> withJooq(func: (context: DSLContext) -> T): T {
        return func(DSL.using(connection(), SQLDialect.SQLITE))
    }

    companion object {
        /** Attempt to create a database from the given sqlite configuration. */
        @JvmStatic
        fun sqlite(filename: String, config: SQLiteConfig): Database {
            return Database(filename, config)
        }
    }
}

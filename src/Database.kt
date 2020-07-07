package io.meltec.amadeus

import org.jooq.DSLContext
import org.jooq.RecordMapper
import org.jooq.SQLDialect
import org.jooq.generated.Tables.*
import org.jooq.generated.tables.records.*
import org.jooq.impl.DSL
import org.sqlite.SQLiteConfig
import java.sql.Connection
import java.time.LocalDateTime

// TODO: When eventually swapping to using an external database like Postgres, use the HikariCP thread pool as a
// connection source.
// TODO: Swap the database to be fully asynchronous using a database call dispatcher :)
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

    // Songs

    /** Select all songs from the database - if you want to filter, make a query for it instead of using this function!. */
    fun allSongs(): List<Song> = withJooq { it.selectFrom(SONGS).fetch(SONG_MAPPER) }

    // Sources

    /** Select all sources from the database - if you want to filter, make a query for it instead of using this function! */
    fun allSources(): List<Source> = withJooq { it.selectFrom(SOURCES).fetch(SOURCE_MAPPER) }

    // Song-Sources

    fun allSongSources(): List<SongSource> = withJooq { it.selectFrom(SONGS_SOURCES).fetch(SONG_SOURCE_MAPPER) }

    // Queued Downloads

    /** Create a new queued download entry in the database. */
    fun newQueuedDownload(url: String, time: LocalDateTime): QueuedYoutubeDownload = withJooq {
        val record = it.insertInto(QUEUED_SONG_DOWNLOADS)
            .set(QUEUED_SONG_DOWNLOADS.URL, url)
            .set(QUEUED_SONG_DOWNLOADS.REQUEST_TIME, time)
            .returning()
            .fetchOne()

        QUEUED_RECORD_MAPPER.map(record)
    }

    /** Delete a queued download with the given ID. */
    fun deleteQueuedDownload(id: Int) = withJooq {
        it.deleteFrom(QUEUED_SONG_DOWNLOADS)
            .where(QUEUED_SONG_DOWNLOADS.ID.eq(id))
            .execute()
    }

    /** Select all queued downloads. */
    fun allQueuedDownloads(): List<QueuedYoutubeDownload> = withJooq {
        it.selectFrom(QUEUED_SONG_DOWNLOADS).fetch(QUEUED_RECORD_MAPPER)
    }

    // Completed Downloads

    /**
     * Create a new successful completed download in the database.
     */
    fun newSuccessfulCompletedDownload(url: String,
                                       requestTime: LocalDateTime,
                                       completeTime: LocalDateTime,
                                       meta: YoutubeMetadata): CompletedYoutubeDownload = withJooq {
        val record = it.insertInto(COMPLETED_SONG_DOWNLOADS)
            .set(COMPLETED_SONG_DOWNLOADS.URL, url)
            .set(COMPLETED_SONG_DOWNLOADS.REQUEST_TIME, requestTime)
            .set(COMPLETED_SONG_DOWNLOADS.COMPLETED_TIME, completeTime)
            .set(COMPLETED_SONG_DOWNLOADS.TITLE, meta.title)
            .set(COMPLETED_SONG_DOWNLOADS.ARTIST, meta.artist)
            .set(COMPLETED_SONG_DOWNLOADS.ALBUM, meta.album)
            .set(COMPLETED_SONG_DOWNLOADS.THUMBNAIL_URL, meta.thumbnailUrl)
            .set(COMPLETED_SONG_DOWNLOADS.DURATION_SECONDS, meta.lengthSeconds)
            .set(COMPLETED_SONG_DOWNLOADS.FILENAME, meta.filename)
            .returning()
            .fetchOne()

        COMPLETED_RECORD_MAPPER.map(record)
    }

    /**
     * Create a new failed completed download in the database.
     */
    fun newFailedCompletedDownload(url: String,
                                   requestTime: LocalDateTime,
                                   completeTime: LocalDateTime,
                                   error: String): CompletedYoutubeDownload = withJooq {
        val record = it.insertInto(COMPLETED_SONG_DOWNLOADS)
            .set(COMPLETED_SONG_DOWNLOADS.URL, url)
            .set(COMPLETED_SONG_DOWNLOADS.REQUEST_TIME, requestTime)
            .set(COMPLETED_SONG_DOWNLOADS.COMPLETED_TIME, completeTime)
            .set(COMPLETED_SONG_DOWNLOADS.ERROR, error)
            .returning()
            .fetchOne()

        COMPLETED_RECORD_MAPPER.map(record)
    }

    /** Select all completed youtube downloads. */
    fun allCompletedDownloads(): List<CompletedYoutubeDownload> = withJooq {
        it.selectFrom(COMPLETED_SONG_DOWNLOADS).fetch(COMPLETED_RECORD_MAPPER)
    }

    companion object {
        /** Attempt to create a database from the given sqlite configuration. */
        @JvmStatic
        fun sqlite(filename: String, config: SQLiteConfig): Database {
            return Database(filename, config)
        }
    }
}

/** Maps SongRecords to Song objects. */
val SONG_MAPPER: RecordMapper<SongsRecord, Song> = RecordMapper { record: SongsRecord ->
    Song(record.id, record.name, record.uploadTime, SongOrigin.fromId(record.uploadOrigin), record.uploadDataSource, record.filename)
}

/** Maps SourceRecords to Source objects. */
val SOURCE_MAPPER: RecordMapper<SourcesRecord, Source> = RecordMapper { record: SourcesRecord ->
    Source(record.id, record.name, record.type, record.referenceLink, record.createdTime)
}

/** Maps SongSourceRecords to SongSource objects. */
val SONG_SOURCE_MAPPER: RecordMapper<SongsSourcesRecord, SongSource> = RecordMapper {  record: SongsSourcesRecord ->
    SongSource(record.songId, record.sourceId, record.type)
}

/** Maps completed download records in the database to CompletedYoutubeDownload objects. */
val COMPLETED_RECORD_MAPPER: RecordMapper<CompletedSongDownloadsRecord, CompletedYoutubeDownload> = RecordMapper { record ->
    if (record.error == null) {
        CompletedYoutubeDownload.Success(record.id, record.url, record.requestTime, record.completedTime,
            YoutubeMetadata(record.title, record.artist, record.album, record.thumbnailUrl, record.durationSeconds, record.filename))
    } else {
        CompletedYoutubeDownload.Error(record.id, record.url, record.requestTime, record.completedTime, record.error)
    }
}

/** Maps queued downloads in the database to QueuedYoutubeDownload objects. */
val QUEUED_RECORD_MAPPER: RecordMapper<QueuedSongDownloadsRecord, QueuedYoutubeDownload> = RecordMapper { record ->
    QueuedYoutubeDownload(record.id, record.url, record.requestTime)
}

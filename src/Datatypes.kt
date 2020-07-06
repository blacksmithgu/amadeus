/**
 * Contains basic datatype definitions used everywhere in the application. These basic datatypes may have JSON
 * annotations to allow for directly serializing them.
 */
package io.meltec.amadeus

import java.time.LocalDateTime

/** The origin of a given song (direct upload, song, unknown, etc.). */
enum class SongOrigin {
    /** A direct file upload. */
    DIRECT_UPLOAD,
    /** The song originated from a youtube video. */
    YOUTUBE,
    /** We have no idea where this song came from. Wierd. */
    UNKNOWN;

    /** Map a song origin ID/ordinal to the actual song origin. */
    companion object {
        @JvmStatic
        fun fromId(id: Int): SongOrigin = when (id) {
            0 -> DIRECT_UPLOAD
            1 -> YOUTUBE
            else -> UNKNOWN
        }
    }
}

/** A song in the system; associated with an arbitrary number of sources. */
data class Song(val id: Int, val name: String, val uploadTime: LocalDateTime, val origin: SongOrigin, val dataSource: String, val filename: String)

/** A source of songs; can also be seen as a Tag. */
data class Source(val id: Int, val name: String, val type: String, val referenceLink: String?, val createdTime: LocalDateTime)

/** A link between a song and a source; annotated with the song and source IDs, as well as the type of the connection. */
data class SongSource(val songId: Int, val sourceId: Int, val type: String)

/** A queued youtube download which will be processed. */
data class QueuedYoutubeDownload(val id: Int, val url: String, val requestTime: LocalDateTime)

/** A completed youtube download (which can either be an error, or a successful download with metadata). */
sealed class CompletedYoutubeDownload(val id: Int, val url: String, val requestTime: LocalDateTime, val completedTime: LocalDateTime) {
    /** The download was successful and returned the given youtube metadata. */
    class Success(id: Int, url: String, requestTime: LocalDateTime, completedTime: LocalDateTime, val meta: YoutubeMetadata)
        : CompletedYoutubeDownload(id, url, requestTime, completedTime)

    /** The download errored with the given reason. */
    class Error(id: Int, url: String, requestTime: LocalDateTime, completedTime: LocalDateTime, val reason: String)
        : CompletedYoutubeDownload(id, url, requestTime, completedTime)
}


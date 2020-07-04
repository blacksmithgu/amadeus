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
    YOUTUBE;

    /** Map a song origin ID/ordinal to the actual song origin. */
    fun fromId(id: Int): SongOrigin? = when(id) {
        0 -> DIRECT_UPLOAD
        1 -> YOUTUBE
        else -> null
    }
}

/** A song in the system; associated with an arbitrary number of sources. */
data class Song(val id: Int, val uploadTime: LocalDateTime, val origin: SongOrigin, val data_source: String)

/** A source of songs; can also be seen as a Tag. */
data class Source(val id: Int, val name: String, val type: String, val referenceLink: String?, val createdTime: LocalDateTime)

/** A link between a song and a source; annotated with the song and source IDs, as well as the type of the connection. */
data class SongSource(val songId: Int, val sourceId: Int, val type: String)


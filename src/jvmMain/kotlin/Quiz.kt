package io.meltec.amadeus

import java.net.URL

/** A quiz question for a [song] consisting of a [prompt] and [solution].*/
data class Question(
    /** The location of the song to play. */
    val song: URL,
    /** The prompt to display. */
    val prompt: String,
    /** The solution to check answers against. */
    val solution: String
)

/** A song quiz. */
data class Quiz(
    /** The list of questions comprising this quiz. */
    val questions: List<Question>
) {
    companion object {
        /** A hard-coded Dark Souls quiz. */
        @JvmStatic
        fun darkSouls(): Quiz = Quiz(
            listOf(
                Question(
                    Quiz::class.java.getResource("/firelink_shrine_30.mp3"),
                    "What area does this song play in?",
                    "Firelink Shrine"
                ),
                Question(
                    Quiz::class.java.getResource("/gaping_dragon_30.mp3"),
                    "What boss is this the theme of?",
                    "Gaping Dragon"
                ),
                Question(
                    Quiz::class.java.getResource("/ancient_dragon_30.mp3"),
                    "What boss is this the theme of?",
                    "Ancient Dragon"
                ),
                Question(
                    Quiz::class.java.getResource("/iron_golem_30.mp3"),
                    "What boss is this the theme of?",
                    "Iron Golem"
                ),
                Question(
                    Quiz::class.java.getResource("/slave_knight_gael_30.mp3"),
                    "You should know this song.",
                    "Slave Knight Gael"
                )
            )
        )
    }
}

package io.meltec.amadeus

import java.net.URL

data class Question(val song: URL, val prompt: String, val answer: String)

// This is hacky.
data class Quiz(val questions: List<Question>) {
    companion object {
        @JvmStatic
        fun darkSouls(): Quiz = Quiz(listOf(
            Question(Quiz::class.java.getResource("/firelink_shrine_30.mp3"), "What area does this song play in?", "Firelink Shrine"),
            Question(Quiz::class.java.getResource("/gaping_dragon_30.mp3"), "What boss is this the theme of?", "Gaping Dragon"),
            Question(Quiz::class.java.getResource("/ancient_dragon_30.mp3"), "What boss is this the theme of?", "Ancient Dragon"),
            Question(Quiz::class.java.getResource("/iron_golem_30.mp3"), "What boss is this the theme of?", "Iron Golem"),
            Question(Quiz::class.java.getResource("/slave_knight_gael_30.mp3"), "You should know this song.", "Slave Knight Gael")
        ))
    }
}


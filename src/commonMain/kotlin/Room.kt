import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// /////////////////////////////////////////////////////////////////////////////////////
// JSON-serializable room state which is exposed to the rest of the server and clients.
// /////////////////////////////////////////////////////////////////////////////////////
/**
 * The full configuration/rules for a room; this is used to generate the music quiz as well as govern the flow of the game.
 * All times are in seconds.
 *
 * This class is serializable, and is sent over the wire as JSON to clients.
 */
@Serializable
data class RoomConfiguration(
    // TODO: Change from seconds to milliseconds, probably.
    /** The number of seconds to play a song for. */
    val playTime: Int = 20,
    /** The number of seconds allowed for guessing. */
    val guessTime: Int = 0,
    /** The number of seconds given to review correct answers. */
    val reviewTime: Int = 5,
    /** The number of rounds to play. */
    val rounds: Int = 20,
    /** The maximum number of players allowed to join the room. */
    val maxPlayers: Int = 8
)

/** A player in the game; just gives basic name and host information, as well as score. */
@Serializable
data class PlayerInfo(
    /** The id of the player. */
    val id: String,
    /** The name of the player. */
    val name: String,
    /** Whether the player is host or not. */
    val host: Boolean
)

/** A highlight for a player on the scoreboard; provides info on their status. */
@Serializable
enum class PlayerHighlight {
    NOTHING,
    FINISHED_BUFFERING,
    GUESSED,
    CORRECT,
    INCORRECT
}

// TODO: For simplicity, players is duplicated across all room states and made available through a function;
/**
 * A snapshot of the current status of the room - what state/phase it is in (such as 'Lobby' or '4th round in game'),
 * scores, answers, etc. A given room status completely captures the current state of the room (so clients do not need
 * to keep around any previous state other than for diffing/animation purposes).
 */
@Serializable
sealed class RoomStatus {
    /** The list of players in this state. */
    abstract val players: List<PlayerInfo>

    /** Obtains the round in any state (0 in non-applicable states). */
    abstract val round: Int

    /** Obtains the highlight for a given player in this state. */
    fun playerHighlight(playerId: String): PlayerHighlight = when (this) {
        is Lobby, is Loading, is Finished -> PlayerHighlight.NOTHING
        is Buffering -> if (ready.contains(playerId)) PlayerHighlight.FINISHED_BUFFERING else PlayerHighlight.NOTHING
        is Playing -> if (guessed.contains(playerId)) PlayerHighlight.GUESSED else PlayerHighlight.NOTHING
        is Reviewing -> if (correct.contains(playerId)) PlayerHighlight.CORRECT else PlayerHighlight.INCORRECT
    }

    /** Obtains the score for the given player in any state. */
    fun score(playerId: String): Int = when(this) {
        is Lobby -> 0
        is Loading -> 0
        is Buffering -> scores.get(playerId) ?: 0
        is Playing -> scores.get(playerId) ?: 0
        is Reviewing -> scores.get(playerId) ?: 0
        is Finished -> scores.get(playerId) ?: 0
    }

    /** The initial state, during which players can freely leave and join. */
    @Serializable
    @SerialName("LOBBY")
    data class Lobby(override val players: List<PlayerInfo>) : RoomStatus() {
        override val round: Int = 0
    }

    /** The loading state, where players wait for the quiz to start. */
    @Serializable
    @SerialName("LOADING")
    data class Loading(override val players: List<PlayerInfo>) : RoomStatus() {
        override val round: Int = 0
    }

    /** Buffering in a round (waiting for players to catch up). */
    @Serializable
    @SerialName("BUFFERING")
    data class Buffering(override val round: Int, val ready: Set<String>, val scores: Map<String, Int>, override val players: List<PlayerInfo>) : RoomStatus()

    /** Currently playing a round. */
    @Serializable
    @SerialName("PLAYING")
    data class Playing(override val round: Int, val startTime: Long, val prompt: String, val guessed: Set<String>, val scores: Map<String, Int>, override val players: List<PlayerInfo>) : RoomStatus()

    /** Players are reviewing the answers to a played round. */
    @Serializable
    @SerialName("REVIEWING")
    data class Reviewing(override val round: Int, val prompt: String, val solution: String, val guesses: Map<String, String>, val correct: Set<String>, val scores: Map<String, Int>, override val players: List<PlayerInfo>) : RoomStatus()

    /** The game has finished, and the scoreboard is being shown. */
    @Serializable
    @SerialName("FINISHED")
    data class Finished(val scores: Map<String, Int>, override val players: List<PlayerInfo>) : RoomStatus() {
        override val round: Int = 0
    }
}

// ///////////////////////////////////////////////////////
// Websocket protocol interchange format (JSON-based). //
// ///////////////////////////////////////////////////////

@Serializable
sealed class ClientCommand {
    @Serializable
    @SerialName("START")
    object Start : ClientCommand()

    @Serializable
    @SerialName("NEXT")
    object Next : ClientCommand()

    @Serializable
    @SerialName("BUFFER_COMPLETE")
    data class BufferComplete(val round: Int) : ClientCommand()

    @Serializable
    @SerialName("GUESS")
    data class Guess(val guess: String, val round: Int) : ClientCommand()
}

/** A command which we are sending from the server to a client (or multiple clients). */
@Serializable
sealed class ServerCommand {
    @Serializable
    @SerialName("ROOM_CONFIG")
    data class RoomConfig(val config: RoomConfiguration) : ServerCommand()

    @Serializable
    @SerialName("ROOM_STATE")
    data class RoomStat(val state: RoomStatus) : ServerCommand()

    @Serializable
    @SerialName("SONG_DATA")
    data class SongData(val round: Int, val sizeBytes: Int) : ServerCommand()
}

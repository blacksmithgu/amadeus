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
    val guessTime: Int = 10,
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

// TODO: For simplicity, players is duplicated across all room states and made available through a function;
/**
 * A snapshot of the current status of the room - what state/phase it is in (such as 'Lobby' or '4th round in game'),
 * how many players are currently
 */
@Serializable
sealed class RoomStatus {
    abstract val players: List<PlayerInfo>

    /** The initial state, during which players can freely leave and join. */
    @Serializable
    @SerialName("LOBBY")
    data class Lobby(override val players: List<PlayerInfo>) : RoomStatus()

    /** The loading state, where players wait for the quiz to start. */
    @Serializable
    @SerialName("LOADING")
    data class Loading(override val players: List<PlayerInfo>) : RoomStatus()

    /** Buffering in a round (waiting for players to catch up). */
    @Serializable
    @SerialName("BUFFERING")
    data class Buffering(val round: Int, val ready: Set<String>, val scores: Map<String, Int>, override val players: List<PlayerInfo>) : RoomStatus()

    /** Currently playing a round. */
    @Serializable
    @SerialName("PLAYING")
    data class Playing(val round: Int, val startTime: Long, val prompt: String, val guessed: Set<String>, val scores: Map<String, Int>, override val players: List<PlayerInfo>) : RoomStatus()

    /** Players are reviewing the answers to a played round. */
    @Serializable
    @SerialName("REVIEWING")
    data class Reviewing(val round: Int, val prompt: String, val solution: String, val guesses: Map<String, String>, val scores: Map<String, Int>, override val players: List<PlayerInfo>) : RoomStatus()

    /** The game has finished, and the scoreboard is being shown. */
    @Serializable
    @SerialName("FINISHED")
    data class Finished(val scores: Map<String, Int>, override val players: List<PlayerInfo>) : RoomStatus()
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
    @SerialName("PLAYER_LEFT")
    data class PlayerLeft(val id: String) : ServerCommand()

    @Serializable
    @SerialName("PLAYER_JOINED")
    data class PlayerJoined(val player: PlayerInfo) : ServerCommand()

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

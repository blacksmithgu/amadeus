package io.meltec.amadeus

import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.close
import io.ktor.http.cio.websocket.send
import io.ktor.websocket.WebSocketServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

// TODO: This is large, implement a more sophisticated buffering technique.
/** The size of audio packets being sent; this should probably eventually be configurable. */
private val AUDIO_PACKET_SIZE: Int = 4 * 1024 * 1024 // 4MB

private val log: Logger = LoggerFactory.getLogger("room")

/**
 * The full configuration/rules for a room; this is used to generate the music quiz as well as govern the flow of the game.
 *
 * This class is serializable, and is sent over the wire as JSON to clients.
 */
@Serializable
data class RoomConfiguration(val playTime: Int = 20, val guessTime: Int = 10, val rounds: Int = 20, val maxPlayers: Int = 8)

// TODO: Consider thread confinement to make concurrency trivial here (no locking/concurrent data structures required).
/**
 * Abstraction for a game room; contains websocket information about the players currently in the room,
 * and the state of the game being played in the room.
 *
 * The full flow of a game room is as follows:
 * 1. New rooms start in the 'Lobby' state, during which players can join freely (up to a player limit). The first
 * player in a room (i.e, the one who creates the room) is automatically designated the host, and can also change
 * lobby rules, kick people, or initiate the game.
 * 2. When the host gives the 'Start' command, the server starts generating the full music quiz, from selecting which
 * songs and sections, to computing the snippets of audio which will be used. Clients see a loading screen while this
 * occurs; upon completion, the server acknowledges with a 'Ready' command and the game starts.
 * 3. The server immediately begins sending the first audio track to clients via binary packets over the web socket;
 * clients acknowledge when they have completely read all audio data, and the timing for the first round begins via
 * a 'Round Start' command from the server. Clients which fail to buffer the audio data within a specified timeout are
 * kicked and the round continues unabated (for now; we'll add better error handling later).
 * 4. Clients have a certain amount of time to listen to the audio track, followed by another amount of time (configurable)
 * to enter an answer and submit via a 'Submit Answer' command. The server automatically ends the round when the time
 * has elapsed, and shares all client answers with other clients as well as which answers were correct.
 *  - During this time, the server will also immediately begin sending the next section of audio so that there is no
 *    buffering time for the next round.
 * 5. Steps 3 & 4 are repeated (with a much smaller buffering time due to pre-buffering).
 */
class Room(val id: String, val server: Amadeus) {
    /** Map of players versus their current live websocket sessions. */
    val players: ConcurrentHashMap<PlayerSession, WebSocketServerSession> = ConcurrentHashMap()

    /** The scope which all the coroutines for this room run in; allows for all of them to easily be canceled. */
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)

    /** The time this room was first created. */
    val createdTime: LocalDateTime = LocalDateTime.now()

    /**
     * The current room configuration. RoomConfiguration is immutable, but it may change in the lifetime of the room
     * (particularly during the lobby phase), so it is volatile. We are okay with occasional stale reads of room
     * configuration data as long as it is up to date when the game actually starts.
     */
    @Volatile
    var config: RoomConfiguration = RoomConfiguration()

    /** Local json object used in data serialization/deserialization. */
    private val json: Json = Json(JsonConfiguration.Stable)

    /** Handle when a player attempts to join the room. The socket will close if this function returns. */
    suspend fun handlePlayer(session: PlayerSession, socket: WebSocketServerSession) {
        // Reject players if they are not already in the game and we are full.
        if (!players.containsKey(session) && players.size >= config.maxPlayers) {
            socket.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "This room only allows ${config.maxPlayers} players"))
            log.info("Rejected player '${session.id}' (${server.playerName(session) ?: "unknown"}) from room '$id' due to insufficient space in the room")
            return
        }

        // Update the map; if there was an existing session, then we are rejoining.
        val existingSession = players.put(session, socket)?.let {
            it.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Only one websocket open per user session"))
            true
        } ?: false

        try {
            if (!existingSession) broadcast(ServerCommand.PlayerJoined(server.playerName(session) ?: session.id), exclude = session)

            // Update the player session in the map; we can assume we need to give the player initialization information.
            socket.send(ServerCommand.RoomConfig(config))

            // Immediately send over a song for the API to play. Whee.
            val data = Room::class.java.getResource("/firelink_shrine_30.mp3").readBytes()
            socket.send(ServerCommand.SongData(data.size))
            socket.send(data)

            // Respond to messages "forever", until the socket is closed.
            for (message in socket.incoming) {
                when(message) {
                    is Frame.Text -> socket.send("nice message man")
                    else -> socket.send("what are you saying man")
                }
            }
        } catch (ex: Exception) {
            log.error("Error during operation of websocket for user '${session.id}':", ex)
            // The socket died, or some other sadness occurred - notify everyone that this socket is dead.
            if (players.remove(session, socket)) {
                broadcast(ServerCommand.PlayerLeft(server.playerName(session) ?: session.id))
            }
        }
    }

    /** The number of players currently in this room. */
    fun numPlayers(): Int = players.size

    /** Broadcast a message to all connected clients. */
    private suspend fun broadcast(command: ServerCommand, exclude: PlayerSession? = null) {
        for ((player, socket) in players) {
            if (player == exclude) continue
            socket.send(command)
        }
    }

    /** Local extension function for sending a server command object directly, instead of raw text. */
    private suspend fun WebSocketServerSession.send(command: ServerCommand) {
        this.send(command.asJson(json))
    }
}

/**
 * The possible command types which we can recieve from the client.
 */
enum class ServerCommands {
    PLAYER_JOINED,
    PLAYER_LEFT,
    ROOM_CONFIG,
    SONG_DATA
}

enum class ClientCommands {
    START,
}

/** A command which we have recieved from the client. */
@Serializable
data class ClientCommand(val type: ClientCommands)

// TODO: Find cleaner way to do this, if possible. Maybe a custom serializer.
/** A command which we are sending from the server to a client (or multiple clients). */
@Serializable
sealed class ServerCommand(val type: ServerCommands) {
    @Serializable
    data class PlayerLeft(val playerName: String) : ServerCommand(ServerCommands.PLAYER_LEFT) {
        override fun asJson(json: Json) = json.stringify(serializer(), this)
    }

    @Serializable
    data class PlayerJoined(val playerName: String): ServerCommand(ServerCommands.PLAYER_JOINED) {
        override fun asJson(json: Json) = json.stringify(serializer(), this)
    }

    @Serializable
    data class RoomConfig(val config: RoomConfiguration) : ServerCommand(ServerCommands.ROOM_CONFIG) {
        override fun asJson(json: Json) = json.stringify(serializer(), this)
    }

    @Serializable
    data class SongData(val sizeBytes: Int) : ServerCommand(ServerCommands.SONG_DATA) {
        override fun asJson(json: Json) = json.stringify(serializer(), this)
    }

    abstract fun asJson(json: Json): String
}

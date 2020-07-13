package io.meltec.amadeus

import io.ktor.http.cio.websocket.*
import io.ktor.websocket.WebSocketServerSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ActorScope
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.ticker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

private val log: Logger = LoggerFactory.getLogger("room")

/**
 * Abstraction for a game room; controlled by a central room actor, which has exclusive access to the current room state.
 * This actor occasionally publishes current room data via several volatile, immutable readonly fields (see [config] and
 * [status]).
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
@ObsoleteCoroutinesApi
class Room(val id: String, val server: Amadeus) {
    /** The time this room was first created. */
    val createdTime: LocalDateTime = LocalDateTime.now()

    /**
     * The current room configuration. In order to make it easy for other parts of the server to quickly check room
     * status without interacting with the room controller, this is a public, readonly volatile field.
     */
    @Volatile
    var config: RoomConfiguration = RoomConfiguration(rounds = 5)
        private set

    /**
     * The current room status as of the last status update. This is public in order to make it easy for other parts
     * of the server to quickly check room status without interacting with the room controller.
     *
     * This status may be several seconds out of date - it is updated occasionally by the room controller.
     */
    @Volatile
    var status: RoomStatus = RoomStatus()
        private set

    /** The scope which all the coroutines for this room run in; allows for all of them to easily be canceled. */
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)

    /**
     * A lazily-started actor which controls all room state. This is the only coroutine which directly
     * operates on private room state (like players).
     */
    val controller = scope.actor<RoomMessage> { runRoom() }

    /** Handle an incoming player connection with the given session and web socket. */
    suspend fun handleConnection(session: PlayerSession, socket: WebSocketServerSession) {
        // Just notify the controller that there is an incoming connection.
        val resultDefer = CompletableDeferred<CloseReason?>()
        controller.send(RoomMessage.IncomingConnection(session, socket, resultDefer))

        // Wait for the controller to get back to us with a response; if it is non-null, close the socket.
        resultDefer.await()?.let {
            socket.close(it)
            return
        }

        // Otherwise, this web socket just endlessly waits for messages, attempts to decode them/interpret them,
        // and sends them to the controller.
        try {
            outer@for (rawFrame in socket.incoming) {
                // The client should only ever send us JSON textual frames.
                val frame = (rawFrame as? Frame.Text) ?: throw IllegalStateException("Recieved a binary websocket frame from a client")
                val parsed = try {
                    json.parse(ClientCommand.serializer(), frame.readText())
                } catch (ex: Exception) {
                    log.debug("Received malformed JSON or an invalid command from the client: {}", frame.readText(), ex)
                    continue
                }

                val message = when (parsed) {
                    is ClientCommand.Start -> RoomMessage.Start
                    is ClientCommand.Next -> RoomMessage.NextRound
                    is ClientCommand.BufferComplete -> RoomMessage.BufferComplete(session, parsed.round)
                    is ClientCommand.Guess -> RoomMessage.Guess(session, parsed.guess, parsed.round)
                    else -> continue@outer
                }

                controller.send(message)
            }
        } catch (ex: Exception) {
            // On any error which causes this socket to close, let the server know.
            controller.send(RoomMessage.ClosedConnection(session, socket))

            log.error("Error during operation of websocket for user '${session.id}':", ex)
        }

        // Is it possible to reach here without having sent the closed connection message? Hmm.
    }

    // Private state of the room which is only visible to the controller.

    /** Local json object used in data serialization/deserialization. */
    private val json: Json = Json(JsonConfiguration.Stable)

    /** Map of players versus their current live websocket sessions. */
    private val connectedPlayers: HashMap<PlayerSession, WebSocketServerSession> = HashMap()

    /** Main actor controller for this room; processes messages to keep the room moving along. */
    private suspend fun ActorScope<RoomMessage>.runRoom() {
        // This is a little bit of black magic, but we can implement the entirety of the room logic using a single
        // procedural function with the magic of actors and background coroutines.

        // LOBBY
        // We start in the lobby state, where we are just waiting for the 'Start' command.
        lobby@for (message in this.channel) {
            genericMessageHandler(message, setOf(), newJoinsAllowed = true)

            when (message) {
                is RoomMessage.Start -> break@lobby
                is RoomMessage.IncomingConnection -> {
                    // TODO: Cleanup this as a generic 'sendState' method.
                    try {
                        message.socket.send(ServerCommand.RoomStat(RoomStatus(
                            state = RoomState.LOBBY,
                            round = null,
                            players = connectedPlayers.keys.map {
                                PlayerStatus(it.id, server.playerName(it) ?: it.id, true)
                            })
                        ))
                    } catch(ex: Exception) { log.error("Failed to send room status", ex) }
                }
            }
        }

        // The final set of players we will play this game with.
        val players = HashSet(connectedPlayers.keys)

        // Asychronously generate the quiz.
        var quiz: Quiz = Quiz(questions = listOf())
        scope.launch {
            controller.send(RoomMessage.LoadingComplete(Quiz.darkSouls()))
        }

        // Notify all players of the new loading state.
        broadcast(ServerCommand.RoomStat(RoomStatus(
            state = RoomState.LOADING,
            round = null,
            players = connectedPlayers.keys.map {
                PlayerStatus(it.id, server.playerName(it) ?: it.id, true)
            })
        ))

        // We've started the game, queue up the background coroutine which loads the actual game and wait for it.
        loading@for (message in this.channel) {
            genericMessageHandler(message, players)

            when (message) {
                is RoomMessage.LoadingComplete -> {
                    quiz = message.quiz
                    break@loading
                }
                is RoomMessage.IncomingConnection -> {
                    // TODO: Cleanup this as a generic 'sendState' method.
                    try {
                        message.socket.send(ServerCommand.RoomStat(RoomStatus(
                            state = RoomState.LOADING,
                            round = null,
                            players = connectedPlayers.keys.map {
                                PlayerStatus(it.id, server.playerName(it) ?: it.id, true)
                            })
                        ))
                    } catch(ex: Exception) { log.error("Failed to send room status", ex) }
                }
            }
        }

        val scores = HashMap<String, Int>()

        // Now we have a quiz, and we can move into actually playing the game.
        for (round in quiz.questions.indices) {
            val question = quiz.questions[round]
            val songData = question.song.readBytes()

            // Notify all players of the new buffering state.
            broadcast(ServerCommand.RoomStat(RoomStatus(
                state = RoomState.INGAME_BUFFERING,
                round = RoundStatus(round, -1, "", setOf(), mapOf()),
                players = connectedPlayers.keys.map {
                    PlayerStatus(it.id, server.playerName(it) ?: it.id, true)
                })
            ))

            // Send song information to everyone.
            for ((player, socket) in connectedPlayers) {
                scope.launch {
                    try {
                        socket.send(ServerCommand.SongData(round, songData.size))
                        socket.send(songData)
                    } catch (ex: Exception) {
                        socket.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Failed to send song for round $round"))
                    }
                }
            }

            val readyPlayers = HashSet<String>()

            // And wait until we get the buffered data back.
            buffering@ for (message in this.channel) {
                genericMessageHandler(message, players)

                when (message) {
                    is RoomMessage.BufferComplete -> {
                        if (message.round != round) continue@buffering

                        readyPlayers.add(message.session.id)

                        val allPlayersReady = connectedPlayers.keys.all { readyPlayers.contains(it.id) }
                        if (allPlayersReady) break@buffering
                    }
                    is RoomMessage.IncomingConnection -> {
                        // TODO: Cleanup this as a generic 'sendState' method.
                        try {
                            message.socket.send(ServerCommand.RoomStat(RoomStatus(
                                state = RoomState.INGAME_BUFFERING,
                                round = RoundStatus(round, -1, "", setOf(), mapOf()),
                                players = connectedPlayers.keys.map {
                                    PlayerStatus(it.id, server.playerName(it) ?: it.id, true)
                                })
                            ))
                        } catch(ex: Exception) { log.error("Failed to send room status", ex) }
                    }
                }
            }

            // Alright, well everyone is buffered now, so let's actually play the round.
            val guesses = HashMap<String, String>()
            val roundStart = System.currentTimeMillis()

            // And notify players that we're actually starting the round...
            broadcast(ServerCommand.RoomStat(RoomStatus(
                state = RoomState.INGAME,
                round = RoundStatus(round, roundStart, question.prompt, guesses.keys, scores),
                players = connectedPlayers.keys.map {
                    PlayerStatus(it.id, server.playerName(it) ?: it.id, true)
                })
            ))

            // Countdown for when the round ends.
            val roundTimeMs = (config.guessTime + config.playTime) * 1000L
            val ticker = ticker(roundTimeMs, roundTimeMs, scope.coroutineContext)
            scope.launch {
                ticker.receive()
                controller.send(RoomMessage.RoundTimeout(round))
            }

            game@for (message in this.channel) {
                genericMessageHandler(message, players)

                when (message) {
                    is RoomMessage.NextRound -> break@game
                    is RoomMessage.Guess -> if (message.round == round) guesses.put(message.session.id, message.guess)
                    is RoomMessage.RoundTimeout -> {
                        if (message.round != round) continue@game
                        break@game
                    }
                    is RoomMessage.IncomingConnection -> {
                        // TODO: Cleanup this as a generic 'sendState' method.
                        try {
                            message.socket.send(ServerCommand.RoomStat(RoomStatus(
                                state = RoomState.INGAME,
                                round = RoundStatus(round, roundStart, question.prompt, guesses.keys, scores),
                                players = connectedPlayers.keys.map {
                                    PlayerStatus(it.id, server.playerName(it) ?: it.id, true)
                                })
                            ))
                        } catch(ex: Exception) { log.error("Failed to send room status", ex) }
                    }
                }
            }

            // Go through guesses and give points to any correct guesses.
            for ((player, guess) in guesses) {
                if (isAnswerCloseEnough(question.answer, guess)) scores.compute(player) { _, score -> (score ?: 0) + 1 }
            }

            // Cancel the ticker, no point in it spamming us.
            ticker.cancel()
        }

        // Notify players that we are in the final state.
        broadcast(ServerCommand.RoomStat(RoomStatus(
            state = RoomState.FINISHED,
            round = null,
            players = connectedPlayers.keys.map {
                PlayerStatus(it.id, server.playerName(it) ?: it.id, true)
            })
        ))

        // Finally, transition to the FINAL state, where we just sit here and do nothing.
        final@for (message in this.channel) {
            genericMessageHandler(message, players)

            when (message) {
                is RoomMessage.IncomingConnection -> {
                    // TODO: Cleanup this as a generic 'sendState' method.
                    try {
                        message.socket.send(ServerCommand.RoomStat(RoomStatus(
                            state = RoomState.FINISHED,
                            round = null,
                            players = connectedPlayers.keys.map {
                                PlayerStatus(it.id, server.playerName(it) ?: it.id, true)
                            })))
                    } catch (ex: Exception) { }
                }
                is RoomMessage.ClosedConnection -> if (connectedPlayers.size == 0) break@final
            }
        }

        // good game! why not play another?
    }

    /** Utility method called by the main actor to handle generic messages, like joins/leaves. */
    private suspend fun genericMessageHandler(message: RoomMessage, players: Set<PlayerSession>, newJoinsAllowed: Boolean = false) {
        when (message) {
            is RoomMessage.IncomingConnection -> {
                // Connections only allowed if the room is accepting players and there is space... OR if this is a rejoin.
                val connectionAllowed = connectedPlayers.containsKey(message.session)
                        || players.contains(message.session)
                        || (newJoinsAllowed && players.size < config.maxPlayers)
                if (!connectionAllowed) {
                    log.debug("An incoming connection from '{}' (name '{}') was rejected for room '{}'",
                        message.session.id, server.playerName(message.session) ?: "<unknown>", this@Room.id)
                    message.result.complete(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "This room is full or not accepting new players"))
                    return
                }

                // Update the connected player map, closing the previous player socket if one exists.
                // TODO: Launch a child coroutine for doing the closing, but don't propogate errors.
                connectedPlayers.put(message.session, message.socket)?.let {
                    it.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Only one websocket per user allowed for this room"))
                }

                // Return that the connection was successful back to the caller; at this point,
                // we can start recieving messages.
                message.result.complete(null)

                // Send this player the room configuration; each room state will send any further config information,
                // like the particular room state.
                try {
                    message.socket.send(ServerCommand.RoomConfig(config))
                } catch (ex: Exception) {
                    message.socket.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Failed to initialize client state"))
                    return
                }

                // Notify other users that this user has joined.
                val playerStatus = PlayerStatus(message.session.id, server.playerName(message.session) ?: message.session.id, true)
                broadcast(ServerCommand.PlayerJoined(playerStatus), exclude = message.session)
            }
            is RoomMessage.ClosedConnection -> {
                // If this was a genuine disconnect (and not a relog), then notify clients that a player has left.
                if (connectedPlayers.remove(message.session, message.socket)) {
                    broadcast(ServerCommand.PlayerLeft(message.session.id), exclude = message.session)
                }
            }
        }
    }

    // TODO: Make this better.
    /** Check if the given answer is close enough to the correct answer. */
    private fun isAnswerCloseEnough(correct: String, given: String) = correct.equals(given, ignoreCase = true)

    /** Broadcast a message to all connected clients, potentially excluding a single player. */
    private suspend fun broadcast(command: ServerCommand, exclude: PlayerSession? = null) {
        for ((player, socket) in connectedPlayers) {
            if (player == exclude) continue
            try { socket.send(command) } catch(ex: Exception) { log.error("Error during broadcast: ", ex) }
        }
    }

    /** Local extension function for sending a server command object directly, instead of raw text. */
    private suspend fun WebSocketServerSession.send(command: ServerCommand) {
        this.send(json.stringify(ServerCommand.serializer(), command))
    }
}

///////////////////////////////////////////////////////////////////////////////////////
// JSON-serializable room state which is exposed to the rest of the server and clients.
///////////////////////////////////////////////////////////////////////////////////////

// TODO: Change from seconds to milliseconds, probably.
/**
 * The full configuration/rules for a room; this is used to generate the music quiz as well as govern the flow of the game.
 * All times are in seconds.
 *
 * This class is serializable, and is sent over the wire as JSON to clients.
 */
@Serializable
data class RoomConfiguration(val playTime: Int = 20, val guessTime: Int = 10, val rounds: Int = 20, val maxPlayers: Int = 8)

/** The current status of a player in the game; just gives basic name and host information, as well as score. */
@Serializable
data class PlayerStatus(val id: String, val name: String, val host: Boolean)

// TODO: I used a Unix epoch because I'm too lazy to figure out how to use ZonedDateTimes. Fix this so we avoid crashing in 2038 :(
/**
 * The current status of a round in the game; gives the round start time (as a Unix timestamp), the prompt being asked,
 * as well as the guessing status of any players.
 */
@Serializable
data class RoundStatus(val round: Int, val roundStart: Long, val prompt: String, val guessed: Set<String>,
    val points: Map<String, Int>)

/**
 * The different possible states that the room can be in; the room will generally linearly transition from one state
 * to the next one (though it may go from 'Finished' back to 'Lobby', and it swaps between 'Ingame' and 'Ingame Buffering').
 */
@Serializable
enum class RoomState { LOBBY, LOADING, INGAME, INGAME_BUFFERING, FINISHED }

/**
 * A snapshot of the current status of the room - what state/phase it is in (such as 'Lobby' or '4th round in game'),
 * how many players are currently
 */
@Serializable
data class RoomStatus(val state: RoomState = RoomState.LOBBY, val round: RoundStatus? = null, val players: List<PlayerStatus> = listOf())

/////////////////////////////////////////////////////////
// Websocket protocol interchange format (JSON-based). //
/////////////////////////////////////////////////////////

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
    data class BufferComplete(val round: Int): ClientCommand()

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
    data class PlayerJoined(val player: PlayerStatus): ServerCommand()

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

//////////////////////////////////
// Room actor control messages. //
//////////////////////////////////

sealed class RoomMessage {

    // Server-originating commands.

    /** A new incoming connection to this room is occurring. */
    data class IncomingConnection(val session: PlayerSession, val socket: WebSocketServerSession, val result: CompletableDeferred<CloseReason?>) : RoomMessage()

    /** An existing connection to this room is closing. */
    data class ClosedConnection(val session: PlayerSession, val socket: WebSocketServerSession) : RoomMessage()

    /** Loading is complete, and we should move to the next state. */
    data class LoadingComplete(val quiz: Quiz) : RoomMessage()

    /** The round timer has been reached, so it should be ended forcibly. */
    data class RoundTimeout(val round: Int) : RoomMessage()

    // Client-based commands.

    /** The client has finished buffering up to the given round. */
    data class BufferComplete(val session: PlayerSession, val round: Int) : RoomMessage()

    /** The client is making the given guess for the given round (if the round is not the active round, it is ignored). */
    data class Guess(val session: PlayerSession, val guess: String, val round: Int) : RoomMessage()

    /** Forcibly jump to the next round. */
    object NextRound : RoomMessage()

    /** Start the game already. */
    object Start : RoomMessage()
}

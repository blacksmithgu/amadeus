package io.meltec.amadeus

import ClientCommand
import PlayerInfo
import RoomConfiguration
import RoomStatus
import ServerCommand
import io.ktor.http.cio.websocket.*
import io.ktor.websocket.WebSocketServerSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ActorScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.ticker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URL
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
@OptIn(ObsoleteCoroutinesApi::class)
class Room(val id: String, private val server: Amadeus) {
    /** The time this room was first created. */
    val createdTime: LocalDateTime = LocalDateTime.now()

    /**
     * The current room configuration. In order to make it easy for other parts of the server to quickly check room
     * status without interacting with the room controller, this is a public, readonly volatile field.
     */
    @Volatile
    var config: RoomConfiguration = RoomConfiguration()
        private set

    /**
     * The current room status as of the last status update. This is public in order to make it easy for other parts
     * of the server to quickly check room status without interacting with the room controller.
     *
     * This status may be several seconds out of date - it is updated occasionally by the room controller.
     */
    @Volatile
    var status: RoomStatus = RoomStatus.Lobby(listOf())
        private set

    /** The scope which all the coroutines for this room run in; allows for all of them to easily be canceled. */
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * A lazily-started actor which controls all room state. This is the only coroutine which directly
     * operates on private room state (like players).
     */
    private val controller = scope.actor<RoomMessage> { runRoom() }

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
            outer@ for (rawFrame in socket.incoming) {
                // The client should only ever send us JSON textual frames.
                val frame = (rawFrame as? Frame.Text)
                    ?: throw IllegalStateException("Received a binary websocket frame from a client")
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
                }

                controller.send(message)
            }
        } catch (ex: Exception) {
            // On any error which causes this socket to close, let the server know.
            log.error("Error during operation of websocket for user '${session.id}':", ex)
        }

        controller.send(RoomMessage.ClosedConnection(session, socket))
    }

    // Private state of the room which is only visible to the controller.

    /** Local json object used in data serialization/deserialization. */
    private val json = Json(JsonConfiguration.Stable)

    /** Map of players versus their current live websocket sessions. */
    private val connectedPlayers = HashMap<PlayerSession, WebSocketServerSession>()

    /** Main actor controller for this room; processes messages to keep the room moving along. */
    private suspend fun ActorScope<RoomMessage>.runRoom() {
        // This is a little bit of black magic, but we can implement the full room logic in a procedural function
        // (with some nice utility functions for each state) using the magic of coroutines. Cool.
        val (config, players) = lobby()
        val quiz = loading(config, players)
        val scores = game(quiz, players)
        finished(quiz, scores, players)
    }

    /** Result of the lobby stage of the game. */
    private data class LobbyResult(val config: RoomConfiguration, val players: Set<PlayerSession>)

    /** Lobby function which executes the lobby state; returns the final room config and player set. */
    private suspend fun ActorScope<RoomMessage>.lobby(): LobbyResult {
        fun state() = RoomStatus.Lobby(connectedPlayerInfo())

        outer@ for (message in this.channel) {
            genericMessageHandler(message, setOf(), { state() }, true)

            // Break when the game start is requested; nothing else to do in the lobby.
            when (message) {
                is RoomMessage.Start -> break@outer
            }
        }

        return LobbyResult(this@Room.config, HashSet(this@Room.connectedPlayers.keys))
    }

    /** Loading function which asynchronously generates the quiz that will be played. */
    private suspend fun ActorScope<RoomMessage>.loading(config: RoomConfiguration, players: Set<PlayerSession>): Quiz {
        fun state() = RoomStatus.Loading(connectedPlayerInfo())

        // Immediately kick off the background coroutines which generate the actual quiz.
        scope.launch {
            controller.send(RoomMessage.LoadingComplete(Quiz.darkSouls()))
        }

        // Notify users we are in the loading state.
        broadcast(ServerCommand.RoomStat(state()))

        outer@ for (message in this.channel) {
            genericMessageHandler(message, players, { state() })

            when (message) {
                is RoomMessage.LoadingComplete -> return message.quiz
            }
        }

        // This will only be reached on cancellation, so we can return whatever here.
        return Quiz(listOf())
    }

    /** Game function which plays the actual game; returns the final scores. */
    private suspend fun ActorScope<RoomMessage>.game(quiz: Quiz, players: Set<PlayerSession>): Map<String, Int> {
        if (quiz.questions.isEmpty()) return HashMap()

        // Shared buffer status which lets us know what round clients have buffered up to.
        // TODO: This buffer status should be (ID, Socket).
        val bufferStatus = HashMap<String, MutableSet<Int>>()

        // Scores for each player.
        val scores = HashMap<String, Int>()

        // Immediately start buffering the first song.
        startBuffering(0, quiz.questions[0].song)

        // Iterate for each round, swapping between the 'buffer', 'play', and 'postplay' states.
        for (round in quiz.questions.indices) {
            // BUFFER

            // TODO: Timeout any players who buffer for too long.
            fun bufferState() = RoomStatus.Buffering(round, bufferStatus.filter { round in it.value }.keys, scores, connectedPlayerInfo())

            // Broadcast we are in the buffer state, and buffer if necessary.
            broadcast(ServerCommand.RoomStat(bufferState()))
            if (!connectedPlayers.keys.all { round in bufferStatus.getOrDefault(it.id, HashSet()) }) {
                buffering@ for (message in this.channel) {
                    genericMessageHandler(message, players, { bufferState() })
                    when (message) {
                        is RoomMessage.IncomingConnection -> startBuffering(round, quiz.questions[round].song, message.socket)
                        is RoomMessage.ClosedConnection -> bufferStatus.remove(message.session.id) // TODO: Errorprone, fix this.
                        is RoomMessage.BufferComplete -> {
                            bufferStatus.computeIfAbsent(message.session.id) { HashSet() }.add(message.round)

                            val allReady = connectedPlayers.keys.all { round in bufferStatus.getOrDefault(it.id, HashSet()) }
                            if (allReady) break@buffering
                            else broadcast(ServerCommand.RoomStat(bufferState()))
                        }
                    }
                }
            }

            // Start buffering the next song while gameplay is ongoing.
            if (round + 1 <= quiz.questions.size - 1) startBuffering(round + 1, quiz.questions[round + 1].song)

            // PLAY

            val startTime = System.currentTimeMillis()
            val question = quiz.questions[round]
            val guesses = HashMap<String, String>()

            fun playState() = RoomStatus.Playing(round, startTime, question.prompt, guesses.keys, scores, connectedPlayerInfo())
            broadcast(ServerCommand.RoomStat(playState()))

            // Set up a timeout so we know when the round ends.
            val playTimeout = timeout((config.guessTime + config.playTime) * 1000L) {
                controller.send(RoomMessage.RoundTimeout(round))
            }

            playing@ for (message in this.channel) {
                genericMessageHandler(message, players, { playState() })
                when (message) {
                    is RoomMessage.IncomingConnection -> {
                        if (round <= quiz.questions.size - 1)
                            startBuffering(round + 1, quiz.questions[round + 1].song, message.socket)
                    }
                    is RoomMessage.BufferComplete -> bufferStatus.computeIfAbsent(message.session.id) { HashSet() }.add(message.round)
                    is RoomMessage.ClosedConnection -> bufferStatus.remove(message.session.id) // TODO: Errorprone, fix this.
                    is RoomMessage.Guess -> {
                        guesses[message.session.id] = message.guess
                        broadcast(ServerCommand.RoomStat(playState()))
                    }
                    is RoomMessage.NextRound -> break@playing
                    is RoomMessage.RoundTimeout -> break@playing
                }
            }

            playTimeout.cancel()

            // Give points to players who scored.
            val correctPlayers: MutableSet<String> = mutableSetOf()
            for ((playerId, guess) in guesses) {
                if (isAnswerCloseEnough(question.solution, guess)) {
                    correctPlayers.add(playerId)
                    scores.compute(playerId) { _, score -> (score ?: 0) + 1 }
                }
            }

            // REVIEW
            // Transition to the review state for a fixed amount of time.
            fun reviewState() = RoomStatus.Reviewing(round, question.prompt, question.solution, guesses, correctPlayers, scores, connectedPlayerInfo())
            broadcast(ServerCommand.RoomStat(reviewState()))

            val reviewTimeout = timeout(config.reviewTime * 1000L) {
                controller.send(RoomMessage.ReviewTimeout(round))
            }

            reviewing@ for (message in this.channel) {
                genericMessageHandler(message, players, { reviewState() })
                when (message) {
                    is RoomMessage.IncomingConnection -> {
                        if (round <= quiz.questions.size - 1)
                            startBuffering(round + 1, quiz.questions[round + 1].song, message.socket)
                    }
                    is RoomMessage.BufferComplete -> bufferStatus.computeIfAbsent(message.session.id) { HashSet() }.add(message.round)
                    is RoomMessage.ClosedConnection -> bufferStatus.remove(message.session.id) // TODO: Errorprone, fix this.
                    is RoomMessage.ReviewTimeout -> break@reviewing
                    is RoomMessage.NextRound -> break@reviewing
                }
            }

            reviewTimeout.cancel()
        }

        return scores
    }

    /** Start buffering the given songfile by sending it to all connected clients. */
    private suspend fun startBuffering(round: Int, songFile: URL) {
        scope.launch {
            val songData = songFile.readBytes()

            for ((player, socket) in connectedPlayers) {
                socket.sendOrClose(
                    ServerCommand.SongData(round, songData.size),
                    CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Failed to send song data")
                )
                socket.send(songData)
            }
        }
    }

    /** Start buffering a song for a single client. */
    private suspend fun startBuffering(round: Int, songFile: URL, socket: WebSocketServerSession) {
        scope.launch {
            val songData = songFile.readBytes()

            socket.sendOrClose(
                ServerCommand.SongData(round, songData.size),
                CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Failed to send song data")
            )
            socket.send(songData)
        }
    }

    private suspend fun ActorScope<RoomMessage>.finished(quiz: Quiz, score: Map<String, Int>, players: Set<PlayerSession>) {
        fun state() = RoomStatus.Finished(score, connectedPlayerInfo())

        broadcast(ServerCommand.RoomStat(state()))

        outer@ for (message in this.channel) {
            genericMessageHandler(message, players, { state() })

            when (message) {
                is RoomMessage.ClosedConnection -> if (connectedPlayers.size == 0) break@outer
            }
        }
    }

    /** Utility method called by the main actor to handle generic messages, like joins/leaves. */
    private suspend fun genericMessageHandler(
        message: RoomMessage,
        players: Set<PlayerSession>,
        stateFunc: () -> RoomStatus,
        newJoinsAllowed: Boolean = false
    ) {
        when (message) {
            is RoomMessage.IncomingConnection -> {
                // Connections only allowed if the room is accepting players and there is space... OR if this is a rejoin.
                val connectionAllowed =
                    connectedPlayers.containsKey(message.session) || players.contains(message.session) || (newJoinsAllowed && players.size < config.maxPlayers)
                if (!connectionAllowed) {
                    log.debug(
                        "An incoming connection from '{}' (name '{}') was rejected for room '{}'",
                        message.session.id, server.playerName(message.session) ?: "<unknown>", this@Room.id
                    )
                    message.result.complete(
                        CloseReason(
                            CloseReason.Codes.CANNOT_ACCEPT,
                            "This room is full or not accepting new players"
                        )
                    )
                    return
                }

                // Update the connected player map, closing the previous player socket if one exists.
                // TODO: Launch a child coroutine for doing the closing, but don't propagate errors.
                connectedPlayers.put(message.session, message.socket)?.close(
                    CloseReason(
                        CloseReason.Codes.GOING_AWAY,
                        "Only one websocket per user allowed for this room"
                    )
                )

                // Return that the connection was successful back to the caller; at this point,
                // we can start receiving messages.
                message.result.complete(null)

                // Send this player the room configuration; each room state will send any further config information,
                // like the particular room state.
                message.socket.sendOrClose(ServerCommand.RoomConfig(config), CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Failed to initialize client state"))
                message.socket.sendFallibly(ServerCommand.RoomStat(stateFunc()))

                // Notify other users that this user has joined.
                broadcast(ServerCommand.RoomStat(stateFunc()), exclude = message.session)
            }
            is RoomMessage.ClosedConnection -> {
                // If this was a genuine disconnect (and not a relog), then notify clients that a player has left.
                if (connectedPlayers.remove(message.session, message.socket)) {
                    broadcast(ServerCommand.RoomStat(stateFunc()))
                }
            }
        }
    }

    /** Run an action once after the given timeout; returns a cancellable channel. */
    private suspend fun timeout(timeMs: Long, action: suspend () -> Unit): ReceiveChannel<Unit> {
        val ticker = ticker(timeMs, timeMs, scope.coroutineContext)
        scope.launch {
            // If the ticker is cancelled we want to silently go away.
            try { ticker.receive() } catch (ex: Exception) { return@launch }
            action()
            ticker.cancel()
        }

        return ticker
    }

    /** Get the list of player information for all connected players. */
    private fun connectedPlayerInfo(): List<PlayerInfo> = connectedPlayers.keys.map {
        PlayerInfo(
            it.id,
            server.playerName(it) ?: "player-${it.id}",
            true
        )
    }

    /** Broadcast a message to all connected clients, potentially excluding a single player. */
    private suspend fun broadcast(command: ServerCommand, exclude: PlayerSession? = null) {
        for ((player, socket) in connectedPlayers) {
            if (player == exclude) continue
            socket.sendFallibly(command)
        }
    }

    // TODO: This will be a noisy log, silence it during deployment and have an alternative mechanism for recording failure.
    /** Attempt to send a message, doing nothing on a failed message send. */
    private suspend fun WebSocketServerSession.sendFallibly(command: ServerCommand) {
        try { this.send(command) } catch (ex: Exception) { log.error("Error when sending message: ", ex) }
    }

    /** Attempt to send a message, closing the socket on a failed send. */
    private suspend fun WebSocketServerSession.sendOrClose(command: ServerCommand, close: CloseReason) {
        try {
            this.send(command)
        } catch (ex: Exception) {
            this.close(close)
        }
    }

    /** Local extension function for sending a server command object directly, instead of raw text. */
    private suspend fun WebSocketServerSession.send(command: ServerCommand) {
        this.send(json.stringify(ServerCommand.serializer(), command))
    }

    // TODO: Make this better.
    /** Check if the given answer is close enough to the correct answer. */
    private fun isAnswerCloseEnough(correct: String, given: String) = correct.equals(given, ignoreCase = true)
}

/** Room actor control messages. */
sealed class RoomMessage {

    // Server-originating commands.

    /** A new incoming connection to this room is occurring. */
    data class IncomingConnection(
        /** The player who this connection is from. */
        val session: PlayerSession,
        /** The websocket represented by this connection. */
        val socket: WebSocketServerSession,
        /** TODO: What is this? */
        val result: CompletableDeferred<CloseReason?>
    ) : RoomMessage()

    /** An existing connection to this room is closing. */
    data class ClosedConnection(
        /** The player who this connection is from. */
        val session: PlayerSession,
        /** The websocket represented by this connection. */
        val socket: WebSocketServerSession
    ) : RoomMessage()

    /** Loading is complete, and we should move to the next state. */
    data class LoadingComplete(
        /** The quiz which has finished loading. */
        val quiz: Quiz
    ) : RoomMessage()

    /** The round timer has been reached, so it should be ended forcibly. */
    data class RoundTimeout(
        /** The round which has been timed out. */
        val round: Int
    ) : RoomMessage()

    /** The review window timeout has been reached, so it should be ended. */
    data class ReviewTimeout(
        /** The round which has been timed out. */
        val round: Int
    ) : RoomMessage()

    // Client-based commands.

    /** The client has finished buffering up to the given round. */
    data class BufferComplete(
        /** The player who has finished buffering. */
        val session: PlayerSession,
        /** The round the player finished buffering. */
        val round: Int
    ) : RoomMessage()

    /** The client is making the given guess for the given round (if the round is not the active round, it is ignored). */
    data class Guess(val session: PlayerSession, val guess: String, val round: Int) : RoomMessage()

    /** Forcibly jump to the next round. */
    object NextRound : RoomMessage()

    /** Start the game already. */
    object Start : RoomMessage()
}

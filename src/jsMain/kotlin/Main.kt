import io.ktor.client.*
import io.ktor.client.features.websocket.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.util.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.dom.clear
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

var config: RoomConfiguration = RoomConfiguration()
var state: RoomStatus = RoomStatus.Lobby(listOf())

var audioPlaying: Int? = null

val audioBuffers: MutableMap<Int, AudioSource> = mutableMapOf()
val audioContext: AudioContext = js("new AudioContext()") as AudioContext

val json: Json = Json(JsonConfiguration.Stable)

// Some constant HTML elements (see src/jvmMain/resources/templates/game.html) which we modify.
val playerDiv: HTMLDivElement = document.getElementById("players") as HTMLDivElement
val startButton: HTMLButtonElement = document.getElementById("lobby-start") as HTMLButtonElement
val guessBox: HTMLInputElement = document.getElementById("game-guess") as HTMLInputElement
val guessForm: HTMLFormElement = document.getElementById("game-guess-form") as HTMLFormElement
val gameTitle: HTMLElement = document.getElementById("game-round-title") as HTMLElement
val gamePrompt: HTMLParagraphElement = document.getElementById("game-prompt") as HTMLParagraphElement

// The main divs which hold the game state.
val lobbyDiv: HTMLDivElement = document.getElementById("lobby") as HTMLDivElement
val loadingDiv: HTMLDivElement = document.getElementById("loading") as HTMLDivElement
val gameDiv: HTMLDivElement = document.getElementById("game") as HTMLDivElement
val finishedDiv: HTMLDivElement = document.getElementById("finished") as HTMLDivElement

@KtorExperimentalAPI
suspend fun main() {
    console.log("Powered by KotlinJS - and the huge 2MB dependency it requires ;(")

    val client = HttpClient {
        install(WebSockets)
    }

    client.ws(
        method = HttpMethod.Get,
        host = window.location.hostname,
        port = window.location.port.toInt(),
        path = window.location.pathname
    ) {
        console.log("Successfully connected to the server at ws://${window.location.hostname}:${window.location.port}${window.location.pathname}")

        // Set up some bindings for various HTML elements.
        // The start button starts the game (obviously).
        startButton.onclick = {
            launch { send(ClientCommand.Start) }
        };

        // Guessing actually submits a guess, as you might hope.
        guessForm.onsubmit = {
            val guess = guessBox.value
            val round = state.round
            console.log("Submitting guess '${guess}'")
            guessBox.value = ""
            guessBox.focus()

            launch { send(ClientCommand.Guess(guess, round) )}
            false
        }

        game()
    }
}

suspend fun DefaultClientWebSocketSession.game() {
    var nextAudio: Int = -1

    for (message in this.incoming) {
        if (message is Frame.Text) {
            val decoded = json.parse(ServerCommand.serializer(), message.readText())
            when (decoded) {
                is ServerCommand.SongData -> nextAudio = decoded.round
                is ServerCommand.RoomConfig -> config = decoded.config
                is ServerCommand.RoomStat -> {
                    val oldState = state
                    console.log(decoded.state.toString())
                    state = decoded.state
                    render(oldState, state)
                }
            }
        } else if (message is Frame.Binary) {
            // An annoying limitation of Ktor; we need to massage the byte array into a ArrayBuffer :|
            val arrayBuffer = ArrayBuffer(message.data.size)
            val uint8Buffer = Uint8Array(arrayBuffer)
            uint8Buffer.set(message.data.toTypedArray(), 0)

            // The only binary messages we get are audio data.
            val buffer = audioContext.asyncDecodeAudio(arrayBuffer)
            val source = audioContext.createBufferSource()
            source.buffer = buffer
            source.connect(audioContext.destination)
            audioBuffers[nextAudio] = source
            console.log("Buffered audio for round $nextAudio")

            send(ClientCommand.BufferComplete(nextAudio))
        }
    }
}

suspend fun DefaultClientWebSocketSession.send(command: ClientCommand) {
    this.send(Frame.Text(json.stringify(ClientCommand.serializer(), command)))
}

//////////////////
// UI rendering //
//////////////////

fun render(oldState: RoomStatus, newState: RoomStatus) {
    val oldHighlights = oldState.players.map { oldState.playerHighlight(it.id) }
    val newHighlights = newState.players.map { newState.playerHighlight(it.id) }

    // Diff Players
    if (!oldState.players.equals(newState.players) || !oldHighlights.equals(newHighlights)) {
        playerDiv.clear()

        // TODO: Sort by a tiebreaker for ties.
        for (player in newState.players.sortedByDescending { newState.score(it.id) }) {
            val highlightColor = when(newState.playerHighlight(player.id)) {
                PlayerHighlight.NOTHING -> ""
                PlayerHighlight.CORRECT -> "bg-success"
                PlayerHighlight.INCORRECT -> "bg-danger"
                PlayerHighlight.GUESSED -> "bg-warning"
                PlayerHighlight.FINISHED_BUFFERING -> "bg-info"
            }

            playerDiv.append.div(classes = "row border border-dark mt-3 $highlightColor") {
                id = "player-${player.id}"
                div(classes = "col-auto pr-0") {
                    img(src = "http://via.placeholder.com/50", classes = "pt-1 pb-1")
                }

                div(classes = "col align-self-center pl-2") {
                    span(classes = "font-weight-bold") { text(player.name) }
                    br {}
                    span(classes = "text-muted") { text(player.id) }
                }

                div(classes="col-auto align-middle text-center") {
                    h1 { text(newState.score(player.id)) }
                }
            }
        }
    }

    // Swap which view we are in.
    val newStateType = newState::class.js.name
    val oldStateType = oldState::class.js.name

    // If the types are different...
    if (newStateType != oldStateType) {
        lobbyDiv.hidden = true
        loadingDiv.hidden = true
        gameDiv.hidden = true
        finishedDiv.hidden = true

        // Render the correct page...
        when (newState) {
            is RoomStatus.Lobby -> lobbyDiv.hidden = false
            is RoomStatus.Loading -> loadingDiv.hidden = false
            is RoomStatus.Buffering, is RoomStatus.Playing, is RoomStatus.Reviewing -> gameDiv.hidden = false
            is RoomStatus.Finished -> finishedDiv.hidden = false
        }

        // And start/stop audio...
        if (newState is RoomStatus.Playing) {
            audioPlaying?.let { audioBuffers[audioPlaying!!]?.stop() }
            audioBuffers[newState.round]?.start()

            audioPlaying = newState.round
        } else {
            audioPlaying?.let { audioBuffers[audioPlaying!!]?.stop() }
            audioPlaying = null
        }
    }

    // Update game state (simple text substitutions).
    when (newState) {
        is RoomStatus.Playing -> {
            gameTitle.innerText = "Round ${newState.round}"
            gamePrompt.innerText = newState.prompt
        }
        is RoomStatus.Buffering -> {
            gameTitle.innerText = "Round ${newState.round} (buffering)"
            gamePrompt.innerText = "BUFFERING"
        }
        is RoomStatus.Reviewing -> {
            gameTitle.innerText = "Round ${newState.round} (review)"
            gamePrompt.innerHTML= "${newState.prompt}<br><span class=\"font-weight-bold\">${newState.solution}</span>"
        }
    }
}

////////////////////////////////////////////////
// External Classes, mainly the web audio API //
////////////////////////////////////////////////

/** Main class for interacting with the Web Audio API. */
external class AudioContext {
    fun createBufferSource(): AudioSource
    fun decodeAudioData(data: ArrayBuffer, success: (AudioBuffer) -> Unit, error: (dynamic) -> Unit)

    val destination: AudioNode = definedExternally
}

/** A coroutine-friendly asynchronous decodeAudioData. */
suspend fun AudioContext.asyncDecodeAudio(data: ArrayBuffer): AudioBuffer = suspendCoroutine { cont ->
    this.decodeAudioData(data, { cont.resume(it) }, { cont.resumeWithException(IllegalArgumentException("Invalid audio data: $it")) })
}

/** A node within an audio computation graph. */
external open class AudioNode {
    fun connect(destination: AudioNode, output: Int = definedExternally, input: Int = definedExternally): AudioNode
}

/** A source of audio - usually backed by a buffer for our purposes. */
external class AudioSource : AudioNode {
    var buffer: AudioBuffer

    fun start()
    fun stop()
}

/** An opaque object containing audio data. */
external class AudioBuffer

// TODO: Rewrite to use ktor websocket
import org.w3c.dom.ARRAYBUFFER
import org.w3c.dom.BinaryType
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import kotlin.browser.window


external class AudioContext

val socket = WebSocket("ws://${window.location.host}${window.location.pathname}")
val audioContext = AudioContext()

val audioBuffers = listOf<ByteArray>()
val expectedAudioRound = 0

val state: RoomStatus = RoomStatus.Lobby(listOf())

fun WebSocket.game() {
}

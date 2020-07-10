// Create a new websocket upon loading the room.
const socket = new WebSocket("ws://" + location.host + location.pathname)
const audioContext = new AudioContext()

socket.binaryType = "arraybuffer"
socket.addEventListener("open", function(event) {
    console.log("Successfully connected to server")
})

socket.addEventListener("close", function(event) {
    console.log("Socket closed (code " + event.code + "): " + event.reason)
})

socket.addEventListener("message", function(event) {
    if (typeof event.data == 'string') {
        console.log(event.data)
    } else {
        audioContext.decodeAudioData(event.data, function(audioBuffer) {
            const source = audioContext.createBufferSource()
            source.buffer = audioBuffer
            source.connect(audioContext.destination)
            source.start(0)
        }, function(error) {
            console.log(error)
        })
    }
})

socket.addEventListener("error", function(event) {
    console.log("Lost Connection to the server: " + event)
})
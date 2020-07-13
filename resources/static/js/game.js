// Create a new websocket upon loading the room.
const socket = new WebSocket("ws://" + location.host + location.pathname)
const audioContext = new AudioContext()

// Any saved audio buffers for songs that will be played.
const audioBuffers = {}
// The last audio ID that was given to us (via SONG_DATA).
let expectedAudioRound = 0
// The current game state.
let state = {
    state: "LOBBY",
    round: null,
    players: []
}
let config = { }

socket.binaryType = "arraybuffer"
socket.addEventListener("open", function(event) {
    console.log("Successfully connected to server")
})

socket.addEventListener("close", function(event) {
    console.log("Socket closed (code " + event.code + "): " + event.reason)
})

socket.addEventListener("message", function(event) {
    if (typeof event.data == 'string') {
        console.log(JSON.parse(event.data))
        execute(JSON.parse(event.data))
    } else {
        audioContext.decodeAudioData(event.data, function(audioBuffer) {
            const source = audioContext.createBufferSource()
            source.buffer = audioBuffer
            source.connect(audioContext.destination)
            audioBuffers[expectedAudioRound] = source
            bufferComplete(expectedAudioRound)
            console.log("Recieved audio for round " + expectedAudioRound)
        }, function(error) {
            console.log(error)
        })
    }
})

socket.addEventListener("error", function(event) {
    console.log("Lost Connection to the server: " + event)
})

// Diffs the old and new states, executing any changes in the UI that are necessary.
function diffState(oldState, newState) {
    if (oldState.state == "INGAME" && newState.state == "INGAME_BUFFERING") {
        console.log("Ending round " + oldState.round.round)
        audioBuffers[oldState.round.round].stop()
    } else if (oldState.state == "INGAME_BUFFERING" && newState.state == "INGAME") {
        console.log("Starting round " + newState.round.round)
        console.log("Prompt: " + newState.round.prompt)
        audioBuffers[newState.round.round].start()
    }
}

function execute(command) {
    if (command.type == "SONG_DATA") expectedAudioRound = command.round
    else if (command.type == "ROOM_STATE") {
        let oldState = state
        state = command.state
        diffState(oldState, state)
    }
}

function start() {
    socket.send(JSON.stringify({ type: "START" }))
}

function next() {
    socket.send(JSON.stringify({ type: "NEXT" }))
}

function guess(theGuess) {
    socket.send(JSON.stringify({ type: "GUESS", round: state.round.round, guess: theGuess }))
}

function bufferComplete(round) {
    socket.send(JSON.stringify({ type: "BUFFER_COMPLETE", round: round }))
}
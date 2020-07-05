import './scss/main.scss'
import 'game.ts'

var testAddRoomButton = document.createElement("button")
testAddRoomButton.innerHTML = "Test Add Room"
testAddRoomButton.addEventListener("click", function(){ addRoom("test", "Test Room", [], false)})
document.body.appendChild(testAddRoomButton)

var testRemoveRoomButton = document.createElement("button")
testRemoveRoomButton.innerHTML = "Test Remove Room"
testRemoveRoomButton.addEventListener("click", function(){ removeRoom("test")})
document.body.appendChild(testRemoveRoomButton)

var roomlist = <HTMLTableElement> document.getElementById("roomlist")
function addRoom(id:string, name:string, players:Player[], hasPassword:boolean){
    var row = roomlist.insertRow(-1)
    row.id = id
    var nameCell = row.insertCell(0)
    var playersCell = row.insertCell(1)
    var passwordCell = row.insertCell(2)
    var joinCell = row.insertCell(3)

    var playerNames = players.map(i => i.name)

    nameCell.innerHTML = name
    playersCell.innerHTML = playerNames.toString()
    if (hasPassword) passwordCell.innerHTML = "Yes"
    else passwordCell.innerHTML = "No"

    var joinButton = document.createElement("button")
    joinButton.id = "join"+id
    joinButton.innerHTML = "Join"
    joinButton.addEventListener("click", function(){ joinRoom(id) })
    joinCell.appendChild(joinButton)
}

function removeRoom(id:string){
    var row = <HTMLTableRowElement> document.getElementById(id)
    roomlist.deleteRow(row.rowIndex)
}

function joinRoom(id:string) {
    console.log("Redirect to room "+id)
}



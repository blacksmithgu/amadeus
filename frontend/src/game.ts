var playerContainer = document.getElementById("playercontainer")
var scoreboard = <HTMLTableElement>document.getElementById("scoreboard")
class Player {
    name: string
    score: number
}

function addPlayer(name:string) {
    var newPlayer = document.createElement('div')
    playerContainer.appendChild(newPlayer)

    var row = scoreboard.insertRow(-1)
    var nameCell = row.insertCell(0)
    var scoreCell = row.insertCell(1)

    nameCell.innerHTML = name
    scoreCell.innerHTML = "0"
}
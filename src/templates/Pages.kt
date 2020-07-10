package io.meltec.amadeus.templates

import io.ktor.html.Placeholder
import io.ktor.html.Template
import io.ktor.html.insert
import io.meltec.amadeus.CompletedYoutubeDownload
import io.meltec.amadeus.QueuedYoutubeDownload
import io.meltec.amadeus.Room
import kotlinx.html.*

class DefaultTemplate : Template<HTML> {
    val content = Placeholder<HtmlBlockTag>()
    override fun HTML.apply() {
        head {
            title("Amadeus")
            meta(name = "description", content = "Amadeus - Youtube Music Guessing Game")
            styleLink("/main.css")
        }
        body {
            insert(content)
        }
    }
}

fun DefaultTemplate.registrationPage() {
    content {
        classes = setOf("text-center")
        id = "registration"
        form(classes = "form-register") {
            action = "/register"
            method = FormMethod.post
            h1(classes = "mb-3 font-weight-normal") { +"Amadeus" }
            input(classes = "form-control mb-3") {
                type = InputType.text
                id = "inputDisplayName"
                name = "displayName"
                placeholder = "Display name"
                required = true
                autoFocus = true
            }
            button(classes = "btn btn-lg btn-primary btn-block") { +"Enter" }
        }
    }
}

// TODO: Make this much more generic instead of taking heavyweight room objects.
fun DefaultTemplate.roomsPage(rooms: List<Room>) {
    content {
        container {
            row {
                for (room in rooms) {
                    col(GridBreakpoint.MEDIUM, 4) {
                        card {
                            classes += setOf("mt-4", "shadow-sm")
                            cardBody {
                                h5(classes = "card-title") { text(room.id) }
                                p(classes = "card-text") {
                                    text("${room.numPlayers()}/${room.config.maxPlayers} players")
                                }
                                a(href = "/room/$room", classes = "btn btn-primary float-right") {
                                    +"Join"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun DefaultTemplate.roomPage(room: String) {
    content {
        h1 { +"Room '$room'" }
        div(classes = "container-fluid h-100") {
            div(classes = "row justify-content-center align-items-center") {
                button(type = ButtonType.button, classes = "btn btn-lg btn-primary") {
                    onClick = "console.log(\"Play the first song\")"
                    + "Start Game"
                }
            }
            div(classes = "row h-100 justify-content-center align-items-center") {
                div(classes = "col") {
                    div(classes = "row justify-content-center align-items-center") {
                        audio {
                            controls = true
                        }
                        button(type = ButtonType.button, classes = "btn btn-primary") { +"Next Song" }
                    }
                    div(classes = "row justify-content-center align-items-center") {
                        form(classes = "form-inline") {
                            input(classes = "form-control") {
                                type = InputType.text
                                id = "inputGuess"
                                name = "guess"
                                placeholder = "Guess"
                            }
                            button(classes = "btn btn-primary") { +"Submit" }
                        }
                    }
                }
            }
        }

        script(type = "text/javascript", src = "/js/game.js") { }
    }
}

/** A status page which shows all in-process and completed downloads, as well as a form for submitting a new download. */
fun DefaultTemplate.youtubeStatusPage(completed: List<CompletedYoutubeDownload>, queued: List<QueuedYoutubeDownload>) {
    content {
        h1(classes = "text-center") { +"Youtube Download Status" }
        div(classes = "container rounded border") {
            // TODO: Make this action typed.
            form(action = "/youtube-dl", method = FormMethod.post, classes = "container rounded border") {
                div(classes = "form-group") {
                    label { htmlFor = "urls"; +"Youtube URLs" }
                    textArea(classes = "form-control", rows = "4") { name = "urls" }
                }
                div(classes = "text-center") {
                    button(type = ButtonType.submit, classes = "btn btn-primary") { +"Queue Downloads" }
                }
            }
        }

        div(classes = "container-fluid rounded border mt-4") {
            h1 { +"Queued Downloads" }
            table(classes = "table table-sm table-striped table-hover text-center") {
                thead {
                    tr {
                        th(scope = ThScope.col) { +"ID" }
                        th(scope = ThScope.col) { +"URL" }
                        th(scope = ThScope.col) { +"Request Time" }
                        th(scope = ThScope.col) {
                            style = "width: 1px;"
                            +"Actions"
                        }
                    }
                }
                tbody {
                    for (download in queued) {
                        tr {
                            th(scope = ThScope.row) { text(download.id) }
                            td { +download.url }
                            td { +download.requestTime.toString() }
                            td(classes = "text-right") {
                                button(classes = "btn btn-danger") { +"Cancel" }
                            }
                        }
                    }
                }
            }
        }

        div(classes = "container-fluid rounded border mt-4") {
            h1 { +"Completed Downloads" }
            table(classes = "table table-sm table-striped table-hover text-left") {
                thead {
                    tr {
                        th(scope = ThScope.col) { +"ID" }
                        th(scope = ThScope.col) { +"URL" }
                        th(scope = ThScope.col) { +"Request Time" }
                        th(scope = ThScope.col) { +"Complete Time" }
                        th(scope = ThScope.col) { +"Title/Error" }
                        th(scope = ThScope.col, classes="text-center") {
                            style = "width: 1px;"
                            +"Actions"
                        }
                    }
                }
                tbody {
                    for (download in completed) {
                        tr {
                            th(scope = ThScope.row) { text(download.id) }
                            td { +download.url }
                            td { +download.requestTime.toString() }
                            td { +download.completedTime.toString() }
                            td {
                                when(download) {
                                    is CompletedYoutubeDownload.Success -> +(download.meta.title ?: "<unknown>")
                                    is CompletedYoutubeDownload.Error -> +download.reason
                                }
                            }
                            td(classes = "text-right") {
                                style = "white-space: nowrap;"
                                button(classes = "btn btn-primary") { +"Make Song" }
                                button(classes = "btn btn-danger") { +"Cancel" }
                            }
                        }
                    }
                }
            }
        }
    }
}
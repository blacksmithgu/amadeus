package io.meltec.amadeus.templates

import io.ktor.html.Placeholder
import io.ktor.html.Template
import io.ktor.html.insert
import io.meltec.amadeus.CompletedYoutubeDownload
import io.meltec.amadeus.QueuedYoutubeDownload
import io.meltec.amadeus.Room
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.HtmlBlockTag
import kotlinx.html.InputType
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.audio
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h5
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.meta
import kotlinx.html.onClick
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.style
import kotlinx.html.styleLink
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textArea
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr

/** Default template containing the main css and js for Amadeus. */
class DefaultTemplate : Template<HTML> {
    /** The content of the page. Inserted into the body tag. */
    val content: Placeholder<HtmlBlockTag> = Placeholder()
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
                                    text("${room.status.players.size}/${room.config.maxPlayers} players")
                                }
                                a(href = "/room/${room.id}", classes = "btn btn-primary float-right") {
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
                    +"Start Game"
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
                    for ((id, url, requestTime) in queued) {
                        tr {
                            th(scope = ThScope.row) { text(id) }
                            td { +url }
                            td { +requestTime.toString() }
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
                        th(scope = ThScope.col, classes = "text-center") {
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
                                when (download) {
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

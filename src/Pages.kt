package io.meltec.amadeus

import io.ktor.html.Placeholder
import io.ktor.html.Template
import io.ktor.html.insert
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

fun DefaultTemplate.roomsPage(rooms: List<String>) {
    content {
        container {
            row {
                for (room in rooms) {
                    col(GridBreakpoint.MEDIUM, 4) {
                        card {
                            classes += setOf("mt-4", "shadow-sm")
                            cardBody {
                                h5(classes = "card-title") { +room }
                                p(classes = "card-text") {
                                    +"Some utterly useless example text to confuse the player even more and take up space"
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

fun DefaultTemplate.roomPage(player: String, room: String) {
    content {
        h1 { +"$player joined room $room" }
    }
}

/** A status page which shows all in-process and completed downloads, as well as a form for submitting a new download. */
fun DefaultTemplate.youtubeStatusPage(completed: List<CompletedYoutubeDownload>, queued: List<QueuedYoutubeDownload>) {
    content {
        h1(classes = "text-center") { +"Youtube-DL Status" }
        form {
            action = "/youtube-dl"
            method = FormMethod.post
            textArea(classes = "form-control") {
                id = "inputUrls"
                name = "urls"
                placeholder = "<Enter list of newline-seperated urls here>"
                required = true
                autoFocus = true
            }
            button(classes = "btn btn-lg btn-primary btn-block") { +"Submit" }
        }
        h2(classes = "text-center") { +"Queued Downloads" }
        table {
            tr {
                th { +"ID" }
                th { +"URL" }
                th { +"Queued Time" }
            }
            for (download in queued) {
                tr {
                    td { text(download.id) }
                    td { text(download.url) }
                    td { text(download.requestTime.toString()) }
                }
            }
        }
        h2(classes = "text-center") { +"Completed Downloads" }
        table {
            tr {
                th { +"ID" }
                th { +"URL" }
                th { +"Queued Time" }
                th { +"Completed Time" }
                th { +"Title" }
            }
            for (download in completed) {
                tr {
                    td { text(download.id) }
                    td { text(download.url) }
                    td { text(download.requestTime.toString()) }
                    td { text(download.completedTime.toString()) }
                    td {
                        when(download) {
                            is CompletedYoutubeDownload.Success -> text(download.meta.title ?: "<unknown>")
                            else -> text("<failed>")
                        }
                    }
                }
            }
        }
    }
}
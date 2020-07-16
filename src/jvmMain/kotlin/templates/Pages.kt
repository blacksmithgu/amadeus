package io.meltec.amadeus.templates

import io.ktor.html.Placeholder
import io.ktor.html.Template
import io.ktor.html.insert
import io.meltec.amadeus.CompletedYoutubeDownload
import io.meltec.amadeus.QueuedYoutubeDownload
import io.meltec.amadeus.Room
import kotlinx.html.*

/** Default template containing the main css and js for Amadeus. */
class DefaultTemplate : Template<HTML> {
    /** The content of the page. Inserted into the body tag. */
    val content: Placeholder<HtmlBlockTag> = Placeholder()
    override fun HTML.apply() {
        head {
            title("Amadeus")
            meta(name = "description", content = "Amadeus - Youtube Music Guessing Game")

            // TODO: For production, use locally served bootstrap.
            link {
                rel = "stylesheet"
                href = "https://stackpath.bootstrapcdn.com/bootstrap/4.5.0/css/bootstrap.min.css"
                integrity = "sha384-9aIt2nRpC12Uk9gS9baDl411NQApFmC26EwAOH8WgZl5MYYxFfc+NcPb1dKGj7Sk"

                attributes.put("crossorigin", "anonymous")
            }
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

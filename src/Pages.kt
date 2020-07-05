package io.meltec.amadeus

import kotlinx.html.*

fun HTML.defaultTemplate(content: BODY.() -> Unit) {
    head {
        title = "Amadeus"
        meta(name = "description", content = "Amadeus - Youtube Music Guessing Game")
        styleLink("main.css")
    }
    body { content() }
}

fun HTML.registrationPage() {
    defaultTemplate {
        classes = setOf("text-center")
        id = "registration"
        form(classes = "form-register") {
            h1(classes = "mb-3 font-weight-normal") { +"Amadeus" }
            input(classes = "form-control mb-3") {
                type = InputType.text
                id = "inputName"
                placeholder = "Name"
                required = true
                autoFocus = true
            }
            button(classes = "btn btn-lg btn-primary btn-block") { +"Enter"}
        }
    }
}
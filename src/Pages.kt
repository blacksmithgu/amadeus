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
            styleLink("main.css")
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
            button(classes = "btn btn-lg btn-primary btn-block") { +"Enter"}
        }
    }
}
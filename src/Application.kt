package io.meltec.amadeus

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.request.*
import io.ktor.routing.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.features.*
import org.slf4j.event.*
import io.ktor.util.date.*
import io.ktor.websocket.*
import io.ktor.http.cio.websocket.*
import java.time.*
import com.fasterxml.jackson.databind.*
import io.ktor.jackson.*
import io.ktor.util.KtorExperimentalAPI

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

// TODO: Consider not using application.conf and instead writing our own configuration, which we pass to
// https://ktor.io/servers/configuration.html

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = true) {
    // Automatically compress responses for network savings.
    install(Compression) {
        gzip {
            priority = 1.0
        }
        deflate {
            priority = 10.0
            minimumSize(1024)
        }
    }

    // Automatically respond to HTTP HEAD requests, which return the headers that would be obtained via an HTTP GET.
    install(AutoHeadResponse)

    // Log API calls; this is a DEV-only feature which we'll disable in production deployments.
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
    }

    // Global CORS allowance; i.e., arbitrary JavaScript from any website can make requests to our APIs.
    install(CORS) {
        method(HttpMethod.Options)
        method(HttpMethod.Post)
        method(HttpMethod.Put)
        method(HttpMethod.Delete)
        method(HttpMethod.Patch)
        header(HttpHeaders.Authorization)
        allowCredentials = true
        anyHost() // TODO: Don't do this in production if possible. Try to limit it.
    }

    // Add cache headers allowing browsers to cache static content of ours (mainly CSS files, and other junk).
    install(CachingHeaders) {
        options { outgoingContent ->
            when (outgoingContent.contentType?.withoutParameters()) {
                ContentType.Text.CSS -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 24 * 60 * 60), expires = null as? GMTDate?)
                else -> null
            }
        }
    }

    // Automatic data marshalling (i.e., automatic JSON parsing, for example) for route handlers.
    install(DataConversion)

    // Ktor-provided HTTPS redirect; automatically forces HTTP to upgrade to HTTPS. Only enabled if running in a production.
    // https://ktor.io/servers/features/https-redirect.html#testing
    if (!testing) {
        install(HttpsRedirect) {
            // The port to redirect to. By default 443, the default HTTPS port.
            sslPort = 443
            // 301 Moved Permanently, or 302 Found redirect.
            permanentRedirect = true
        }
    }

    // WebSocket support; allows the creation of ws:// routes.
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = 1024 * 1024 // 1MB
        masking = false
    }

    // Jackson-based data serialization; could also use gson or something, but Jackson is quite fast and I've used it before.
    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
        }
    }

    // Status-pages which show up on thrown exception. Nothing for now; you can add per-exception-type handling.
    install(StatusPages)

    // Main routing table.
    routing {
        get("/") {
            call.respondText("HELLO WORLD!", contentType = ContentType.Text.Plain)
        }

        // Static feature. Try to access `/static/ktor_logo.svg`
        static("/static") {
            resources("static")
        }

        webSocket("/myws/echo") {
            send(Frame.Text("Hi from server"))
            while (true) {
                val frame = incoming.receive()
                if (frame is Frame.Text) {
                    send(Frame.Text("Client said: " + frame.readText()))
                }
            }
        }
    }
}

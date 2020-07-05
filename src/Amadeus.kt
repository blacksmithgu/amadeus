package io.meltec.amadeus

import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.*
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.cio.websocket.pingPeriod
import io.ktor.http.cio.websocket.timeout
import io.ktor.http.content.CachingOptions
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.jackson.jackson
import io.ktor.request.path
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.util.date.GMTDate
import io.ktor.websocket.WebSockets
import org.slf4j.event.Level
import java.time.Duration

/**
 * Central application information and metadata.
 */
class Amadeus(val database: Database) {

    /** Convenience function which configures the given application with Amadeus routes. */
    fun configure(app: Application, testing: Boolean) = app.amadeus(testing)

    /**
     * Configuration function on application which configures amadeus routes. The `this` in this function is
     * `Application`, not `Amadeus`.
     */
    fun Application.amadeus(testing: Boolean) {
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
            // A corny 'Hello World' until we get some more meaningful things.
            get("/") {
                call.respondText("HELLO WORLD!", contentType = ContentType.Text.Plain)
            }

            // Directly serve anything in resources/static to the root directory if previous dynamic paths fail.
            static {
                resources("static")
            }
        }
    }
}

package io.meltec.amadeus

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.html.respondHtmlTemplate
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.cio.websocket.pingPeriod
import io.ktor.http.cio.websocket.timeout
import io.ktor.http.content.CachingOptions
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.locations.Location
import io.ktor.locations.Locations
import io.ktor.locations.get
import io.ktor.locations.post
import io.ktor.request.path
import io.ktor.request.receiveParameters
import io.ktor.response.respondRedirect
import io.ktor.routing.routing
import io.ktor.serialization.json
import io.ktor.sessions.*
import io.ktor.util.date.GMTDate
import io.ktor.util.generateNonce
import io.ktor.util.pipeline.PipelineContext
import io.ktor.websocket.WebSockets
import org.slf4j.event.Level
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Central application information and metadata.
 */
class Amadeus(val database: Database, val downloader: YoutubeDownloader) {

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
                    ContentType.Text.CSS -> CachingOptions(
                        CacheControl.MaxAge(maxAgeSeconds = 24 * 60 * 60),
                        expires = null as? GMTDate?
                    )
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

        // kotlinx.serialization-based json serialization.
        install(ContentNegotiation) {
            json()
        }

        // Status-pages which show up on thrown exception. Nothing for now; you can add per-exception-type handling.
        install(StatusPages)

        // Enable the use of sessions to keep information between requests of the browser.
        install(Sessions) {
            cookie<PlayerSession>("SESSION")
            cookie<JoinRoomSession>("JOIN_ROOM")
        }

        // Enable the use of locations, a type safe way to create routes
        install(Locations)

        // Create a session in each request if no session exists
        intercept(ApplicationCallPipeline.Features) {
            call.sessions.getOrSet { PlayerSession(generateNonce()) }
        }

        // Main routing table.
        routing {
            // Landing page, allows the player to register a display name
            get<RootRoute> {
                call.respondHtmlTemplate(DefaultTemplate()) {
                    registrationPage()
                }
            }
            // Register a name to the current session so that players can identify each other
            post<RegisterRoute> {
                ensureSession { session ->
                    call.receiveParameters()["displayName"]?.let {
                        playerNames[session] = it
                        call.respondRedirect("/room")
                        return@post
                    }
                }
                call.respondRedirect("/")
            }

            get<RoomsRoute> {
                ensureSession { session ->
                    // Display a list of rooms
                    playerNames[session]?.let {
                        // Redirect if the player had tried joining a room earlier
                        call.sessions.get<JoinRoomSession>()?.let {
                            call.respondRedirect("/room/${it.id}")
                            return@get
                        }
                        // Display the rooms
                        call.respondHtmlTemplate(DefaultTemplate()) {
                            roomsPage(listOf("a", "b", "c", "d", "e", "f", "g"))
                        }
                        return@get
                    }
                }
                call.respondRedirect("/")
            }

            get<RoomRoute> { roomRequest ->
                ensureSession { session ->
                    // Join a specific room, this page will create a WebSocket for game communication
                    call.sessions.clear<JoinRoomSession>()
                    playerNames[session]?.let {
                        call.respondHtmlTemplate(DefaultTemplate()) {
                            roomPage(it, roomRequest.id ?: "No room provided")
                        }
                        return@get
                    }
                    // Before redirecting to the landing page, remember the room they tried joining
                    call.sessions.set(JoinRoomSession(call.parameters["id"] ?: ""))

                    call.respondRedirect("/")
                }
            }

            // Directly serve anything in resources/static to the root directory if previous dynamic paths fail.
            static {
                resources("static")
            }
        }
    }

    private inline fun PipelineContext<Unit, ApplicationCall>.ensureSession(block: (PlayerSession) -> Unit) {
        call.sessions.get<PlayerSession>()?.let { block(it) }
    }

    private val playerNames = ConcurrentHashMap<PlayerSession, String>()
}

/**
 * A player session identified by a unique nonce ID.
 */
inline class PlayerSession(val id: String)

/**
 * Used to redirect the player back to a room if they didn't have a name.
 */
inline class JoinRoomSession(val id: String)

/**
 * Route for the root page
 */
@Location("/")
class RootRoute

/**
 * Route for registering a display name
 */
@Location("/register")
class RegisterRoute

/**
 * Route for displaying the available rooms
 */
@Location("/room")
class RoomsRoute

/**
 * Route for joining a room
 */
@Location("/room/{id}")
data class RoomRoute(val id: String)


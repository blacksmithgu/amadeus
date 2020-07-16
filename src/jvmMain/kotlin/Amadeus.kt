package io.meltec.amadeus

import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.AutoHeadResponse
import io.ktor.features.CORS
import io.ktor.features.CachingHeaders
import io.ktor.features.CallLogging
import io.ktor.features.Compression
import io.ktor.features.ContentNegotiation
import io.ktor.features.DataConversion
import io.ktor.features.HttpsRedirect
import io.ktor.features.StatusPages
import io.ktor.features.deflate
import io.ktor.features.gzip
import io.ktor.features.minimumSize
import io.ktor.html.respondHtmlTemplate
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.cio.websocket.close
import io.ktor.http.cio.websocket.pingPeriod
import io.ktor.http.cio.websocket.timeout
import io.ktor.http.content.CachingOptions
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.locations.KtorExperimentalLocationsAPI
import io.ktor.locations.Location
import io.ktor.locations.Locations
import io.ktor.locations.get
import io.ktor.locations.href
import io.ktor.locations.post
import io.ktor.request.path
import io.ktor.request.receiveParameters
import io.ktor.response.respond
import io.ktor.response.respondRedirect
import io.ktor.routing.routing
import io.ktor.serialization.json
import io.ktor.sessions.Sessions
import io.ktor.sessions.cookie
import io.ktor.sessions.get
import io.ktor.sessions.getOrSet
import io.ktor.sessions.sessions
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.date.GMTDate
import io.ktor.util.generateNonce
import io.ktor.util.pipeline.PipelineContext
import io.ktor.websocket.WebSockets
import io.meltec.amadeus.templates.DefaultTemplate
import io.meltec.amadeus.templates.registrationPage
import io.meltec.amadeus.templates.roomPage
import io.meltec.amadeus.templates.roomsPage
import io.meltec.amadeus.templates.youtubeStatusPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.slf4j.event.Level
import webSocket
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

/**
 * Central application information and metadata.
 */
@OptIn(KtorExperimentalLocationsAPI::class)
class Amadeus(private val database: Database, private val downloader: YoutubeDownloader) {
    /** Maps active player sessions to the nicknames of the player. */
    private val playerNames = ConcurrentHashMap<PlayerSession, String>()

    /** Thread/coroutine-safe map of room IDs (names) to the rooms. */
    private val rooms = ConcurrentHashMap<String, Room>()

    /** Convenience function which configures the given application with Amadeus routes. */
    fun configure(app: Application, testing: Boolean): Unit = app.amadeus(testing)

    /**
     * Configuration function on application which configures amadeus routes. The `this` in this function is
     * `Application`, not `Amadeus`.
     */
    @OptIn(ExperimentalCoroutinesApi::class, KtorExperimentalAPI::class)
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
            maxFrameSize = 4 * 1024 * 1024 // 4MB
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
        }

        // Enable the use of locations, a type safe way to create routes
        install(Locations)

        // Create a session in each request if no session exists
        intercept(ApplicationCallPipeline.Features) {
            call.sessions.getOrSet<PlayerSession> { PlayerSession(generateNonce()) }
        }

        // Main routing table.
        routing {
            // Landing page, allows the player to register a display name
            get<Root> {
                call.respondHtmlTemplate(DefaultTemplate()) {
                    registrationPage()
                }
            }

            // Shows all active youtube downloads (completed and queued), as well as a form for submitting new ones.
            get<Root.YoutubeDl> {
                call.respondHtmlTemplate(DefaultTemplate()) {
                    youtubeStatusPage(database.allCompletedDownloads(), database.allQueuedDownloads())
                }
            }

            // Allows for queueing of multiple youtube URLs on the youtube downloader.
            post<Root.YoutubeDl> {
                // TODO: Need a JSON format for proper errors.
                val urls = call.receiveParameters()["urls"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, "No urls specified")
                    return@post
                }

                for (url in urls.lines()) downloader.queue(url)
                respondRedirect(Root.YoutubeDl())
            }

            // Register a name to the current session so that players can identify each other.
            post<Root.Register> {
                ensureSession { session ->
                    call.receiveParameters()["displayName"]?.let {
                        playerNames[session] = it
                        respondRedirect(Root.Rooms())
                        return@post
                    }
                }
                respondRedirect(Root())
            }

            // Obtain a list of active rooms, or create a room.
            get<Root.Rooms> {
                ensureSession { session ->
                    // Display a list of rooms
                    playerNames[session]?.let {
                        call.respondHtmlTemplate(DefaultTemplate()) {
                            roomsPage(rooms.values.toList())
                        }
                        return@get
                    }
                }
                respondRedirect(Root())
            }

            // The 'big' call - serves the actual room itself.
            get<Root.Rooms.Room> { (id) ->
                val session = call.sessions.get<PlayerSession>() ?: kotlin.run {
                    respondRedirect(Root())
                    return@get
                }

                // User has a session but has not registered yet.
                // TODO: Make this a generic check for most paths.
                if (playerName(session) == null) {
                    respondRedirect(Root())
                    return@get
                }

                // TODO: We shouldn't create a new room on a GET call, but I'm doing it anyway.
                rooms.computeIfAbsent(id) { Room(it, this@Amadeus) }

                // Join a specific room, this page will create a WebSocket for game communication
                call.respondHtmlTemplate(DefaultTemplate()) {
                    roomPage(id)
                }
            }

            webSocket<Root.Rooms.Room> { (id) ->
                val session = call.sessions.get<PlayerSession>() ?: kotlin.run {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No player session found; please log in"))
                    return@webSocket
                }

                val room = rooms[id] ?: kotlin.run {
                    close(
                        CloseReason(
                            CloseReason.Codes.CANNOT_ACCEPT,
                            "Invalid room ID $id; there is no such room!"
                        )
                    )
                    return@webSocket
                }

                // Pass off to the room to handle the player; if this function returns, then the web socket will
                // close (since we do not do anything else here).
                room.handleConnection(session, this)
            }

            // Directly serve anything in resources/static to the root directory if previous dynamic paths fail.
            static {
                resources("static")
                resources("")
            }
        }
    }

    /** Get the name of the player associated with the given session, if present. */
    fun playerName(session: PlayerSession): String? = playerNames[session]

    private inline fun PipelineContext<Unit, ApplicationCall>.ensureSession(block: (PlayerSession) -> Unit) {
        call.sessions.get<PlayerSession>()?.let { block(it) }
    }

    /** A form of redirect which allows for passing location objects (i.e., typed redirects). */
    private suspend inline fun PipelineContext<Unit, ApplicationCall>.respondRedirect(location: Any) {
        call.respondRedirect(href(location))
    }
}

/** A player session identified by a unique nonce ID. */
inline class PlayerSession(
    /** The unique id for this session. */
    val id: String
)

/** Root of the typed routing table. */
@OptIn(KtorExperimentalLocationsAPI::class)
@Location("/")
class Root {
    /** Route for registering a new user. */
    @Location("register")
    class Register

    /** Route for queueing youtube-dl downloads directly and viewing their status. */
    @Location("youtube-dl")
    class YoutubeDl

    /** Route for room-related operations; shows the room list by default. */
    @Location("room")
    class Rooms {
        /** Route for a specific room with the given id. */
        @Location("{id}")
        data class Room(
            /** The id for the specific room. */
            val id: String
        )
    }
}

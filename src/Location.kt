import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.locations.KtorExperimentalLocationsAPI
import io.ktor.locations.location
import io.ktor.locations.locations
import io.ktor.routing.Route
import io.ktor.util.AttributeKey
import io.ktor.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.webSocket as ktorWebSocket

/**
 * TODO: update this to reflect the Locations api
 * Bind websocket at the current route optionally checking for websocket [protocol] (ignored if `null`)
 * Requires [WebSockets] feature to be installed first
 *
 * [DefaultWebSocketSession.incoming] will never contain any control frames and no fragmented frames could be found.
 * Default websocket implementation is handling ping/pongs, timeouts, close frames and reassembling fragmented frames
 *
 * When a websocket session is created, a [handler] lambda will be called with websocket session instance on receiver.
 * Once [handler] function returns, the websocket termination sequence will be scheduled so you shouldn't use
 * [DefaultWebSocketSession] anymore. However websocket could live for a while until close sequence completed or
 * a timeout exceeds
 */
@KtorExperimentalLocationsAPI
inline fun <reified T : Any> Route.webSocket(noinline body: suspend DefaultWebSocketServerSession.(T) -> Unit): Route {
    return location(T::class) {
        intercept(ApplicationCallPipeline.Features) {
            call.attributes.put(LocationInstanceKey, locations.resolve<T>(T::class, call))
        }
        ktorWebSocket {
            body(call.attributes[LocationInstanceKey] as T)
        }
        return@location
    }
}

@PublishedApi
internal val LocationInstanceKey = AttributeKey<Any>("LocationInstance")

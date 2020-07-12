import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.locations.KtorExperimentalLocationsAPI
import io.ktor.locations.location
import io.ktor.locations.locations
import io.ktor.routing.Route
import io.ktor.util.AttributeKey
import io.ktor.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.webSocket as ktorWebSocket

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

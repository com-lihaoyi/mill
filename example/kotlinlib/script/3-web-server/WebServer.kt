//| mvnDeps: [io.ktor:ktor-server-core:2.3.7, io.ktor:ktor-server-cio:2.3.7]
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, port = port) {
        routing {
            post("/reverse-string") {
                val body = call.receiveText()
                call.respondText(body.reversed())
            }
        }
    }.start(wait = true)
}
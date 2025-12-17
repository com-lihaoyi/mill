//| mvnDeps: [io.ktor:ktor-server-core:2.3.7, io.ktor:ktor-server-cio:2.3.7]
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(CIO, port = 8080) {
        routing {
            post("/reverse-string") {
                val body = call.receiveText()
                call.respondText(body.reversed())
            }
        }
    }.start(wait = true)
}
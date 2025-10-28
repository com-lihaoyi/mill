package example

import io.ktor.client.*
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import kotlinx.coroutines.runBlocking

class WebServerTests {
    companion object {
        private lateinit var server: ApplicationEngine
        private val client = HttpClient(ClientCIO)

        @BeforeAll
        @JvmStatic
        fun startServer() {
            server = embeddedServer(ServerCIO, port = 8080) {
                routing {
                    post("/reverse-string") {
                        val body = call.receiveText()
                        call.respondText(body.reversed())
                    }
                }
            }
            server.start(wait = false)
            Thread.sleep(1000) // Give server time to start
        }

        @AfterAll
        @JvmStatic
        fun stopServer() {
            server.stop(1000, 2000)
            client.close()
        }
    }

    @Test
    fun testReverseString() = runBlocking {
        val response = client.post("http://localhost:8080/reverse-string") {
            contentType(ContentType.Text.Plain)
            setBody("helloworld")
        }
        assertEquals(200, response.status.value)
        assertEquals("dlrowolleh", response.bodyAsText())
    }
}

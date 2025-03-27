package webapp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class WebAppTests :
    FunSpec({

        suspend fun withServer(f: suspend HttpClient.() -> Unit) {
            testApplication {
                application { WebApp.configureRoutes(this) }
                client.use { client -> f(client) }
            }
        }

        test("simpleRequest") {
            withServer {
                val response = get("/")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "What needs to be done?"
            }
        }
    })

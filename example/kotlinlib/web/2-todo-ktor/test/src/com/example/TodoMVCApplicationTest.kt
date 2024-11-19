package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.Database

class TodoMVCApplicationTest :
    FunSpec({
        suspend fun withServer(f: suspend HttpClient.() -> Unit) {
            val database = Database.connect("jdbc:h2:mem:regular;DB_CLOSE_DELAY=-1;", "org.h2.Driver")
            val repository = TodoItemRepositoryImpl(database)
            testApplication {
                application { app(repository) }
                client.use { client -> f(client) }
            }
        }
        test("Home page loads") {
            withServer {
                val response = get("/")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "<h1>todos</h1>"
            }
        }

        test("Create a new todo") {
            withServer {
                val submitFromResponse: HttpResponse =
                    submitForm(
                        url = "/save",
                        formParameters =
                        Parameters.build {
                            append("title", "Test Todo")
                        },
                    )
                val homePageResponse = get("/")
                submitFromResponse.status shouldBe HttpStatusCode.Found
                homePageResponse.bodyAsText() shouldContain "Test Todo"
            }
        }
    })

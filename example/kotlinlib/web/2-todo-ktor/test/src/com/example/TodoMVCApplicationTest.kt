package com.example

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.*
import io.ktor.client.request.forms.*
import org.jetbrains.exposed.sql.Database

class TodoMVCApplicationTest : FunSpec({
    suspend fun withServer(f: suspend HttpClient.() -> Unit): Unit {
        val database = Database.connect("jdbc:h2:mem:regular;DB_CLOSE_DELAY=-1;", "org.h2.Driver")
        val repository = TodoItemRepositoryImpl(database)
        testApplication {
            application { app(repository) }
            client.use { client -> f(client) }
        }
    }
    test("Home page loads") {withServer {
        val response = get("/")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "<h1>todos</h1>"
        }
    }

    test("Create a new todo") {
        withServer {
            val submitFromResponse: HttpResponse = submitForm(
                url = "/save",
                formParameters = Parameters.build {
                    append("title", "Test Todo")
                }
            )
            val homePageResponse = get("/")
            submitFromResponse.status shouldBe HttpStatusCode.Found
            homePageResponse.bodyAsText() shouldContain "Test Todo"
        }
    }
})

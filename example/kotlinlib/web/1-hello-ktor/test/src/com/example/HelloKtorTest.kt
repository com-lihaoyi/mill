package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*

class HelloKtorTest :
    FunSpec({
        test("HelloKtorTest") {
            testApplication {
                application { module() }
                val response = client.get("/")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "<h1>Hello, World!</h1>"
            }
        }
    })

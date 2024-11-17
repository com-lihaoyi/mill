package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class HelloKtorTest :
    FunSpec({
        test("HelloKtorTest") {
            testApplication {
                application {
                    module()
                }
                val response = client.get("/")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "<h1>Hello, World!</h1>"
            }
        }
    })

package webapp

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.footer
import kotlinx.html.head
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.title
import shared.Todo
import shared.renderBody

object WebApp {
    private val todos =
        mutableListOf(
            Todo(true, "Get started with Cask"),
            Todo(false, "Profit!"),
        )

    fun add(
        state: String,
        text: String,
    ) {
        todos.add(Todo(false, text))
    }

    fun delete(
        state: String,
        index: Int,
    ) {
        todos.removeAt(index)
    }

    fun toggle(
        state: String,
        index: Int,
    ) {
        todos[index] =
            todos[index].let {
                it.copy(checked = !it.checked)
            }
    }

    fun clearCompleted(state: String) {
        todos.removeAll { it.checked }
    }

    fun toggleAll(state: String) {
        val next = todos.any { !it.checked }
        for (item in todos.withIndex()) {
            todos[item.index] = item.value.copy(checked = next)
        }
    }

    private fun HTML.renderIndex() {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title("Template • TodoMVC")
            link(rel = "stylesheet", href = "/static/index.css")
        }
        body {
            section(classes = "todoapp") {
                renderBody(todos, "all")
            }
            footer(classes = "info") {
                p { +"Double-click to edit a todo" }
                p {
                    +"Created by "
                    a(href = "http://todomvc.com") { +"Li Haoyi" }
                }
                p {
                    +"Part of "
                    a(href = "http://todomvc.com") { +"TodoMVC" }
                }
            }
            script(src = "/static/client.js", block = {})
        }
    }

    fun configureRoutes(app: Application) {
        with(app) {
            routing {
                get("/") {
                    call.respondHtml {
                        renderIndex()
                    }
                }
                post("/toggle-all/{state}") {
                    toggleAll(call.parameters.getOrFail("state"))
                    call.respond(todos)
                }
                post("/clear-completed/{state}") {
                    clearCompleted(call.parameters.getOrFail("state"))
                    call.respond(todos)
                }
                post("/toggle/{state}/{index}") {
                    call.parameters.run {
                        toggle(getOrFail("state"), getOrFail<Int>("index"))
                        call.respond(todos)
                    }
                }
                post("/delete/{state}/{index}") {
                    call.parameters.run {
                        delete(getOrFail("state"), getOrFail<Int>("index"))
                        call.respond(todos)
                    }
                }
                post("/add/{state}") {
                    val requestText = call.receiveText()
                    add(call.parameters.getOrFail("state"), requestText)
                    call.respond(todos)
                }
                post("/list/{state}") {
                    call.respond(todos)
                }
                staticResources("/static", "webapp")
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        embeddedServer(Netty, port = 8093, host = "0.0.0.0") {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(this)
        }.start(wait = true)
    }
}

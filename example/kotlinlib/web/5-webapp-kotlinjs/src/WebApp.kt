package webapp

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.strong
import kotlinx.html.title
import kotlinx.html.ul

object WebApp {
    data class Todo(
        val checked: Boolean,
        val text: String,
    )

    private val todos =
        mutableListOf(
            Todo(true, "Get started with Cask"),
            Todo(false, "Profit!"),
        )

    private fun FlowContent.list(state: String) = renderBody(state)

    private fun FlowContent.add(
        state: String,
        text: String,
    ) {
        todos.add(Todo(false, text))
        renderBody(state)
    }

    private fun FlowContent.delete(
        state: String,
        index: Int,
    ) {
        todos.removeAt(index)
        renderBody(state)
    }

    private fun FlowContent.toggle(
        state: String,
        index: Int,
    ) {
        todos[index] =
            todos[index].let {
                it.copy(checked = !it.checked)
            }
        renderBody(state)
    }

    private fun FlowContent.clearCompleted(state: String) {
        todos.removeAll { it.checked }
        renderBody(state)
    }

    private fun FlowContent.toggleAll(state: String) {
        val next = todos.any { !it.checked }
        for (item in todos.withIndex()) {
            todos[item.index] = item.value.copy(checked = next)
        }
        renderBody(state)
    }

    private fun FlowContent.renderBody(state: String) {
        val filteredTodos =
            when (state) {
                "all" -> todos.withIndex()
                "active" -> todos.withIndex().filter { !it.value.checked }
                "completed" -> todos.withIndex().filter { it.value.checked }
                else -> throw IllegalStateException("Unknown state=$state")
            }
        div {
            header(classes = "header") {
                h1 {
                    +"todos"
                }
                input(classes = "new-todo") {
                    placeholder = "What needs to be done?"
                }
            }
            section(classes = "main") {
                input(
                    classes = "toggle-all",
                    type = InputType.checkBox,
                ) {
                    id = "toggle-all"
                    checked = todos.any { it.checked }
                }
                label {
                    htmlFor = "toggle-all"
                    +"Mark all as complete"
                }
                ul(classes = "todo-list") {
                    filteredTodos.forEach { (index, todo) ->
                        li(classes = if (todo.checked) "completed" else "") {
                            div(classes = "view") {
                                form {
                                    input(classes = "toggle", type = InputType.checkBox) {
                                        checked = todo.checked
                                        attributes["data-todo-index"] = index.toString()
                                    }
                                    label { +todo.text }
                                }
                                form {
                                    button(classes = "destroy") {
                                        attributes["data-todo-index"] = index.toString()
                                    }
                                }
                            }
                            input(classes = "edit") {
                                value = todo.text
                            }
                        }
                    }
                }
            }
            footer(classes = "footer") {
                span(classes = "todo-count") {
                    strong {
                        +todos.filter { !it.checked }.size.toString()
                    }
                    +" items left"
                }
                ul(classes = "filters") {
                    li(classes = "todo-all") {
                        a(classes = if (state == "all") "selected" else "") { +"All" }
                    }
                    li(classes = "todo-active") {
                        a(classes = if (state == "active") "selected" else "") { +"Active" }
                    }
                    li(classes = "todo-completed") {
                        a(classes = if (state == "completed") "selected" else "") { +"Completed" }
                    }
                }
                button(classes = "clear-completed") { +"Clear completed" }
            }
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
                renderBody("all")
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
                    call.respondText {
                        createHTML().div { toggleAll(call.parameters.getOrFail("state")) }
                    }
                }
                post("/clear-completed/{state}") {
                    call.respondText {
                        createHTML().div { clearCompleted(call.parameters.getOrFail("state")) }
                    }
                }
                post("/toggle/{state}/{index}") {
                    call.parameters.run {
                        call.respondText {
                            createHTML().div { toggle(getOrFail("state"), getOrFail<Int>("index")) }
                        }
                    }
                }
                post("/delete/{state}/{index}") {
                    call.parameters.run {
                        call.respondText {
                            createHTML().div { delete(getOrFail("state"), getOrFail<Int>("index")) }
                        }
                    }
                }
                post("/add/{state}") {
                    val requestText = call.receiveText()
                    call.respondText {
                        createHTML().div { add(call.parameters.getOrFail("state"), requestText) }
                    }
                }
                post("/list/{state}") {
                    call.respondText {
                        createHTML().div { list(call.parameters.getOrFail("state")) }
                    }
                }
                staticResources("/static", "webapp")
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        embeddedServer(Netty, port = 8092, host = "0.0.0.0") {
            configureRoutes(this)
        }.start(wait = true)
    }
}

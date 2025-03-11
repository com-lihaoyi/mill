package shared

import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.h1
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.ul
import kotlinx.serialization.Serializable

@Serializable
data class Todo(
    val checked: Boolean,
    val text: String,
)

fun FlowContent.renderBody(
    todos: List<Todo>,
    state: String,
) {
    val filteredTodos =
        when (state) {
            "all" -> todos.withIndex()
            "active" -> todos.withIndex().filter { !it.value.checked }
            "completed" -> todos.withIndex().filter { it.value.checked }
            else -> throw IllegalStateException("Unknown state=$state")
        }
    div {
        header(classes = "header") {
            h1 { +"todos" }
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
                            input(classes = "toggle", type = InputType.checkBox) {
                                checked = todo.checked
                                attributes["data-todo-index"] = index.toString()
                            }
                            label { +todo.text }
                            button(classes = "destroy") {
                                attributes["data-todo-index"] = index.toString()
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

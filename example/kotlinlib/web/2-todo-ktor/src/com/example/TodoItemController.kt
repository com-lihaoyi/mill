package com.example

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.thymeleaf.ThymeleafContent
import io.ktor.server.util.getOrFail
import java.util.UUID

enum class ListFilter {
    ALL,
    ACTIVE,
    COMPLETED,
}

data class TodoItemFormData(
    var title: String? = null,
)

fun modelContent(
    todos: List<TodoItem>,
    filter: ListFilter,
): Map<String, Any> {
    val activeItemCount = todos.count { !it.completed }
    val numberOfCompletedItems = todos.count { it.completed }

    val items =
        when (filter) {
            ListFilter.ALL -> todos
            ListFilter.ACTIVE -> todos.filterNot { it.completed }
            ListFilter.COMPLETED -> todos.filter { it.completed }
        }

    return mapOf(
        "item" to TodoItemFormData(),
        "todoItems" to items,
        "totalItemCount" to todos.size,
        "activeItemCount" to activeItemCount,
        "numberOfCompletedItems" to numberOfCompletedItems,
        "filter" to filter.name,
    )
}

fun Application.configureRoutes(repository: TodoItemRepository) {
    routing {
        get("/") {
            val todos = repository.findAll()
            call.respond(ThymeleafContent("index", modelContent(todos, ListFilter.ALL)))
        }
        get("/active") {
            val todos = repository.findAll()
            call.respond(ThymeleafContent("index", modelContent(todos, ListFilter.ACTIVE)))
        }
        get("/completed") {
            val todos = repository.findAll()
            call.respond(ThymeleafContent("index", modelContent(todos, ListFilter.COMPLETED)))
        }
        get("/completed/delete") {
            repository
                .findAll()
                .filter { it.completed }
                .forEach { repository.deleteById(it.id) }
            call.respondRedirect("/")
        }
        post("/save") {
            val title = call.receiveParameters().getOrFail("title")
            repository.save(TodoItem(UUID.randomUUID(), title))
            call.respondRedirect("/")
        }
        post("/{id}/delete") {
            val id = call.parameters.getOrFail("id")
            repository.deleteById(UUID.fromString(id))
            call.respondRedirect("/")
        }
        post("/{id}/toggle") {
            val id = call.parameters.getOrFail("id")
            val item = repository.findById(UUID.fromString(id))
            repository.save(item.copy(completed = !item.completed))
            call.respondRedirect("/")
        }
        post("/toggle-all") {
            repository
                .findAll()
                .map { it.copy(completed = !it.completed) }
                .forEach { repository.save(it) }
            call.respondRedirect("/")
        }
    }
}

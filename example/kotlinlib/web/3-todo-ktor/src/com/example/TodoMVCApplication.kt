package com.example

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.thymeleaf.Thymeleaf
import io.ktor.server.webjars.Webjars
import org.jetbrains.exposed.sql.Database
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

fun main(args: Array<String>) {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val database = Database.connect("jdbc:h2:mem:regular;DB_CLOSE_DELAY=-1;", "org.h2.Driver")
    val todoItemRepository = TodoItemRepositoryImpl(database)
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        app(todoItemRepository)
    }.start(wait = true)
}

fun Application.configureTemplating() {
    install(Thymeleaf) {
        setTemplateResolver(
            ClassLoaderTemplateResolver().apply {
                prefix = "templates/thymeleaf/"
                suffix = ".html"
                characterEncoding = "utf-8"
            },
        )
    }
}

fun Application.configureWebjars() {
    install(Webjars) {
        path = "/webjars" // defaults to /webjars
    }
}

fun Application.app(todoItemRepository: TodoItemRepository) {
    configureTemplating()
    configureWebjars()
    configureRoutes(todoItemRepository)
}

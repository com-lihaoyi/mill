package com.example

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.thymeleaf.*
import io.ktor.server.webjars.*
import org.jetbrains.exposed.sql.Database
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

fun main(args: Array<String>) {
    val database = Database.connect("jdbc:h2:mem:regular;DB_CLOSE_DELAY=-1;", "org.h2.Driver")
    val todoItemRepository = TodoItemRepositoryImpl(database)
    embeddedServer(Netty, port = 8091, host = "0.0.0.0"){
        app(todoItemRepository)
    }.start(wait = true)
}

fun Application.configureTemplating() {
    install(Thymeleaf) {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/thymeleaf/"
            suffix = ".html"
            characterEncoding = "utf-8"
        })
    }
}

fun Application.configureWebjars() {
    install(Webjars) {
        path = "/webjars" //defaults to /webjars
    }
}

fun Application.app(todoItemRepository: TodoItemRepository) {
    configureTemplating()
    configureWebjars()
    configureRoutes(todoItemRepository)
}

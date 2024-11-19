package com.example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
open class HelloSpringBoot

fun main(args: Array<String>) {
    runApplication<HelloSpringBoot>(*args)
}

@RestController
class HelloController {
    @GetMapping("/")
    fun hello(): String = "<h1>Hello, World!</h1>"
}

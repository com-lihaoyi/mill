package com.example.demokotlin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
class DemoKotlinApplication

fun main(args: Array<String>) {
    runApplication<DemoKotlinApplication>(*args)
}

@RestController
class HelloController {
    @GetMapping("/")
    fun hello(): String = "<h1>Hello, World!</h1>"
}

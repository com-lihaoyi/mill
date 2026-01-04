package com.example

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
open class TodomvcApplication

fun main(args: Array<String>) {
    SpringApplication.run(TodomvcApplication::class.java, *args)
}

package com.example.demokotlin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DemoKotlinApplication

fun main(args: Array<String>) {
    runApplication<DemoKotlinApplication>(*args)
}

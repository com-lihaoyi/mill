package com.example.dagger

fun main() {
    println("Random number: ${DaggerNumberApp.create().numberService().generateNumber()}")
}

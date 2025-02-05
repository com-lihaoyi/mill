package com.example.dagger

fun main() {
    println(DaggerNumberApp.create().numberService().generateNumber())
}

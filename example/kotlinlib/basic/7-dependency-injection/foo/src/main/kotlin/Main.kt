package io.vaslabs

fun main() {
    println(DaggerNumberApp.create().numberService().generateNumber())
}

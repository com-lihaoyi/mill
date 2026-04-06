package com.example.kapt

fun main() {
    println(PersonMapper.INSTANCE.toDto(Person("Mill")).name)
}

package com.example.shared

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

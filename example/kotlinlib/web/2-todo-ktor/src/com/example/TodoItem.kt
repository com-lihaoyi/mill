package com.example

import java.util.UUID

data class TodoItem(
    val id: UUID,
    val title: String,
    val completed: Boolean = false,
)

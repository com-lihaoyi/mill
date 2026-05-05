package com.example.todoitem

import org.springframework.data.jpa.repository.JpaRepository

interface TodoItemRepository : JpaRepository<TodoItem, Long> {
    fun countAllByCompleted(completed: Boolean): Int

    fun findAllByCompleted(completed: Boolean): List<TodoItem>
}

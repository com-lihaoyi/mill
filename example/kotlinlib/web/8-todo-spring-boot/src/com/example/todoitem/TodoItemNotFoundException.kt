package com.example.todoitem

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@ResponseStatus(HttpStatus.NOT_FOUND)
class TodoItemNotFoundException(itemId: Long) : RuntimeException("TodoItem itemId=$itemId not found")

package com.example.todoitem.web

import javax.validation.constraints.NotBlank

data class TodoItemFormData(
    @field:NotBlank
    var title: String = "",
)

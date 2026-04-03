package com.example.todoitem

import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.Id
import javax.validation.constraints.NotBlank

@Entity
data class TodoItem(
    @Id
    @GeneratedValue
    val id: Long? = null,

    @field:NotBlank
    var title: String = "",

    var completed: Boolean = false,
)

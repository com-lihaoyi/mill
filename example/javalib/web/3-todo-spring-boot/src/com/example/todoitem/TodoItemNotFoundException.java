package com.example.todoitem;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class TodoItemNotFoundException extends RuntimeException {
  public TodoItemNotFoundException(Long itemId) {
    super(String.format("TodoItem itemId=%s not found", itemId));
  }
}

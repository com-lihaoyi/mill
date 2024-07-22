package com.example.todoitem;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TodoItemRepository extends JpaRepository<TodoItem, Long> {
    int countAllByCompleted(boolean completed);

    List<TodoItem> findAllByCompleted(boolean completed);
}

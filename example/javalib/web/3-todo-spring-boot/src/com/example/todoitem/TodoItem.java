package com.example.todoitem;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.validation.constraints.NotBlank;

@Entity
public class TodoItem {
  @Id
  @GeneratedValue
  private Long id;

  @NotBlank
  private String title;

  private boolean completed;

  protected TodoItem() {}

  public TodoItem(String title, boolean completed) {
    this.title = title;
    this.completed = completed;
  }

  public Long getId() {
    return id;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public boolean isCompleted() {
    return completed;
  }

  public void setCompleted(boolean completed) {
    this.completed = completed;
  }
}

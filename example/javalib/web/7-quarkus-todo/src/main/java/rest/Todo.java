package rest;

// Simplified Hibernate entity with Panache
// See: https://quarkus.io/guides/hibernate-orm-panache
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;

@Entity
public class Todo extends PanacheEntity {
  public String task;
  public boolean completed;

  public Todo() {}

  public Todo(String task, boolean completed) {
    this.task = task;
    this.completed = completed;
  }
}

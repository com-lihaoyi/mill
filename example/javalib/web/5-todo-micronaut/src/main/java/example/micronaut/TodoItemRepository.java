package example.micronaut;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@JdbcRepository(dialect = Dialect.H2)
public interface TodoItemRepository extends CrudRepository<TodoItem, Long>{
    int countByCompleted(boolean completed);

    @NonNull
    List<TodoItem> findAllByCompleted(boolean completed);
    @Query("UPDATE todo_item SET completed = :completed")
    void updateCompleted(boolean completed);

    @Query("UPDATE todo_item SET completed = :completed WHERE id = :id")
    void updateCompletedById(boolean completed, Long id);
}

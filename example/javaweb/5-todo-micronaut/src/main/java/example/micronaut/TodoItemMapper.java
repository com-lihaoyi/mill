package example.micronaut;

import io.micronaut.context.annotation.Mapper;
import jakarta.inject.Singleton;

@Singleton
public interface TodoItemMapper {
    @Mapper.Mapping(to = "completed", from = "#{false}")
    TodoItem toEntity(TodoItemFormData form);
}

package example.micronaut;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(startApplication = false)
class TodoItemMapperTest {

    @Test
    void mapper(TodoItemMapper mapper) {
        TodoItem todoItem = mapper.toEntity(new TodoItemFormData() {{
            setTitle("Micronaut");
        }});
        assertNotNull(todoItem);
        assertNull(todoItem.id());
        assertEquals("Micronaut", todoItem.title());
        assertFalse(todoItem.completed());
    }

}
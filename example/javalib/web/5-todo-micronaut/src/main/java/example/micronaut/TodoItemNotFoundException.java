package example.micronaut;

public class TodoItemNotFoundException extends RuntimeException {
    public TodoItemNotFoundException(Long id) {
        super(String.format("TodoItem with id %s not found", id));
    }
}

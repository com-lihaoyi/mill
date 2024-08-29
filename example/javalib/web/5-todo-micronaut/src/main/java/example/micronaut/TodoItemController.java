package example.micronaut;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.views.ModelAndView;
import io.micronaut.views.View;
import io.micronaut.views.htmx.http.HtmxRequestHeaders;
import io.micronaut.views.htmx.http.HtmxResponseHeaders;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Controller
class TodoItemController {
    private static final String MODEL_ITEM = "item";
    private static final String MODEL_FILTER = "filter";
    private static final String MODEL_TODOS = "todos";
    private static final String MODEL_TOTAL_NUMBER_OF_ITEMS = "totalNumberOfItems";
    private static final String MODEL_NUMBER_OF_ACTIVE_ITEMS = "numberOfActiveItems";
    private static final String MODEL_NUMBER_OF_COMPLETED_ITEMS = "numberOfCompletedItems";
    private static final URI ROOT = URI.create("/");

    private final TodoItemRepository repository;
    private final TodoItemMapper todoItemMapper;

    TodoItemController(TodoItemRepository repository,
                       TodoItemMapper todoItemMapper) {
        this.repository = repository;
        this.todoItemMapper = todoItemMapper;
    }

    @View("index")
    @Get
    Map<String, Object> index() {
        return createModel(ListFilter.ALL);
    }

    @View("index")
    @Get("/active")
    Map<String, Object> indexActive() {
        return createModel(ListFilter.ACTIVE);
    }


    @Get("/completed")
    @View("index")
    Map<String, Object> indexCompleted() {
        return createModel(ListFilter.COMPLETED);
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Post("/save")
    HttpResponse<?> addNewTodoItem(@Body TodoItemFormData formData, @Nullable HtmxRequestHeaders htmxRequestHeaders) {
        TodoItem item = repository.save(todoItemMapper.toEntity(formData));
        if (htmxRequestHeaders != null) {
            return HttpResponse.ok(new ModelAndView<>("fragments :: todoItem", Collections.singletonMap(MODEL_ITEM, item)))
                    .header(HtmxResponseHeaders.HX_TRIGGER, "itemAdded");
        }
        return HttpResponse.seeOther(ROOT);
    }

    @Get("/active-items-count")
    @View("fragments :: active-items-count")
    Map<String, Object> htmxActiveItemsAcount(@NonNull HtmxRequestHeaders htmxRequestHeaders) {
        return Collections.singletonMap("numberOfActiveItems", getNumberOfActiveItems());
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Post("/{id}/toggle")
    HttpResponse<?> toggleSelection(@PathVariable Long id) {
        TodoItem todoItem = repository.findById(id)
                .orElseThrow(() -> new TodoItemNotFoundException(id));
        repository.updateCompletedById(!todoItem.completed(), id);
        return HttpResponse.seeOther(ROOT);
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Put("/{id}/toggle")
    HttpResponse<?> htmxToggleTodoItem(@PathVariable Long id, @NonNull HtmxRequestHeaders htmxRequestHeaders) {
        TodoItem todoItem = repository.findById(id)
                .orElseThrow(() -> new TodoItemNotFoundException(id));
        TodoItem updated = new TodoItem(todoItem.id(),
                todoItem.title(),
        !todoItem.completed());
        repository.updateCompletedById(updated.completed(), id);
        return HttpResponse.ok(new ModelAndView<>("fragments :: todoItem",
                            Collections.singletonMap(MODEL_ITEM, updated)))
                    .header(HtmxResponseHeaders.HX_TRIGGER, "itemCompletionToggled");
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Post("/toggle-all")
    HttpResponse<?> toggleAll() {
        repository.updateCompleted(true);
        return HttpResponse.seeOther(ROOT);
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Post("/{id}/delete")
    HttpResponse<?> deleteTodoItem(@PathVariable Long id) {
        repository.deleteById(id);
        return HttpResponse.seeOther(ROOT);
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Delete("/{id}")
    HttpResponse<?> deleteTodoItem(@PathVariable Long id,
                                   @NonNull HtmxRequestHeaders htmxRequestHeaders) {
        repository.deleteById(id);
        return HttpResponse.ok().body("").header(HtmxResponseHeaders.HX_TRIGGER, "itemDeleted");
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Post("/completed/delete")
    HttpResponse<?> deleteCompletedItems() {
        List<TodoItem> items = repository.findAllByCompleted(true);
        for (TodoItem item : items) {
            repository.deleteById(item.id());
        }
        return HttpResponse.seeOther(ROOT);
    }

    private Map<String, Object> createModel(ListFilter listFilter) {
        return Map.of(
                MODEL_ITEM, new TodoItemFormData(),
        MODEL_TODOS, getTodoItems(listFilter),
        MODEL_TOTAL_NUMBER_OF_ITEMS, repository.count(),
        MODEL_NUMBER_OF_ACTIVE_ITEMS, getNumberOfActiveItems(),
        MODEL_NUMBER_OF_COMPLETED_ITEMS, getNumberOfCompletedItems(),
        MODEL_FILTER, listFilter);
    }

    private int getNumberOfActiveItems() {
        return repository.countByCompleted(false);
    }

    private int getNumberOfCompletedItems() {
        return repository.countByCompleted(true);
    }

    private List<TodoItem> getTodoItems(ListFilter filter) {
        return switch (filter) {
            case ALL -> repository.findAll();
            case ACTIVE -> repository.findAllByCompleted(false);
            case COMPLETED -> repository.findAllByCompleted(true);
        };
    }

    public enum ListFilter {
        ALL,
        ACTIVE,
        COMPLETED
    }
}

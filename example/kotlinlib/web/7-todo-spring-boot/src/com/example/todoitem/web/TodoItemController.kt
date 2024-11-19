package com.example.todoitem.web

import com.example.todoitem.TodoItem
import com.example.todoitem.TodoItemNotFoundException
import com.example.todoitem.TodoItemRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import javax.validation.Valid

@Controller
@RequestMapping("/")
class TodoItemController(private val repository: TodoItemRepository) {

    @GetMapping
    fun index(model: Model): String {
        addAttributesForIndex(model, ListFilter.ALL)
        return "index"
    }

    @GetMapping("/active")
    fun indexActive(model: Model): String {
        addAttributesForIndex(model, ListFilter.ACTIVE)
        return "index"
    }

    @GetMapping("/completed")
    fun indexCompleted(model: Model): String {
        addAttributesForIndex(model, ListFilter.COMPLETED)
        return "index"
    }

    private fun addAttributesForIndex(model: Model, listFilter: ListFilter) {
        model.addAttribute("item", TodoItemFormData())
        model.addAttribute("todoFilter", listFilter)
        model.addAttribute("todoItems", getTodoItems(listFilter))
        model.addAttribute("totalItemCount", repository.count())
        model.addAttribute("activeItemCount", getActiveItemCount())
        model.addAttribute("completedItemCount", getCompletedItemCount())
    }

    @PostMapping
    fun addNewTodoItem(@Valid @ModelAttribute("item") formData: TodoItemFormData): String {
        repository.save(TodoItem(id = null, title = formData.title, completed = false))
        return "redirect:/"
    }

    @PutMapping("/{id}/toggle")
    fun toggleSelection(@PathVariable("id") id: Long): String {
        val todoItem = repository.findById(id).orElseThrow { TodoItemNotFoundException(id) }
        todoItem.completed = !todoItem.completed
        repository.save(todoItem)
        return "redirect:/"
    }

    @PutMapping("/toggle-all")
    fun toggleAll(): String {
        val todoItems = repository.findAll()
        todoItems.forEach { it.completed = !it.completed }
        repository.saveAll(todoItems)
        return "redirect:/"
    }

    @DeleteMapping("/{id}")
    fun deleteTodoItem(@PathVariable("id") id: Long): String {
        repository.deleteById(id)
        return "redirect:/"
    }

    @DeleteMapping("/completed")
    fun deleteCompletedItems(): String {
        val items = repository.findAllByCompleted(true)
        items.forEach { repository.deleteById(it.id) }
        return "redirect:/"
    }

    private fun getTodoItems(filter: ListFilter): List<TodoItemDto> = when (filter) {
        ListFilter.ALL -> convertToDto(repository.findAll())
        ListFilter.ACTIVE -> convertToDto(repository.findAllByCompleted(false))
        ListFilter.COMPLETED -> convertToDto(repository.findAllByCompleted(true))
    }

    private fun convertToDto(todoItems: List<TodoItem>): List<TodoItemDto> = todoItems.map {
        TodoItemDto(
            id = it.id ?: throw IllegalArgumentException("TodoItem ID cannot be null"),
            title = it.title,
            completed = it.completed,
        )
    }

    private fun getActiveItemCount(): Int = repository.countAllByCompleted(false)

    private fun getCompletedItemCount(): Int = repository.countAllByCompleted(true)

    data class TodoItemDto(val id: Long, val title: String, val completed: Boolean)

    enum class ListFilter {
        ALL,
        ACTIVE,
        COMPLETED,
    }
}

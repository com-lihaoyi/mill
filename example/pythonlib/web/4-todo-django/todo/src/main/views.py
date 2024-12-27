from django.shortcuts import render
from .models import Todo

def index(request):
    todos = Todo.objects.all()
    context = get_todo_context(todos, 'all')
    return render(request, 'base.html', context)

def list_todos(request, state):
    todos = get_filtered_todos(state)
    context = get_todo_context(todos, state)
    return render(request, 'index.html', context)

def add(request, state):
    if request.method == 'POST':
        Todo.objects.create(text=request.body.decode('utf-8'), checked=False)
    return list_todos(request, state)

def delete(request, state, index):
    todos = get_filtered_todos(state)
    todo = todos[int(index)]
    todo.delete()
    return list_todos(request, state)

def toggle(request, state, index):
    todos = get_filtered_todos(state)
    todo = todos[int(index)]
    todo.checked = not todo.checked
    todo.save()
    return list_todos(request, state)

def clear_completed(request, state):
    Todo.objects.filter(checked=True).delete()
    return list_todos(request, state)

def toggle_all(request, state):
    todos = Todo.objects.all()
    next_state = not all(todo.checked for todo in todos)
    todos.update(checked=next_state)
    return list_todos(request, state)

def edit(request, state, index):
    if request.method == 'POST':
        todos = get_filtered_todos(state)
        todo = todos[int(index)]
        todo.text = request.body.decode('utf-8')
        todo.save()
    return list_todos(request, state)

def get_filtered_todos(state):
    if state == 'active':
        return Todo.objects.filter(checked=False)
    elif state == 'completed':
        return Todo.objects.filter(checked=True)
    return Todo.objects.all()

def get_todo_context(todos, state):
    total_count = todos.count()
    active_count = todos.filter(checked=False).count()
    completed_count = todos.filter(checked=True).count()
    has_completed = completed_count > 0
    return {
        'todos': todos,
        'state': state,
        'total_count': total_count,
        'active_count': active_count,
        'completed_count': completed_count,
        'has_completed': has_completed,
    }
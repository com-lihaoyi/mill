from django.shortcuts import render, redirect, get_object_or_404
from django.contrib import messages
from .models import Task
from .forms import TaskForm


def index(request):
    tasks = Task.objects.all()
    return render(request, "index.html", {"tasks": tasks})


def add_task(request):
    if request.method == "POST":
        form = TaskForm(request.POST)
        if form.is_valid():
            form.save()
            messages.success(request, "Task added successfully!")
            return redirect("index")
        else:
            # If the form is not valid, pass the form back to the template with errors
            messages.warning(request, "Please Add Required Fields!")
            return render(request, "task.html", {"form": form, "title": "Add Task"})
    else:
        form = TaskForm()
    return render(request, "task.html", {"form": form, "title": "Add Task"})


def edit_task(request, task_id):
    task = get_object_or_404(Task, id=task_id)
    if request.method == "POST":
        form = TaskForm(request.POST, instance=task)
        if form.is_valid():
            form.save()
            messages.success(request, "Task updated successfully!")
            return redirect("index")
    else:
        form = TaskForm(instance=task)
    return render(request, "task.html", {"form": form, "title": "Edit Task"})


def delete_task(request, task_id):
    task = get_object_or_404(Task, id=task_id)
    task.delete()
    messages.success(request, "Task deleted successfully!")
    return redirect("index")

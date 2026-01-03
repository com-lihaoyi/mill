import os
from flask import Flask, render_template, request
from dataclasses import dataclass
from typing import List

app = Flask(__name__, static_folder="../static", template_folder="../templates")


@dataclass
class Todo:
    checked: bool
    text: str


todos: List[Todo] = []


@app.route("/")
def index():
    return render_template("base.html", todos=todos, state="all")


def render_body(state: str):
    filtered_todos = {
        "all": todos,
        "active": [todo for todo in todos if not todo.checked],
        "completed": [todo for todo in todos if todo.checked],
    }[state]
    return render_template("index.html", todos=filtered_todos, state=state)


def filter_todos(state):
    """Filter todos based on the state (all, active, completed)."""
    if state == "all":
        return todos
    elif state == "active":
        return [todo for todo in todos if not todo.checked]
    elif state == "completed":
        return [todo for todo in todos if todo.checked]


@app.route("/edit/<state>/<int:index>", methods=["POST"])
def edit_todo(state, index):
    """Edit the text of a todo."""
    global todos
    updated_text = request.data.decode("utf-8")
    # Update the text attribute of the Todo object
    todos[index].text = updated_text
    filtered_todos = filter_todos(state)
    return render_template("index.html", todos=filtered_todos, state=state)


@app.route("/list/<state>", methods=["POST"])
def list_todos(state):
    return render_body(state)


@app.route("/add/<state>", methods=["POST"])
def add_todo(state):
    todos.insert(0, Todo(checked=False, text=request.data.decode("utf-8")))
    return render_body(state)


@app.route("/delete/<state>/<int:index>", methods=["POST"])
def delete_todo(state, index):
    if 0 <= index < len(todos):
        todos.pop(index)
    return render_body(state)


@app.route("/toggle/<state>/<int:index>", methods=["POST"])
def toggle(state, index):
    if 0 <= index < len(todos):
        todos[index].checked = not todos[index].checked
    return render_body(state)


@app.route("/clear-completed/<state>", methods=["POST"])
def clear_completed(state):
    global todos
    todos = [todo for todo in todos if not todo.checked]
    return render_body(state)


@app.route("/toggle-all/<state>", methods=["POST"])
def toggle_all(state):
    global todos

    all_checked = all(todo.checked for todo in todos)
    for todo in todos:
        todo.checked = not all_checked

    return render_body(state)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(debug=True, port=port)

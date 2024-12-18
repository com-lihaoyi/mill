from flask import Flask, render_template, redirect, url_for, flash, request
from flask_sqlalchemy import SQLAlchemy
from flask_wtf import FlaskForm
from wtforms import StringField, TextAreaField, SelectField, DateField, SubmitField
from wtforms.validators import DataRequired, Length

# Initialize Flask App and Database
app = Flask(__name__, static_folder="../static", template_folder="../templates")
app.config["SQLALCHEMY_DATABASE_URI"] = "sqlite:///todo.db"
app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False
app.config["SECRET_KEY"] = (
    "8f41b7124eec1c73f2fbe77e6e76c54602a40c44c842da93b09f48b79c023c88"
)

# Import models
from models import Task, db

# Import forms
from forms import TaskForm

db.init_app(app)


# Routes
@app.route("/")
def index():
    tasks = Task.query.all()
    return render_template("index.html", tasks=tasks)


@app.route("/add", methods=["GET", "POST"])
def add_task():
    form = TaskForm()
    if form.validate_on_submit():
        new_task = Task(
            title=form.title.data,
            description=form.description.data,
            status=form.status.data,
            deadline=form.deadline.data,
        )
        db.session.add(new_task)
        db.session.commit()
        flash("Task added successfully!", "success")
        return redirect(url_for("index"))
    return render_template("task.html", form=form, title="Add Task")


@app.route("/edit/<int:task_id>", methods=["GET", "POST"])
def edit_task(task_id):
    task = db.session.get(Task, task_id)
    if not task:  # Handle case where task doesn't exist
        flash("Task not found.", "error")
        return redirect(url_for("index"))
    form = TaskForm(obj=task)
    if form.validate_on_submit():
        task.title = form.title.data
        task.description = form.description.data
        task.status = form.status.data
        task.deadline = form.deadline.data
        db.session.commit()
        flash("Task updated successfully!", "success")
        return redirect(url_for("index"))
    return render_template("task.html", form=form, title="Edit Task")


@app.route("/delete/<int:task_id>")
def delete_task(task_id):
    task = db.session.get(Task, task_id)
    if not task:  # Handle case where task doesn't exist
        flash("Task not found.", "error")
        return redirect(url_for("index"))
    db.session.delete(task)
    db.session.commit()
    flash("Task deleted successfully!", "success")
    return redirect(url_for("index"))


# Create database tables and run the app
if __name__ == "__main__":
    with app.app_context():
        db.create_all()
    app.run(debug=True, port=5001)

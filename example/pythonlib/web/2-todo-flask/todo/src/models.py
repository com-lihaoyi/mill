from flask_sqlalchemy import SQLAlchemy

db = SQLAlchemy()

class Task(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    title = db.Column(db.String(100), nullable=False)
    description = db.Column(db.Text, nullable=True)
    status = db.Column(db.String(20), default='Pending')  # Options: Pending, Completed
    created_at = db.Column(db.DateTime, default=db.func.current_timestamp())
    deadline = db.Column(db.Date)

    def __repr__(self):
        return f'<Task {self.title}>'

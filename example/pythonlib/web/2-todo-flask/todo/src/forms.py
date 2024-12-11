from flask_wtf import FlaskForm
from wtforms import StringField, TextAreaField, SelectField, DateField, SubmitField
from wtforms.validators import DataRequired, Length


class TaskForm(FlaskForm):
    title = StringField("Title", validators=[DataRequired(), Length(max=100)])
    description = TextAreaField("Description")
    status = SelectField(
        "Status", choices=[("Pending", "Pending"), ("Completed", "Completed")]
    )
    deadline = DateField("Deadline", format="%Y-%m-%d", validators=[DataRequired()])
    submit = SubmitField("Save")

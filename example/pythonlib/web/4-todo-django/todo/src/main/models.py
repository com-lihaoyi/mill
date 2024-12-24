from django.db import models

class Todo(models.Model):
    text = models.CharField(max_length=255)
    checked = models.BooleanField(default=False)
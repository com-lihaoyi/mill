from django.test import TestCase
from django.urls import reverse, resolve
from django.contrib.messages import get_messages
from .models import Task
from .views import index, add_task, edit_task, delete_task


class TestScript(TestCase):
    """
    Unit and Integration tests.
    """

    def test_str_method(self):
        """
        Test the __str__ method of the Task model.
        """
        task = Task.objects.create(
            title="Buy groceries", description="Get milk, eggs, and bread"
        )
        self.assertEqual(str(task), "Buy groceries")

    def test_default_status(self):
        """
        Test that the default status of a task is 'Pending'.
        """
        task = Task.objects.create(title="Finish project")
        self.assertEqual(task.status, "Pending")

    def test_add_task_view_get(self):
        """
        Test that the 'Add Task' page loads successfully with an empty form.
        """
        url = reverse("add_task")
        response = self.client.get(url)
        self.assertEqual(response.status_code, 200)
        self.assertTemplateUsed(response, "task.html")
        self.assertContains(response, "Add Task")

    def test_add_task_view_post_valid_data(self):
        """
        Test that submitting the form with valid data creates a new task.
        """
        url = reverse("add_task")
        data = {
            "title": "Test Task",
            "description": "This is a test task description.",
            "status": "Pending",
            "deadline": "2024-12-31",
        }
        response = self.client.post(url, data)

        # Check that the task was added and the user is redirected
        self.assertEqual(response.status_code, 302)
        self.assertTrue(Task.objects.filter(title="Test Task").exists())

    def test_add_task_view_post_invalid_data(self):
        """
        Test that submitting the form with invalid data shows errors.
        """
        url = reverse("add_task")
        data = {
            "title": "",
            "description": "No title task",
            "status": "Pending",
            "deadline": "2024-12-31",
        }  # Invalid title
        response = self.client.post(url, data)
        messages = list(get_messages(response.wsgi_request))

        # Ensure that the form is not saved and the page reloads with errors
        self.assertEqual(response.status_code, 200)
        self.assertEqual(str(messages[0]), "Please Add Required Fields!")

    def test_edit_task_view_post_valid_data(self):
        """
        Test that editing a task updates its details.
        """
        task = Task.objects.create(title="Old Task", description="Old description")
        url = reverse("edit_task", args=[task.id])
        data = {
            "title": "Updated Task",
            "description": "Updated description",
            "status": "Completed",
            "deadline": "2024-12-31",
        }

        response = self.client.post(url, data)

        # Verify that the task was updated and the user is redirected
        self.assertEqual(response.status_code, 302)
        task.refresh_from_db()
        self.assertEqual(task.title, "Updated Task")
        self.assertEqual(task.description, "Updated description")

    def test_delete_task_view(self):
        """
        Test that deleting a task works and redirects to the task list.
        """
        task = Task.objects.create(
            title="Task to delete", description="This task will be deleted"
        )
        url = reverse("delete_task", args=[task.id])

        response = self.client.post(url)

        # Ensure the task is deleted and the user is redirected
        self.assertEqual(response.status_code, 302)
        self.assertFalse(Task.objects.filter(title="Task to delete").exists())

    def test_url_resolves_to_correct_view(self):
        """
        Test that the correct view function is mapped to each URL.
        """
        # Mapping URLs to their respective views
        url_resolutions = {
            reverse("index"): index,
            reverse("add_task"): add_task,
            reverse("edit_task", args=[1]): edit_task,
            reverse("delete_task", args=[1]): delete_task,
        }

        for url, view in url_resolutions.items():
            with self.subTest(url=url):
                resolved = resolve(url)
                self.assertEqual(resolved.func, view)

    def test_add_task_message(self):
        """
        Test that a success message appears when a task is successfully added.
        """
        url = reverse("add_task")
        data = {
            "title": "Complete Assignment",
            "description": "Finish Django tutorial",
            "status": "Pending",
            "deadline": "2024-12-31",
        }
        response = self.client.post(url, data)

        # Check if the success message appears in the response
        messages = list(get_messages(response.wsgi_request))
        self.assertEqual(str(messages[0]), "Task added successfully!")

    def test_delete_task_message(self):
        """
        Test that a success message appears when a task is deleted.
        """
        task = Task.objects.create(
            title="Task to delete", description="Delete this task"
        )
        url = reverse("delete_task", args=[task.id])
        response = self.client.post(url)

        # Check if the success message appears
        messages = list(get_messages(response.wsgi_request))
        self.assertEqual(str(messages[0]), "Task deleted successfully!")

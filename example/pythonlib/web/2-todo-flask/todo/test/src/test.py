import unittest
from app import app, todos, Todo


class TestTodoApp(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Set up the test client for the app
        cls.client = app.test_client()

    def setUp(self):
        # Clear the todos list before each test
        global todos
        todos.clear()

    def test_add_todo(self):
        # Add a todo item
        response = self.client.post("/add/all", data="Test Todo")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(todos), 1)
        self.assertEqual(todos[0].text, "Test Todo")

    def test_toggle_todo(self):
        # Add a todo item and toggle it
        todos.append(Todo(checked=False, text="Test Todo"))
        response = self.client.post("/toggle/all/0", data="")
        self.assertEqual(response.status_code, 200)
        self.assertTrue(todos[0].checked)

    def test_edit_todo(self):
        # Add a todo item and edit it
        todos.append(Todo(checked=False, text="Test Todo"))
        response = self.client.post("/edit/all/0", data="Updated Todo")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(todos[0].text, "Updated Todo")

    def test_delete_todo(self):
        # Add a todo item and delete it
        todos.append(Todo(checked=False, text="Test Todo"))
        response = self.client.post("/delete/all/0", data="")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(todos), 0)

    def test_toggle_all(self):
        # Add todos and toggle all
        todos.append(Todo(checked=False, text="Todo 1"))
        todos.append(Todo(checked=False, text="Todo 2"))
        response = self.client.post("/toggle-all/all", data="")
        self.assertEqual(response.status_code, 200)
        self.assertTrue(all(todo.checked for todo in todos))

    def test_filter_todos(self):
        # Add todos and test the filter functionality
        todos.append(Todo(checked=False, text="Active Todo"))
        todos.append(Todo(checked=True, text="Completed Todo"))
        response = self.client.post("/list/active", data="")
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"Active Todo", response.data)
        self.assertNotIn(b"Completed Todo", response.data)
        self.assertEqual(len(todos), 2)  # Should still have 2 todos


if __name__ == "__main__":
    unittest.main()

import unittest
from app import app, todos


class TestTodoAppIntegration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Set up the test client for the app
        cls.client = app.test_client()

    def setUp(self):
        # Clear the todos list before each test
        global todos
        todos.clear()

    def test_add_and_list_todos(self):
        # Test adding a todo and listing all todos
        response = self.client.post("/add/all", data="Test Todo")
        self.assertEqual(response.status_code, 200)
        # Fetch the todos list and verify the item was added
        response = self.client.post("/list/all", data="")
        self.assertIn(b"Test Todo", response.data)

    def test_toggle_and_list_todos(self):
        # Test adding a todo, toggling it, and listing active/completed todos
        self.client.post("/add/all", data="Test Todo")
        response = self.client.post("/toggle/all/0", data="")
        # Check if the todo is toggled
        self.assertEqual(response.status_code, 200)
        # Now, test filtering todos based on active/completed state
        response = self.client.post("/list/active", data="")
        self.assertNotIn(b"Test Todo", response.data)
        response = self.client.post("/list/completed", data="")
        self.assertIn(b"Test Todo", response.data)

    def test_edit_and_list_todos(self):
        # Test adding a todo, editing it, and then verifying the updated text
        self.client.post("/add/all", data="Test Todo")
        response = self.client.post("/edit/all/0", data="Updated Todo")
        # Check that the todo was updated
        response = self.client.post("/list/all", data="")
        self.assertIn(b"Updated Todo", response.data)
        self.assertNotIn(b"Test Todo", response.data)

    def test_delete_todo(self):
        # Test adding and deleting a todo
        self.client.post("/add/all", data="Test Todo")
        response = self.client.post("/delete/all/0", data="")
        # Verify that the todo was deleted
        response = self.client.post("/list/all", data="")
        self.assertNotIn(b"Test Todo", response.data)

    def test_toggle_all_todos(self):
        # Test toggling all todos
        self.client.post("/add/all", data="Todo 1")
        self.client.post("/add/all", data="Todo 2")
        response = self.client.post("/toggle-all/all", data="")
        response = self.client.post("/list/completed", data="")
        self.assertIn(b"Todo 1", response.data)
        self.assertIn(b"Todo 2", response.data)
        response = self.client.post("/toggle-all/all", data="")
        response = self.client.post("/list/active", data="")
        self.assertIn(b"Todo 1", response.data)
        self.assertIn(b"Todo 2", response.data)


if __name__ == "__main__":
    unittest.main()

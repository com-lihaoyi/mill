import unittest
from app import app, db, Task
from datetime import date

class IntegrationTest(unittest.TestCase):
    def setUp(self):
        """Set up the test client and initialize the in-memory database."""
        app.config['TESTING'] = True
        app.config['SQLALCHEMY_DATABASE_URI'] = "sqlite:///:memory:"
        # Disable CSRF for testing
        app.config['WTF_CSRF_ENABLED'] = False
        self.client = app.test_client()
        with app.app_context():
            db.create_all()

    def tearDown(self):
        """Clean up the database after each test."""
        with app.app_context():
            db.session.remove()
            db.drop_all()

    def test_index_empty(self):
        """Test the index route with no tasks."""
        response = self.client.get("/")
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"No tasks found", response.data)
    
    def test_add_task(self):
        """Test adding a task through the /add route."""

        # Simulate a POST request with valid form data
        response = self.client.post("/add", data={
            "title": "Test Task",
            "description": "This is a test task description.",
            "status": "Pending",
            "deadline": "2024-12-31"
        }, follow_redirects=True)

        # Check if the response is successful
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"Task added successfully!", response.data)

        # Verify the task was added to the database
        with app.app_context():
            task = Task.query.first()  # Retrieve the first task in the database
            self.assertIsNotNone(task, "Task was not added to the database.")
            self.assertEqual(task.title, "Test Task")
            self.assertEqual(task.description, "This is a test task description.")
            self.assertEqual(task.status, "Pending")
            self.assertEqual(str(task.deadline), "2024-12-31")

    def test_edit_task(self):
        """Test editing a task through the /edit/<int:task_id> route."""

        # Prepopulate the database with a task
        with app.app_context():
            new_task = Task(
                title="Original Task",
                description="Original description.",
                status="Pending",
                deadline=date(2024, 12, 31)
            )
            db.session.add(new_task)
            db.session.commit()
            task_id = new_task.id  # Get the ID of the newly added task

        # Simulate a POST request to edit the task
        response = self.client.post(f"/edit/{task_id}", data={
            "title": "Updated Task",
            "description": "Updated description.",
            "status": "Completed",
            "deadline": "2025-01-15"
        }, follow_redirects=True)

        # Check if the response is successful
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"Task updated successfully!", response.data)

        # Verify the task was updated in the database
        with app.app_context():
            task = db.session.get(Task, task_id)
            self.assertIsNotNone(task, "Task not found in the database after edit.")
            self.assertEqual(task.title, "Updated Task")
            self.assertEqual(task.description, "Updated description.")
            self.assertEqual(task.status, "Completed")
            self.assertEqual(str(task.deadline), "2025-01-15")
            
            # Test editing a non-existent task
            non_existent_id = 9999
            response = self.client.get(f"/edit/{non_existent_id}", follow_redirects=True)
            self.assertEqual(response.status_code, 200)
            self.assertIn(b"Task not found.", response.data)

    def test_delete_task(self):
        with app.app_context():  # Ensure application context is active
            # Add a task to delete
            task = Task(title="Test Task")
            db.session.add(task)
            db.session.commit()
            task_id = task.id

            # Test deleting an existing task
            response = self.client.get(f"/delete/{task_id}", follow_redirects=True)
            self.assertEqual(response.status_code, 200)
            self.assertIn(b"Task deleted successfully!", response.data)
            self.assertIsNone(db.session.get(Task, task_id))

            # Test deleting a non-existent task
            non_existent_id = 9999
            response = self.client.get(f"/delete/{non_existent_id}", follow_redirects=True)
            self.assertEqual(response.status_code, 200)
            self.assertIn(b"Task not found.", response.data)


if __name__ == "__main__":
    unittest.main()

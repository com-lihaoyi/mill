import unittest
from app import Task  # type: ignore


class UnitTest(unittest.TestCase):
    def test_task_creation(self):
        task = Task(
            title="Sample Task", description="Task Description", status="Pending"
        )
        self.assertEqual(task.title, "Sample Task")
        self.assertEqual(task.description, "Task Description")
        self.assertEqual(task.status, "Pending")

    def test_task_status_update(self):
        task = Task(
            title="Sample Task", description="Task Description", status="Pending"
        )
        task.status = "Completed"
        self.assertEqual(task.status, "Completed")


if __name__ == "__main__":
    unittest.main()

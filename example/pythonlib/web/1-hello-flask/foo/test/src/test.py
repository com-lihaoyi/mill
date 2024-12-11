import unittest
from foo import app  # type: ignore


class TestScript(unittest.TestCase):
    def setUp(self):
        """Set up the test client before each test."""
        self.app = app.test_client()  # Initialize the test client
        self.app.testing = True  # Enable testing mode for better error handling

    def test_hello_flask(self):
        """Test the '/' endpoint."""
        response = self.app.get("/")  # Simulate a GET request to the root endpoint
        self.assertEqual(response.status_code, 200)  # Check the HTTP status code
        self.assertIn(
            b"Hello, Mill!", response.data
        )  # Check if the response contains the expected text


if __name__ == "__main__":
    unittest.main()

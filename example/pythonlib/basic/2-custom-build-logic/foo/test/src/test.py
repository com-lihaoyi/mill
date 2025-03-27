import unittest
from foo import line_count  # type: ignore


class TestScript(unittest.TestCase):
    def test_line_count(self) -> None:
        expected_line_count = 10
        # Check if the line count matches the expected value
        self.assertEqual(line_count(), expected_line_count)


if __name__ == "__main__":
    unittest.main()

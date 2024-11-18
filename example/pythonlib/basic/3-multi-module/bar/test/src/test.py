import unittest
from bar.src.bar import combine


class TestScript(unittest.TestCase):
    def test_bar(self) -> None:
        expected_line = "Hello BAR"
        self.assertEqual(combine("BAR"), expected_line)


if __name__ == "__main__":
    unittest.main()

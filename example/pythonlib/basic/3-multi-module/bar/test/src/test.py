import unittest
from bar import generate_html


class TestScript(unittest.TestCase):
    def test_simple(self) -> None:
        expected_line = "<h1>bar</h1>"
        self.assertEqual(generate_html("bar"), expected_line)

    def test_escaping(self) -> None:
        expected_line = "<h1>&lt;bar&gt;</h1>"
        self.assertEqual(generate_html("<bar>"), expected_line)

if __name__ == "__main__":
    unittest.main()
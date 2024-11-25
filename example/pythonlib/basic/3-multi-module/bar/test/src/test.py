import unittest
from bar import generate_html  # type: ignore


class TestScript(unittest.TestCase):
    def test_simple(self) -> None:
        expected_line = "<h1>world</h1>"
        self.assertEqual(generate_html("world"), expected_line)

    def test_escaping(self) -> None:
        expected_line = "<h1>&lt;world&gt;</h1>"
        self.assertEqual(generate_html("<world>"), expected_line)


if __name__ == "__main__":
    unittest.main()

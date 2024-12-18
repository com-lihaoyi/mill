import unittest
from foo import main  # type: ignore


class TestScript(unittest.TestCase):
    def test_main(self) -> None:
        expected_line = "Foo.value: hello\nBar.value: <h1>world</h1>"
        self.assertEqual(main("hello", "world"), expected_line)


if __name__ == "__main__":
    unittest.main()

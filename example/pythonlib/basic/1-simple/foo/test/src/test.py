import unittest
from markupsafe import escape
from foo import generate_html


class TestScript(unittest.TestCase):
    def test_simple(self):
        self.assertEqual(generate_html("hello"), "<h1>hello</h1>")

    def test_escaping(self):
        escaped_text = escape("<hello>")
        self.assertEqual(generate_html("&lt;hello&gt;"), f"<h1>{escaped_text}</h1>")


if __name__ == "__main__":
    unittest.main()

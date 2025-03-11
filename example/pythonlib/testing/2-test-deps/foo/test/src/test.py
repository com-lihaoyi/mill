import unittest
from markupsafe import escape
from foo import get_value  # type: ignore
from test_utils import BarTestUtils  # type: ignore


class TestScript(unittest.TestCase):
    def test_equal_string(self) -> None:
        escaped_text = escape("<XYZ>")
        BarTestUtils().bar_assert_equals(
            f"<h1>{escaped_text}</h1>", get_value(text="&lt;XYZ&gt;")
        )

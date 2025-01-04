import unittest
from foo import get_value  # type: ignore
from test_utils import BarTestUtils  # type: ignore


class TestScript(unittest.TestCase):
    def test_equal_string(self) -> None:
        BarTestUtils().bar_assert_equals("XYZ".lower(), get_value())

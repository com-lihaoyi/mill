import unittest
import statistics
from bar import get_value  # type: ignore
from testUtils import BarTestUtils  # type: ignore


class TestScript(unittest.TestCase):
    def test_mean(self) -> None:
        BarTestUtils().bar_assert_equals(get_value(), statistics.mean([122, 124]))

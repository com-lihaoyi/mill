import pyfiglet
from termcolor import colored
import unittest
from foo import get_value  # type: ignore
from test_utils import BarTestUtils  # type: ignore


class TestScript(unittest.TestCase):
    def test_equal_string(self) -> None:
        ascii_art = pyfiglet.figlet_format("XYZ")
        colored_art = colored(ascii_art, "cyan")
        BarTestUtils().bar_assert_equals(colored_art, get_value())

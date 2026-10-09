import unittest

from .helper import add


class HelperTests(unittest.TestCase):
    def test_add(self) -> None:
        self.assertEqual(add(1, 2), 3)

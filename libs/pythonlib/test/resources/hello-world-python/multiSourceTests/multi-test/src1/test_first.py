import unittest


class FirstTest(unittest.TestCase):
    def test_first(self) -> None:
        self.assertEqual(1 + 1, 2)

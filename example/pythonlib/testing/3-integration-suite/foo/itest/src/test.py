import unittest
from foo import Foo  # type: ignore


class TestScript(unittest.TestCase):
    def test_hello_world(self) -> None:
        result = Foo().main()
        self.assertEqual("Hello World", result)

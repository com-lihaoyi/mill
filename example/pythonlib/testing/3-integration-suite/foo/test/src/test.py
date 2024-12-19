import unittest
from foo import Foo  # type: ignore


class TestScript(unittest.TestCase):
    def test_hello(self) -> None:
        result = Foo().main()
        self.assertTrue(result.startswith("Hello"))

    def test_world(self) -> None:
        result = Foo().main()
        self.assertTrue(result.endswith("World"))

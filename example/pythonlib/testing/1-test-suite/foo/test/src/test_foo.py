import unittest
from unittest.mock import MagicMock
from foo import Foo  # type: ignore


class TestScript(unittest.TestCase):
    def test_hello(self) -> None:
        result = Foo().main()
        self.assertTrue(result.startswith("Hello"))

    def test_world(self) -> None:
        result = Foo().main()
        self.assertTrue(result.endswith("World"))

    def test_mock(self):
        mock_foo = MagicMock(spec=Foo)
        mock_foo.main.return_value = "Hello Mockito World"
        result = mock_foo.main()
        self.assertEqual(
            result,
            "Hello Mockito World",
            "Mocked hello() did not return expected value",
        )
        mock_foo.main.assert_called_once()

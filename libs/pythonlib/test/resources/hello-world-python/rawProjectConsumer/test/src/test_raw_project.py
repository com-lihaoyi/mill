import unittest

from raw_uv_project import raw_message


class RawProjectTests(unittest.TestCase):
    def test_dependency_is_inherited(self) -> None:
        self.assertEqual(raw_message(), "Hello from a raw uv project!")

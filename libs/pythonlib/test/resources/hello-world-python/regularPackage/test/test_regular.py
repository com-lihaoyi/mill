import unittest

from regularPackage import regular_message
from .helper import expected_message


class RegularPackageTests(unittest.TestCase):
    def test_bare_suite_below_regular_package(self) -> None:
        self.assertEqual(regular_message(), expected_message())

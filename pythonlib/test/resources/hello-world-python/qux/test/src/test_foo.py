import unittest

import bar;

class TestStringMethods(unittest.TestCase):

    def test_upper(self):
        self.assertEqual('foo'.upper(), 'FOO')

    def test_bar(self):
        self.assertEqual(bar.bar_val, 42)

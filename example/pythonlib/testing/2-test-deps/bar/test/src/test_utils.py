class BarTestUtils:
    def bar_assert_equals(self, a, b):
        print("Using BarTestUtils.bar_assert_equals")
        assert a == b, f"Expected {b} but got {a}"

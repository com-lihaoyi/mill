package baz

object BazTestUtils {
    fun bazAssertEquals(
        x: Any,
        y: Any,
    ) {
        println("Using BazTestUtils.bazAssertEquals")
        if (x != y) {
            throw AssertionError("Expected $y, but got $x")
        }
    }
}

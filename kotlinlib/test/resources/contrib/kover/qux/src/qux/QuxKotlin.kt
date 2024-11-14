package qux

fun action(
    one: Boolean,
    two: Boolean,
): String =
    if (one) {
        if (two) {
            "one, two"
        } else {
            "one"
        }
    } else {
        if (two) {
            "two"
        } else {
            "nothing"
        }
    }

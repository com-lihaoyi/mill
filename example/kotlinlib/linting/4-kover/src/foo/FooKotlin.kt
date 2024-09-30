package foo

fun action(one: Boolean, two: Boolean): String {
    return if (one) {
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
}

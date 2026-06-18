package repro

sealed interface Foo {
    data object Bar : Foo
}

// You can also take an existing cross module and extend it with additional cross
// axes as shown:
import mill._

object foo extends Cross[FooModule]("a", "b")
trait FooModule extends Cross.Module[String] {
  def param1 = T { "Param Value: " + crossValue }
}

object foo2 extends Cross[FooModule2](("a", 1), ("b", 2))
trait FooModule2 extends Cross.Module2[String, Int] {
  def param1 = T { "Param Value: " + crossValue }
  def param2 = T { "Param Value: " + crossValue2 }
}

object foo3 extends Cross[FooModule3](("a", 1, true), ("b", 2, false))
trait FooModule3 extends FooModule2 with Cross.Module3[String, Int, Boolean] {
  def param3 = T{ "Param Value: " + crossValue3 }
}

// Starting from an existing cross module with `Cross.Module{N-1}`,
// you can extend `Cross.ModuleN` to add a new axis to it.
//
// Multi-axis cross modules take their input as tuples, and each element of the
// tuple beyond the first is bound to the `crossValueN` property defined by the
// corresponding `Cross.ArgN` trait. Providing tuples of the wrong arity to the
// `Cross[]` constructor is a compile error.
//
// The Cross module's axes can take any type `T` with `Cross.ToSegments[T]`
// defined. There are default implementations for strings, chars, numbers,
// booleans, and lists; the example above demonstrates cross axes of type
// `String`, `Int`, and `Boolean`. You can define additional `ToPathSegments`
// for your own user-defined types that you wish to use in a Cross module

/** Usage

> mill show foo[a].param1
"Param Value: a"

> mill show foo[b].param1
"Param Value: b"

> mill show foo2[a,1].param1
"Param Value: a"

> mill show foo2[b,2].param2
"Param Value: 2"

> mill show foo3[b,2,false].param3
"Param Value: false"

> sed -i.bak 's/, true//g' build.sc

> sed -i.bak 's/, false//g' build.sc

> mill show foo3[b,2,false].param3
error: ...object foo3 extends Cross[FooModule3](("a", 1), ("b", 2))
error: ...                                      ^
error: ...value _3 is not a member of (String, Int)
*/
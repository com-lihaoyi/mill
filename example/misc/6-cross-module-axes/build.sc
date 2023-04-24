import mill.api.PathRef
import mill.{Agg, T}
import mill.define.{Cross, Module}
import mill.scalalib._

object myCross extends Cross[MyCrossModule]("a", "b")
trait MyCrossModule extends Cross.Module[String] {
  def param1 = T { "Param Value: " + crossValue }
}

object myCross2 extends Cross[MyCrossModule2](("a", 1), ("b", 2))
trait MyCrossModule2 extends Cross.Module2[String, Int] {
  def param1 = T { "Param Value: " + crossValue }
  def param2 = T { "Param Value: " + crossValue2 }
}

object myCrossExtended extends Cross[MyCrossModuleExtended](("a", 1, true), ("b", 2, false))
trait MyCrossModuleExtended extends MyCrossModule2 with Cross.Module3[String, Int, Boolean] {
  def param3 = T{ "Param Value: " + crossValue3 }
}

// Cross modules can have multiple axes. You can define a cross module with N
// axes via the `Cross.ModuleN` trait, or you can take an existing cross module
// with `Cross.Module[N-1]` and extend `Cross.ModuleN` to add a new axis to it.
//
// Multi-axis cross modules take their input as tuples, and each element of the
// tuple beyond the first is bound to the `crossValueN` property defined by the
// corresponding `Cross.ArgN` trait. Providing tuples of the wrong arity to the
// `Cross[]` constructor is a compilation error
//
// The Cross module's axes can take any type `T` with `Cross.ToSegments[T]`
// defined. There are default implementations for strings, chars, numbers,
// booleans, and lists; the example above demonstrates cross axes of type
// `String`, `Int`, and `Boolean`. You can define additional `ToPathSegments`
// for your own user-defined types that you wish to use in a Cross module

/* Example Usage

> ./mill show myCross[a].param1
"Param Value: a"

> ./mill show myCross[b].param1
"Param Value: b"

> ./mill show myCross2[a,1].param1
"Param Value: a"

> ./mill show myCross2[b,2].param2
"Param Value: 2"

> ./mill show myCrossExtended[b,2,false].param3
"Param Value: false"

> sed -i 's/, true//g' build.sc

> sed -i 's/, false//g' build.sc

> ./mill show myCrossExtended[b,2,false].param3
error: object myCrossExtended extends Cross[MyCrossModuleExtended](("a", 1), ("b", 2))
error:                                                              ^
error: value _3 is not a member of (String, Int)
*/
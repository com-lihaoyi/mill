import mill._

object foo extends Cross[FooModule]("2.10", "2.11", "2.12")
trait FooModule extends Cross.Module[String] {
  def suffix = T { crossValue }
}

def bar = T { s"hello ${foo("2.10").suffix()}" }

def qux = T { s"hello ${foo("2.10").suffix()} world ${foo("2.12").suffix()}" }

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
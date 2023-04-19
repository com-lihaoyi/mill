import mill._

object myCross extends Cross[MyCrossModule]("a", "b")
trait MyCrossModule extends Cross.Module[String] {

  object foo extends InnerCrossModule{
    def param1 = T { "Param Value: " + crossValue }
  }
  object bar extends InnerCrossModule{
    def param3 = T{ "Param Value: " + crossValue3 }
  }
}

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
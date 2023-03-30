import mill._, scalalib._

val scalaVersions = Seq("2.12.17", "2.13.10", "3.2.2")

object foo extends Cross[FooModule](scalaVersions:_*)
class FooModule(val crossScalaVersion: String) extends CrossScalaModule

object bar extends Cross[FooModule](scalaVersions:_*)
class BarModule(val crossScalaVersion: String) extends CrossScalaModule

/* Example Usage

> ./mill resolve __.run
foo[2.12.17].run
foo[2.13.10].run
foo[3.2.2].run
bar[2.12.17].run
bar[2.13.10].run
bar[3.2.2].run

> ./mill foo[2.12.17].run
Foo.value: Hello World Scala library version 2.12.17
Specific code for Scala 2.x
Specific code for Scala 2.12.x

> ./mill foo[3.2.2].run
Specific code for Scala 3.x
Specific code for Scala 3.2.x

> ./mill bar[3.2.2].run
Bar.value: bar-value

*/
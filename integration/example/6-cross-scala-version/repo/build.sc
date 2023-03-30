import mill._, scalalib._

val scalaVersions = Seq("2.12.17", "2.13.10", "3.2.2")

object foo extends Cross[FooModule](scalaVersions:_*)
class FooModule(val crossScalaVersion: String) extends CrossScalaModule

object bar extends Cross[FooModule](scalaVersions:_*)
class BarModule(val crossScalaVersion: String) extends CrossScalaModule

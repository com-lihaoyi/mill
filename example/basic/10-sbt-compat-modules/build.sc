// == SBT-Compatible Modules
import mill._, scalalib._

object foo extends SbtModule {
  def scalaVersion = "2.13.8"
  object test extends Tests {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.11")
    def testFramework = "utest.runner.Framework"
  }
}


object bar extends Cross[BarModule]("2.12.17", "2.13.8")
trait BarModule extends CrossSbtModule{
  object test extends Tests {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.11")

    def testFramework = "utest.runner.Framework"
  }
}


// `SbtModule`/`CrossSbtModule` are variants of `ScalaModule`/`CrossScalaModule`
// that use the more verbose folder layout of SBT, Maven, and other tools:
//
// - `foo/src/main/scala`
// - `foo/src/main/scala-2.12`
// - `foo/src/main/scala-2.13`
// - `foo/src/test/scala`
//
// Rather than Mill's
//
// - `foo/src`
// - `foo/src-2.12`
// - `foo/src-2.13`
// - `foo/test/src`
//
// This is especially useful during migrations, where a particular module may
// be built using both SBT and Mill at the same time

/* Example Usage

> ./mill foo.compile
compiling 1 Scala source

> ./mill foo.test.compile
compiling 1 Scala source

> ./mill foo.test.test
+ foo.FooTests.hello

> ./mill foo.test
+ foo.FooTests.hello

> ./mill bar[2.13.8].run
Hello World Scala library version 2.13.8

> ./mill bar[2.12.17].run
Hello World Scala library version 2.12.17

*/
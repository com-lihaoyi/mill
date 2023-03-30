import mill._, scalalib._

object foo extends BuildModule with ScalaModule {
  def scalaVersion = "2.13.2"
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.8.2")
}

/* Example Usage

> ./mill compile
compiling 1 Scala source

> ./mill run
Foo.value: <h1>hello</h1>

*/
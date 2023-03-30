import mill._, scalalib._

object foo extends BuildModule with ScalaModule {
  def scalaVersion = "2.13.2"
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.8.2")
}

// This is a basic Mill build for a single `ScalaModule`, with a single
// third-party dependency. As a single-module project, it `extends BuildModule`
// to mark `object foo` as the top-level module in the build. This lets us
// directly perform operations `./mill compile` or `./mill run` without needing
// to prefix it as `foo.compile` or `foo.run`.

/* Example Usage

> ./mill compile
compiling 1 Scala source

> ./mill run
Foo.value: <h1>hello</h1>

*/

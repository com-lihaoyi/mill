// == Adding Ivy Dependencies

import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.12.17"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::upickle:3.1.0",
    ivy"com.lihaoyi::pprint:0.8.1",
    ivy"${scalaOrganization()}:scala-reflect:${scalaVersion()}"
  )
}

// You can define the `ivyDeps` field to add ivy dependencies to your module. The
// `ivy"com.lihaoyi::upickle:0.5.1"` syntax (with `::`) represents Scala
// dependencies; for Java dependencies you would use a single `:` e.g.
// `"ivy"com.lihaoyi:upickle:0.5.1"`. If you have dependencies cross-published
// against the full Scala version (eg. `2.12.4` instead of just `2.12`),
// you can use `:::` as in `ivy"org.scalamacros:::paradise:2.1.1"`.
//
// To select the test-jars from a dependency use the following syntax:
// `ivy"org.apache.spark::spark-sql:2.4.0;classifier=tests`.
//
// Please consult the xref:Library_Dependencies.adoc[] section for even more details.

/** Example Usage

> ./mill run i am cow
Array("i", "am", "cow")
["i","am","cow"]

*/

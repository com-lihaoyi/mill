import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.12.17"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::upickle:3.1.0",
    ivy"com.lihaoyi::pprint:0.8.1",
    ivy"${scalaOrganization()}:scala-reflect:${scalaVersion()}"
  )
}

// You can define the `ivyDeps` field to add ivy dependencies to your module.
//
// * Single `:` syntax (e.g. `"ivy"org.testng:testng:6.11"`) defines Java
//   dependencies
//
// * Double `::` syntax (e.g. `ivy"com.lihaoyi::upickle:0.5.1"`) defines Scala
//   dependencies
//
// * Triple `:::` syntax (e.g. `ivy"org.scalamacros:::paradise:2.1.1"`) defines
//   dependencies cross-published against the full Scala version e.g. `2.12.4`
//   instead of just `2.12`. These are typically Scala compiler plugins or
//   similar.
//
// To select the test-jars from a dependency use the following syntax:
//
// * `ivy"org.apache.spark::spark-sql:2.4.0;classifier=tests`.
//
// Please consult the xref:Library_Dependencies.adoc[] section for even more details.

/** Usage

> ./mill run i am cow
pretty-printed using PPrint: Array("i", "am", "cow")
serialized using uPickle: ["i","am","cow"]

*/

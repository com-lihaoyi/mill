import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.10"

  def ivyDeps = Agg(
    ivy"com.lihaoyi::scalatags:0.8.2",
    ivy"com.lihaoyi::os-lib:0.9.1",
  )

  // We use `generatedSources` to write the list of dependencies this module
  // uses to a `MyDeps.scala` file, making them available at runtime e.g. if
  // the user of the module wants to know its dependencies for auditing purposes
  def generatedSources: T[Seq[PathRef]] = T {
    val prettyIvyDeps = for(ivyDep <- ivyDeps()) yield {
      val org = ivyDep.dep.module.organization.value
      val name = ivyDep.dep.module.name.value
      val version = ivyDep.dep.version
      s"""("$org", "$name", "$version")"""
    }
    os.write(
      T.dest / s"MyDeps.scala",
      s"""package foo
         |object MyDeps {
         |  val value = List(
         |    ${prettyIvyDeps.mkString(",\n")}
         |  )
         |}
         |""".stripMargin
    )

    Seq(PathRef(T.dest))
  }

  // We define a brand new target `lineCount`, that depends on `sources` and
  // walks the filesystem to find Scala files to count their lines. We then pass
  // this information to `forkArgs`, making the count available at runtime
  def lineCount: T[Int] = T {
    sources()
      .flatMap(pathRef => os.walk(pathRef.path))
      .filter(_.ext == "scala")
      .map(os.read.lines(_))
      .map(_.size)
      .sum
  }

  def forkArgs: T[Seq[String]] = Seq(s"-Dmy.line.count=${lineCount()}")
}

// This example shows how to define tasks that depend on other tasks:
//
// 1. For `generatedSources`, we override an the task and make it depend
//    directly on `ivyDeps` to generate its source files.
//
// 2. For `lineCount`, we define a brand new task that depends on `sources`,
//    and then override `forkArgs` to use it
//
// Note that depending on a task requires use of parentheses after the task
// name, e.g. `ivyDeps()`, `sources()` and `lineCount()`. This converts the
// task of type `T[V]` into a value of type `V` you can make use in your task
// implementation.

/* Example Usage

> ./mill run
Foo.value: <h1>hello</h1>
MyDeps.value: List((com.lihaoyi,scalatags,0.8.2), (com.lihaoyi,os-lib,0.9.1))
my.line.count: 10
*/

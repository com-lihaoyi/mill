// This example shows how to define target that depend on other tasks:
//
// 1. For `generatedSources`, we override an the task and make it depend
//    directly on `ivyDeps` to generate its source files. In this example,
//    to include the list of dependencies as tuples within a static `object`
//
// 2. For `lineCount`, we define a brand new task that depends on `sources`,
//    and then override `forkArgs` to use it. That lets us access the line
//    count at runtime using `sys.props` and print it when the program runs

import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.8"
  def ivyDeps = Agg(ivy"com.lihaoyi::mainargs:0.5.0")

  def generatedSources: T[Seq[PathRef]] = T {
    val prettyIvyDeps = for (ivyDep <- ivyDeps()) yield {
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

  def lineCount: T[Int] = T {
    sources()
      .flatMap(pathRef => os.walk(pathRef.path))
      .filter(_.ext == "scala")
      .map(os.read.lines(_).size)
      .sum
  }

  def forkArgs: T[Seq[String]] = Seq(s"-Dmy.line.count=${lineCount()}")

  def printLineCount() = T.command { println(lineCount()) }
}

// Mill lets you define new cached Targets using the `T {...}` syntax,
// depending on existing Targets e.g. `foo.sources` via the `foo.sources()`
// syntax to extract their current value, as shown in `lineCount` above. The
// return-type of a Target has to be JSON-serializable (using
// https://github.com/lihaoyi/upickle[uPickle]) and the Target is cached when
// first run until its inputs change (in this case, if someone edits the
// `foo.sources` files which live in `foo/src`. Cached Targets cannot take
// parameters.
//
// Note that depending on a task requires use of parentheses after the task
// name, e.g. `ivyDeps()`, `sources()` and `lineCount()`. This converts the
// task of type `T[V]` into a value of type `V` you can make use in your task
// implementation.
//
// This example can be run as follows:

/**
 * Usage
 *
 * > ./mill run --text hello
 * text: hello
 * MyDeps.value: List((com.lihaoyi,mainargs,0.4.0))
 * my.line.count: 12
 *
 * > ./mill show lineCount
 * 12
 *
 * > ./mill printLineCount
 * 12
 */

// Custom targets and commands can contain arbitrary code. Whether you want to
// download files using `requests.get`, shell-out to Webpack
// to compile some Javascript, generate sources to feed into a compiler, or
// create some custom jar/zip assembly with the files you want , all of these
// can simply be custom targets with your code running in the `T {...}` block.
//
// You can create arbitrarily long chains of dependent targets, and Mill will
// handle the re-evaluation and caching of the targets' output for you.
// Mill also provides you a `T.dest` folder for you to use as scratch space or
// to store files you want to return: all files a task creates should live
// within `T.dest`, and any files you want to modify should be copied into
// `T.dest` before being modified. That ensures that the files belonging to a
// particular target all live in one place, avoiding file-name conflicts and
// letting Mill automatically invalidate the files when the target's inputs
// change.

//// SNIPPET:BUILD

import mill._, javalib._

object foo extends RootModule with JavaModule {
  def ivyDeps = Agg(ivy"net.sourceforge.argparse4j:argparse4j:0.9.0")

  def generatedSources: T[Seq[PathRef]] = T {
    val prettyIvyDeps = for(ivyDep <- ivyDeps()) yield {
      val org = ivyDep.dep.module.organization.value
      val name = ivyDep.dep.module.name.value
      val version = ivyDep.dep.version
      s""" "$org:$name:$version" """
    }
    val ivyDepsString = prettyIvyDeps.mkString(" + \"\\n\" + \n")
    os.write(
      T.dest / s"MyDeps.java",
      s"""
         |package foo;
         |public class MyDeps {
         |  public static String value =
         |    $ivyDepsString;
         |}
      """.stripMargin
    )

    Seq(PathRef(T.dest))
  }

  def lineCount: T[Int] = T {
    sources()
      .flatMap(pathRef => os.walk(pathRef.path))
      .filter(_.ext == "java")
      .map(os.read.lines(_).size)
      .sum
  }

  def forkArgs: T[Seq[String]] = Seq(s"-Dmy.line.count=${lineCount()}")

  def printLineCount() = T.command { println(lineCount()) }
}

//// SNIPPET:COMMANDS

/** Usage

> mill run --text hello
text: hello
MyDeps.value: net.sourceforge.argparse4j:argparse4j:0.9.0
my.line.count: 24

> mill show lineCount
24

> mill printLineCount
24
*/
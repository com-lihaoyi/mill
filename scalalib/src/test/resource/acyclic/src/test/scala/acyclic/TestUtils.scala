package acyclic

import tools.nsc.{Global, Settings}
import tools.nsc.reporters.ConsoleReporter
import tools.nsc.plugins.Plugin

import java.net.URLClassLoader
import scala.tools.nsc.util.ClassPath
import utest._, asserts._
import scala.reflect.io.VirtualDirectory
import acyclic.plugin.Value
import scala.collection.SortedSet

object TestUtils {
  def getFilePaths(src: String): List[String] = {
    val f = new java.io.File(src)
    if (f.isDirectory) f.list.toList.flatMap(x => getFilePaths(src + "/" + x))
    else List(src)
  }

  /**
   * Attempts to compile a resource folder as a compilation run, in order
   * to test whether it succeeds or fails correctly.
   */
  def make(path: String,
           extraIncludes: Seq[String] = Seq("src/main/scala/acyclic/package.scala"),
           force: Boolean = false) = {
    val src = "src/test/resources/" + path
    val sources = getFilePaths(src) ++ extraIncludes

    val vd = new VirtualDirectory("(memory)", None)
    lazy val settings = new Settings
    val loader = getClass.getClassLoader.asInstanceOf[URLClassLoader]
    val entries = loader.getURLs map(_.getPath)
    settings.outputDirs.setSingleOutput(vd)

    // annoyingly, the Scala library is not in our classpath, so we have to add it manually
    val sclpath = entries.map(
      _.replaceAll("scala-compiler.jar", "scala-library.jar")
    )

    settings.classpath.value = ClassPath.join(entries ++ sclpath : _*)

    if (force) settings.pluginOptions.value = List("acyclic:force")

    var cycles: Option[Seq[Seq[(acyclic.plugin.Value, SortedSet[Int])]]] = None
    lazy val compiler = new Global(settings, new ConsoleReporter(settings)){
      override protected def loadRoughPluginsList(): List[Plugin] = {
        List(new plugin.TestPlugin(this, foundCycles => cycles = cycles match{
          case None => Some(Seq(foundCycles))
          case Some(oldCycles) => Some(oldCycles :+ foundCycles)
        }))
      }
    }
    val run = new compiler.Run()
    run.compile(sources)

    if (vd.toList.isEmpty) throw CompilationException(cycles.get)
  }

  def makeFail(path: String, force: Boolean = false)(expected: Seq[(Value, SortedSet[Int])]*) = {
    def canonicalize(cycle: Seq[(Value, SortedSet[Int])]): Seq[(Value, SortedSet[Int])] = {
      val startIndex = cycle.indexOf(cycle.minBy(_._1.toString))
      cycle.toList.drop(startIndex) ++ cycle.toList.take(startIndex)
    }

    val ex = intercept[CompilationException]{ make(path, force = force) }
    val cycles = ex.cycles
                   .map(canonicalize)
                   .map(
                     _.map{
                       case (Value.File(p, pkg), v) => (Value.File(p, Nil), v)
                       case x => x
                     }
                   )
                   .toSet

    def expand(v: Value) = v match{
      case Value.File(filePath, pkg) => Value.File("src/test/resources/" + path + "/" + filePath, Nil)
      case v => v
    }

    val fullExpected = expected.map(_.map(x => x.copy(_1 = expand(x._1))))
                               .map(canonicalize)
                               .toSet

    assert(fullExpected.forall(cycles.contains))
  }

  case class CompilationException(cycles: Seq[Seq[(Value, SortedSet[Int])]]) extends Exception

}

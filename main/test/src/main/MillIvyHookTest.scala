package mill.main

import java.io.File

import scala.util.Try

import ammonite.runtime.ImportHook.InterpreterInterface
import coursierapi.{Dependency => CDependency, Module => CModule, ScalaVersion => CScalaVersion}
import utest.{TestSuite, Tests, _}

object MillIvyHookTest extends TestSuite {
  val wd = os.pwd
  def mapDep(d: CDependency): Seq[File] =
    Seq(
      (wd / d.getModule.getOrganization / d.getModule.getName / d.getVersion / s"${d.getModule.getName}-${d.getVersion}.jar").toIO
    )
  override def tests: Tests = Tests {
    val interp = new InterpreterInterface {
      def loadIvy(coordinates: CDependency*): Either[String, Seq[File]] =
        Right(coordinates.flatMap(mapDep))
      def watch(p: os.Path): Unit = ???
      def scalaVersion: String = "2.13.6"
    }
    test("simple") {
      val deps = Seq(
        ("a:b:c", CDependency.of("a", "b", "c"), wd / "a" / "b" / "c" / "b-c.jar"),
        (
          "a::b:c",
          CDependency.of(CModule.parse("a::b", CScalaVersion.of("2.13.6")), "c"),
          wd / "a" / "b_2.13" / "c" / "b_2.13-c.jar"
        ),
        (
          "a::b::c",
          CDependency.of(
            CModule.parse(
              s"a::b_mill${mill.BuildInfo.millBinPlatform}",
              CScalaVersion.of("2.13.6")
            ),
            "c"
          ),
          wd / "a" / s"b_mill${mill.BuildInfo.millBinPlatform}_2.13" / "c" / s"b_mill${mill.BuildInfo.millBinPlatform}_2.13-c.jar"
        )
      )
      val checks = deps.map { case (coord, dep, path) =>
        Try {
          val expected: Either[String, (Seq[CDependency], Seq[File])] =
            Right(Seq(dep), Seq(path.toIO))
          val resolved = MillIvyHook.resolve(interp, Seq(coord))
          assert(
            // first check only adds context to the exception message
            !coord.isEmpty && resolved == expected
          )
        }
      }
      assert(checks.forall(_.isSuccess))
    }
  }
}

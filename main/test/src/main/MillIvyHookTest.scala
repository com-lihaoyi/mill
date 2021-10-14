package mill.main

import java.io.File

import scala.util.Try

import ammonite.runtime.ImportHook.InterpreterInterface
import coursierapi.{Dependency => CDependency, Module => CModule, ScalaVersion => CScalaVersion}
import utest.{TestSuite, Tests, _}

object MillIvyHookTest extends TestSuite {
  val wd = os.root / "tmp"
  def mapDep(d: CDependency): Seq[File] =
    Seq(
      (wd / s"${d.getModule.getOrganization}__${d.getModule.getName}__${d.getVersion}__${d.getModule.getName}-${d.getVersion}.jar").toIO
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
        ("a:b:c", CDependency.of("a", "b", "c"), wd / "a__b__c__b-c.jar"),
        (
          "a::b:c",
          CDependency.of(CModule.parse("a::b", CScalaVersion.of("2.13.6")), "c"),
          wd / "a__b_2.13__c__b_2.13-c.jar"
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
          wd / s"a__b_mill${mill.BuildInfo.millBinPlatform}_2.13__c__b_mill${mill.BuildInfo.millBinPlatform}_2.13-c.jar"
        ),
        (
          s"a::b:",
          CDependency.of(
            CModule.parse("a::b", CScalaVersion.of("2.13.6")),
            mill.BuildInfo.millVersion
          ),
          wd / s"a__b_2.13__${mill.BuildInfo.millVersion}__b_2.13-${mill.BuildInfo.millVersion}.jar"
        )
      )
      val checks = deps.map { case (coord, dep, path) =>
        Try {
          val expected: Either[String, (Seq[CDependency], Seq[File])] =
            Right(Seq(dep), Seq(path.toIO))
          val resolved = MillIvyHook.resolve(interp, Seq(coord))
          assert(
            // first check only adds context to the exception message
            coord.nonEmpty && dep.toString.nonEmpty && resolved == expected
          )
        }
      }
      assert(checks.forall(_.isSuccess))
    }
  }
}

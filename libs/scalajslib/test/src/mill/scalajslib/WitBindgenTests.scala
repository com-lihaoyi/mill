package mill.scalajslib

import mill.api.Discover
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*
import mill.*

object WitBindgenTests extends TestSuite {

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "wit-bindgen"

  /**
   * A stand-in for `wit-bindgen-scala`: it records its arguments and writes one
   * Scala source to `--out-dir`, so the test runs without the real tool.
   */
  def fakeBindgen(dir: os.Path): os.Path = {
    val script = dir / "fake-wit-bindgen-scala"
    os.write.over(
      script,
      """#!/bin/sh
        |printf '%s\n' "$@" > "$PWD/args.txt"
        |while [ $# -gt 0 ]; do
        |  if [ "$1" = "--out-dir" ]; then out="$2"; fi
        |  shift
        |done
        |mkdir -p "$out/generated"
        |printf 'package generated\nobject Marker { val witPackage = "mill:test" }\n' > "$out/generated/Marker.scala"
        |""".stripMargin,
      createFolders = true
    )
    os.perms.set(script, "rwxr-xr-x")
    script
  }

  object WitModule extends TestRootModule with ScalaJSModule {
    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    override def scalaJSVersion = "1.22.0"

    def witDirectory = Task.Source("wit")
    override def scalaJSWitDirectory = Task { Some(witDirectory()) }
    override def scalaJSWitWorld = Task { Some("calculator") }
    override def scalaJSWitPackage = Task { Some("bindings") }
    override def scalaJSWitBindgenArgs = Task { Seq("--all-features") }
    override def scalaJSWitBindgenExecutable =
      Task { fakeBindgen(Task.dest).toString }

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  object NoWitModule extends TestRootModule with ScalaJSModule {
    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    override def scalaJSVersion = "1.22.0"

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val tests: Tests = Tests {
    test("generatesBindings") {
      if (!scala.util.Properties.isWin) {
        UnitTester(WitModule, resourcePath).scoped { evaluator =>
          val Right(bindings) = evaluator(WitModule.scalaJSWitBindings).runtimeChecked
          val Seq(outDir) = bindings.value.runtimeChecked
          assert(os.exists(outDir.path / "generated" / "Marker.scala"))

          val args = os.read.lines(outDir.path / os.up / "args.txt")
          // UnitTester copies the module sources to a sandbox
          assert(os.Path(args.head).last == "wit")
          assert(args.tail == Seq(
            "--out-dir",
            outDir.path.toString,
            "--world",
            "calculator",
            "--base-package",
            "bindings",
            "--all-features"
          ))

          // the generated sources are compiled with the module
          val Right(_) = evaluator(WitModule.compile).runtimeChecked
        }
      }
    }

    test("noWitDirectory") {
      UnitTester(NoWitModule, resourcePath).scoped { evaluator =>
        val Right(bindings) = evaluator(NoWitModule.scalaJSWitBindings).runtimeChecked
        assert(bindings.value.isEmpty)
      }
    }
  }
}

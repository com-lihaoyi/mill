package mill.javalib.revapi

import mill.api.PathRef
import mill.javalib.*
import mill.scalalib.publish.{PomSettings, VersionControl}
import mill.testkit.{TestBaseModule, UnitTester}
import mill.{Agg, T, Task}
import utest.*

object RevapiModuleTests extends TestSuite {

  def tests: Tests = Tests {

    val root = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "javalib/revapi"
    val conf = root / "conf"
    val textReport = "report.txt"

    test("example") {
      val dir = revapiLocal(
        name = "example",
        root1 = root / "example/v1",
        root2 = root / "example/v2",
        conf = conf
      )
      val out = dir / textReport

      val actual = os.read.lines(out)
      // severities elided because their order is not stable across runs
      val expected = os.read.lines(root / "expected/example.lines")

      assert(
        expected.forall(actual.contains)
      )
    }

    test("guava") {
      val dir = revapiRemote(
        group = "com.google.guava",
        id = "guava",
        v1 = "17.0",
        v2 = "18.0",
        conf = conf
      )
      val out = dir / textReport

      val actual = os.read.lines(out)
      // severities elided because their order is not stable across runs
      val expected = os.read.lines(root / "expected/guava.lines")

      assert(
        expected.forall(actual.contains)
      )
    }
  }

  def revapiLocal(
      name: String,
      root1: os.Path,
      root2: os.Path,
      conf: os.Path
  ): os.Path = {
    trait module extends TestBaseModule with PublishModule {
      override def artifactName = name
      override def pomSettings: T[PomSettings] =
        PomSettings("", "mill.revapi.local", "", Seq(), VersionControl(), Seq())
      override def publishVersion: T[String] = root1.last
    }
    object module1 extends module
    object module2 extends module with RevapiModule {
      override def revapiConfigFiles: T[Seq[PathRef]] =
        Task.Sources(os.list(conf).iterator.filter(_.ext == "json").map(PathRef(_)).toSeq)
      override def revapiClasspath: T[Agg[PathRef]] = Task {
        super.revapiClasspath() ++ Seq(PathRef(conf))
      }
    }

    var eval = UnitTester(module1, root1)
    eval(module1.publishLocal())

    eval = UnitTester(module2, root2)
    val Right(dir) = eval(module2.revapi())
    dir.value.path
  }

  def revapiRemote(
      group: String,
      id: String,
      v1: String,
      v2: String,
      conf: os.Path
  ): os.Path = {

    object module extends TestBaseModule with RevapiModule {
      override def artifactName = id
      override def pomSettings: T[PomSettings] =
        PomSettings("", group, "", Seq(), VersionControl(), Seq())
      override def publishVersion: T[String] = v1

      override def revapiOldFiles: T[Agg[PathRef]] = Task {
        defaultResolver().classpath(Seq(mvn"$group:$id:$v1"))
      }
      override def revapiNewFiles: T[Agg[PathRef]] = Task {
        defaultResolver().classpath(Seq(mvn"$group:$id:$v2"))
      }
      override def revapiConfigFiles: T[Seq[PathRef]] =
        Task.Sources(os.list(conf).iterator.filter(_.ext == "json").map(PathRef(_)).toSeq)
      override def revapiClasspath: T[Agg[PathRef]] = Task {
        super.revapiClasspath() ++ Seq(PathRef(conf))
      }
    }

    val eval = UnitTester(module, os.temp.dir())
    val Right(dir) = eval(module.revapi())
    dir.value.path
  }
}

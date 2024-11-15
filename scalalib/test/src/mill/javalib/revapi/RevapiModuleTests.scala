package mill.javalib.revapi

import mill.api.PathRef
import mill.javalib.*
import mill.scalalib.publish.{PomSettings, VersionControl}
import mill.testkit.{TestBaseModule, UnitTester}
import mill.{Agg, T, Task}
import utest.*

import java.io.PrintStream

object RevapiModuleTests extends TestSuite {

  def tests: Tests = Tests {

    val root = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "javalib" / "revapi"

    test("example") {
      val out = os.pwd / "example.out"
      revapiLocal(
        root1 = root / "example/v1",
        root2 = root / "example/v2",
        conf = root / "conf",
        out = out
      )

      val actual = os.read.lines(out)
      val expected = os.read.lines(root / "expected/example.lines")

      assert(
        expected.forall(actual.contains)
      )
    }

    test("guava") {
      val out = os.pwd / "guava.out"
      revapiRemote(
        group = "com.google.guava",
        id = "guava",
        v1 = "17.0",
        v2 = "18.0",
        conf = root / "conf",
        out = out
      )

      val actual = os.read.lines(out)
      val expected = os.read.lines(root / "expected/guava.lines")

      assert(
        expected.forall(actual.contains)
      )
    }
  }

  def revapiLocal(
      root1: os.Path,
      root2: os.Path,
      conf: os.Path,
      out: os.Path
  ): Unit = {
    val outStream = new PrintStream(os.write.outputStream(out), true)

    trait module extends TestBaseModule with PublishModule {
      override def artifactName = out.baseName
      override def pomSettings: T[PomSettings] =
        PomSettings("", "mill.revapi.local", "", Seq(), VersionControl(), Seq())
      override def publishVersion: T[String] = root1.last
    }
    object module1 extends module
    object module2 extends module with RevapiModule {
      override def revapiConfigFiles: T[Seq[PathRef]] =
        Task.Sources(os.list(conf).iterator.filter(_.ext == "json").map(PathRef(_)).toSeq)
      override def revapiClasspath: T[Agg[PathRef]] = T {
        super.revapiClasspath() ++ Seq(PathRef(conf))
      }
    }

    try {
      var eval = UnitTester(module1, root1, outStream = outStream)
      eval(module1.publishLocal())

      eval = UnitTester(module2, root2, outStream = outStream)
      eval(module2.revapi())
    } finally {
      outStream.close()
    }
  }

  def revapiRemote(
      group: String,
      id: String,
      v1: String,
      v2: String,
      conf: os.Path,
      out: os.Path
  ): Unit = {
    val outStream = new PrintStream(os.write.outputStream(out), true)

    object module extends TestBaseModule with RevapiModule {
      override def artifactName = id
      override def pomSettings: T[PomSettings] =
        PomSettings("", group, "", Seq(), VersionControl(), Seq())
      override def publishVersion: T[String] = v1

      override def revapiOldFiles: T[Agg[PathRef]] = T {
        defaultResolver().resolveDeps(Seq(ivy"$group:$id:$v1"))
      }
      override def revapiNewFiles: T[Agg[PathRef]] = T {
        defaultResolver().resolveDeps(Seq(ivy"$group:$id:$v2"))
      }
      override def revapiConfigFiles: T[Seq[PathRef]] =
        Task.Sources(os.list(conf).iterator.filter(_.ext == "json").map(PathRef(_)).toSeq)
      override def revapiClasspath: T[Agg[PathRef]] = T {
        super.revapiClasspath() ++ Seq(PathRef(conf))
      }
    }

    try {
      val eval = UnitTester(module, os.temp.dir(), outStream = outStream)
      eval(module.revapi())
    } finally {
      outStream.close()
    }
  }
}

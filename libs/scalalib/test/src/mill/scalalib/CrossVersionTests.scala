package mill.scalalib

import mill.api.Discover
import mill.util.TokenReaders.*
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*

object CrossVersionTests extends TestSuite {

  object TestCases extends TestRootModule {

    object StandaloneScala213 extends ScalaModule {
      val tree =
        """├─ com.lihaoyi:upickle_2.13:1.4.0
          |│  ├─ com.lihaoyi:ujson_2.13:1.4.0
          |│  │  └─ com.lihaoyi:upickle-core_2.13:1.4.0
          |│  │     └─ com.lihaoyi:geny_2.13:0.6.10
          |│  ├─ com.lihaoyi:upack_2.13:1.4.0
          |│  │  └─ com.lihaoyi:upickle-core_2.13:1.4.0
          |│  │     └─ com.lihaoyi:geny_2.13:0.6.10
          |│  └─ com.lihaoyi:upickle-implicits_2.13:1.4.0
          |│     └─ com.lihaoyi:upickle-core_2.13:1.4.0
          |│        └─ com.lihaoyi:geny_2.13:0.6.10
          |└─ org.scala-lang:scala-library:2.13.10
          |""".stripMargin
      override def scalaVersion = "2.13.10"
      override def mvnDeps = Seq(mvn"com.lihaoyi::upickle:1.4.0")
    }

    object JavaDependsOnScala213 extends JavaModule {
      val tree =
        """├─ StandaloneScala213
          |│  ├─ com.lihaoyi:upickle_2.13:1.4.0
          |│  │  ├─ com.lihaoyi:ujson_2.13:1.4.0
          |│  │  │  └─ com.lihaoyi:upickle-core_2.13:1.4.0
          |│  │  │     └─ com.lihaoyi:geny_2.13:0.6.10
          |│  │  ├─ com.lihaoyi:upack_2.13:1.4.0
          |│  │  │  └─ com.lihaoyi:upickle-core_2.13:1.4.0
          |│  │  │     └─ com.lihaoyi:geny_2.13:0.6.10
          |│  │  └─ com.lihaoyi:upickle-implicits_2.13:1.4.0
          |│  │     └─ com.lihaoyi:upickle-core_2.13:1.4.0
          |│  │        └─ com.lihaoyi:geny_2.13:0.6.10
          |│  └─ org.scala-lang:scala-library:2.13.10
          |└─ org.slf4j:slf4j-api:1.7.35
          |""".stripMargin
      override def moduleDeps = Seq(StandaloneScala213)
      override def mvnDeps = Seq(mvn"org.slf4j:slf4j-api:1.7.35")
    }

    object Scala3DependsOnScala213 extends ScalaModule {
      val tree =
        """├─ StandaloneScala213
          |│  ├─ com.lihaoyi:upickle_2.13:1.4.0
          |│  │  ├─ com.lihaoyi:ujson_2.13:1.4.0
          |│  │  │  └─ com.lihaoyi:upickle-core_2.13:1.4.0
          |│  │  │     └─ com.lihaoyi:geny_2.13:0.6.10
          |│  │  ├─ com.lihaoyi:upack_2.13:1.4.0
          |│  │  │  └─ com.lihaoyi:upickle-core_2.13:1.4.0
          |│  │  │     └─ com.lihaoyi:geny_2.13:0.6.10
          |│  │  └─ com.lihaoyi:upickle-implicits_2.13:1.4.0
          |│  │     └─ com.lihaoyi:upickle-core_2.13:1.4.0
          |│  │        └─ com.lihaoyi:geny_2.13:0.6.10
          |│  └─ org.scala-lang:scala-library:2.13.10
          |├─ com.lihaoyi:sourcecode_3:0.2.7
          |└─ org.scala-lang:scala3-library_3:3.2.1
          |   └─ org.scala-lang:scala-library:2.13.10
          |""".stripMargin
      override def scalaVersion = "3.2.1"
      override def moduleDeps = Seq(StandaloneScala213)
      override def mvnDeps = Seq(mvn"com.lihaoyi::sourcecode:0.2.7")
    }

    object JavaDependsOnScala3 extends JavaModule {
      val tree =
        """├─ Scala3DependsOnScala213
          |│  ├─ com.lihaoyi:sourcecode_3:0.2.7
          |│  ├─ StandaloneScala213
          |│  │  ├─ com.lihaoyi:upickle_2.13:1.4.0
          |│  │  │  ├─ com.lihaoyi:ujson_2.13:1.4.0
          |│  │  │  │  └─ com.lihaoyi:upickle-core_2.13:1.4.0
          |│  │  │  │     └─ com.lihaoyi:geny_2.13:0.6.10
          |│  │  │  ├─ com.lihaoyi:upack_2.13:1.4.0
          |│  │  │  │  └─ com.lihaoyi:upickle-core_2.13:1.4.0
          |│  │  │  │     └─ com.lihaoyi:geny_2.13:0.6.10
          |│  │  │  └─ com.lihaoyi:upickle-implicits_2.13:1.4.0
          |│  │  │     └─ com.lihaoyi:upickle-core_2.13:1.4.0
          |│  │  │        └─ com.lihaoyi:geny_2.13:0.6.10
          |│  │  └─ org.scala-lang:scala-library:2.13.10
          |│  └─ org.scala-lang:scala3-library_3:3.2.1
          |│     └─ org.scala-lang:scala-library:2.13.10
          |└─ org.slf4j:slf4j-api:1.7.35
          |""".stripMargin
      override def moduleDeps = Seq(Scala3DependsOnScala213)
      override def mvnDeps = Seq(mvn"org.slf4j:slf4j-api:1.7.35")
    }

    object sandwitch3 extends ScalaModule {
      override def scalaVersion = "3.0.2"
      override def mvnDeps = Seq(mvn"com.lihaoyi::upickle:1.4.0")
    }

    object sandwitch213 extends ScalaModule {
      override def scalaVersion = "2.13.6"
      override def moduleDeps = Seq(sandwitch3)
      override def scalacOptions = Seq("-Ytasty-reader")
      val tree =
        """├─ sandwitch3
          |│  ├─ com.lihaoyi:upickle_3:1.4.0
          |│  │  ├─ com.lihaoyi:ujson_3:1.4.0
          |│  │  │  └─ com.lihaoyi:upickle-core_3:1.4.0
          |│  │  │     └─ com.lihaoyi:geny_3:0.6.10
          |│  │  ├─ com.lihaoyi:upack_3:1.4.0
          |│  │  │  └─ com.lihaoyi:upickle-core_3:1.4.0
          |│  │  │     └─ com.lihaoyi:geny_3:0.6.10
          |│  │  └─ com.lihaoyi:upickle-implicits_3:1.4.0
          |│  │     └─ com.lihaoyi:upickle-core_3:1.4.0
          |│  │        └─ com.lihaoyi:geny_3:0.6.10
          |│  └─ org.scala-lang:scala3-library_3:3.0.2
          |│     └─ org.scala-lang:scala-library:2.13.6
          |└─ org.scala-lang:scala-library:2.13.6
          |""".stripMargin
    }

    lazy val millDiscover = Discover[this.type]
  }

  def init() = UnitTester(TestCases, null)

  import TestCases._

  def check(
      mod: JavaModule,
      // TODO review: unused, removed in https://github.com/com-lihaoyi/mill/commit/1d12ccb6a56b9840a926f6cba642e0098cafe5db#diff-497c3d3724a2d2e3daa4ec3b6965cd82799711327d26f609dee4cceb786b4805L151
      expectedDeps: Seq[String],
      expectedLibs: Seq[String],
      expectedMvnDepsTree: Option[String] = None
  ) = {
    val _ = expectedDeps

    init().scoped { eval =>
      val Right(result) = eval.apply(mod.showMvnDepsTree(MvnDepsTreeArgs())): @unchecked

      expectedMvnDepsTree.foreach { tree =>
        if (scala.util.Properties.isWin) {
          println("Skipping check under Windows")
        } else {
          val diffed = diff(result.value.trim, tree.trim)
          assert(diffed == Nil)
        }
      }

      val Right(libs) = eval.apply(mod.compileClasspath): @unchecked

      val libNames = libs.value.map(l => l.path.last).filter(_.endsWith(".jar")).toSeq.sorted
      assert(libNames == expectedLibs.sorted)
    }
  }

  def diff(actual: String, expected: String): List[String] = {
    actual.lazyZip(expected).collect {
      case (x, y) if x != y => s"'$x' != '$y'"
    }.toList ++
      actual.drop(expected.length).map(x => s"'$x' is unexpected") ++
      expected.drop(actual.length).map(y => s"'$y' is expected but missing")
  }

  def tests: Tests = Tests {

    test("StandaloneScala213") {
      check(
        mod = StandaloneScala213,
        expectedDeps = Seq(
          "scala-library",
          "upickle_2.13"
        ),
        expectedLibs = Seq(
          "geny_2.13-0.6.10.jar",
          "scala-library-2.13.10.jar",
          "ujson_2.13-1.4.0.jar",
          "upack_2.13-1.4.0.jar",
          "upickle-core_2.13-1.4.0.jar",
          "upickle-implicits_2.13-1.4.0.jar",
          "upickle_2.13-1.4.0.jar"
        ),
        expectedMvnDepsTree = Some(StandaloneScala213.tree)
      )
    }

    test("JavaDependsOnScala213") {
      check(
        mod = JavaDependsOnScala213,
        expectedDeps = Seq(
          "scala-library",
          "upickle_2.13",
          "slf4j-api"
        ),
        expectedLibs = Seq(
          "slf4j-api-1.7.35.jar",
          "geny_2.13-0.6.10.jar",
          "scala-library-2.13.10.jar",
          "ujson_2.13-1.4.0.jar",
          "upack_2.13-1.4.0.jar",
          "upickle-core_2.13-1.4.0.jar",
          "upickle-implicits_2.13-1.4.0.jar",
          "upickle_2.13-1.4.0.jar"
        ),
        expectedMvnDepsTree = Some(JavaDependsOnScala213.tree)
      )
    }

    test("Scala3DependsOnScala213") {
      check(
        mod = Scala3DependsOnScala213,
        expectedDeps = Seq(
          "scala-library",
          "scala3-library_3",
          "upickle_2.13",
          "sourcecode_3"
        ),
        expectedLibs = Seq(
          "sourcecode_3-0.2.7.jar",
          "geny_2.13-0.6.10.jar",
          "scala-library-2.13.10.jar",
          "scala3-library_3-3.2.1.jar",
          "ujson_2.13-1.4.0.jar",
          "upack_2.13-1.4.0.jar",
          "upickle-core_2.13-1.4.0.jar",
          "upickle-implicits_2.13-1.4.0.jar",
          "upickle_2.13-1.4.0.jar"
        ),
        expectedMvnDepsTree = Some(Scala3DependsOnScala213.tree)
      )
    }

    test("JavaDependsOnScala3") {
      check(
        mod = JavaDependsOnScala3,
        expectedDeps = Seq(
          "scala-library",
          "scala3-library_3",
          "upickle_2.13",
          "sourcecode_3",
          "slf4j-api"
        ),
        expectedLibs = Seq(
          "slf4j-api-1.7.35.jar",
          "sourcecode_3-0.2.7.jar",
          "geny_2.13-0.6.10.jar",
          "scala-library-2.13.10.jar",
          "scala3-library_3-3.2.1.jar",
          "ujson_2.13-1.4.0.jar",
          "upack_2.13-1.4.0.jar",
          "upickle-core_2.13-1.4.0.jar",
          "upickle-implicits_2.13-1.4.0.jar",
          "upickle_2.13-1.4.0.jar"
        ),
        expectedMvnDepsTree = Some(JavaDependsOnScala3.tree)
      )
    }

    test("sndwitch3") {
      check(
        mod = sandwitch213,
        expectedDeps = Seq(
          "scala-library",
          "scala3-library_3",
          "upickle_3"
        ),
        expectedLibs = Seq(
          "scala-library-2.13.6.jar",
          "scala3-library_3-3.0.2.jar",
          "upickle_3-1.4.0.jar",
          "ujson_3-1.4.0.jar",
          "upickle-core_3-1.4.0.jar",
          "geny_3-0.6.10.jar",
          "upack_3-1.4.0.jar",
          "upickle-implicits_3-1.4.0.jar"
        ),
        expectedMvnDepsTree = Some(sandwitch213.tree)
      )
    }

  }
}

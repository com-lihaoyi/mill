package mill.javalib

import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import utest.*

object DeletedJavaSourceReproTests extends TestSuite {

  object ReproModules extends TestRootModule {
    object upstream extends JavaModule
    object downstream extends JavaModule {
      override def moduleDeps = Seq(upstream)
    }

    lazy val millDiscover = Discover[this.type]
  }

  val tests: Tests = Tests {
    test("deleted source invalidates compile output immediately") {
      withSourceRoot { sourceRoot =>
        UnitTester(ReproModules, sourceRoot).scoped { eval =>
          val deletedSource = eval.evaluator.workspace / "upstream/src/foo/A.java"
          val deletedClass = eval.outPath / "upstream/compile.dest/classes/foo/A.class"

          val Right(initialUpstream) = eval(ReproModules.upstream.compile).runtimeChecked
          val Right(initialDownstream) = eval(ReproModules.downstream.compile).runtimeChecked

          assert(
            initialUpstream.evalCount > 0,
            initialDownstream.evalCount > 0,
            os.exists(deletedClass)
          )

          os.remove(deletedSource)

          val Left(_) = eval(ReproModules.upstream.compile).runtimeChecked
          val Left(_) = eval(ReproModules.downstream.compile).runtimeChecked

          assert(
            !os.exists(deletedClass)
          )

          val Left(_) = eval(ReproModules.upstream.assembly).runtimeChecked
        }
      }
    }
  }

  private def withSourceRoot[T](body: os.Path => T): T = {
    val sourceRoot = os.temp.dir()
    os.write(
      sourceRoot / "upstream/src/foo/A.java",
      """package foo;
        |public class A {
        |    public static String x() {
        |        return "x";
        |    }
        |}
        |""".stripMargin,
      createFolders = true
    )
    os.write(
      sourceRoot / "upstream/src/foo/B.java",
      """package foo;
        |public class B {
        |    public static String y() {
        |        return A.x();
        |    }
        |}
        |""".stripMargin,
      createFolders = true
    )
    os.write(
      sourceRoot / "downstream/src/bar/C.java",
      """package bar;
        |import foo.B;
        |
        |public class C {
        |    public static String z() {
        |        return B.y();
        |    }
        |}
        |""".stripMargin,
      createFolders = true
    )

    try body(sourceRoot)
    finally os.remove.all(sourceRoot)
  }
}

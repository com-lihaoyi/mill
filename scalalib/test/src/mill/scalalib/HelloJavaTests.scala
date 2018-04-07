package mill.scalalib


import ammonite.ops.{%, %%, cp, ls, mkdir, pwd, rm, up}
import ammonite.ops.ImplicitWd._
import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath


object HelloJavaTests extends TestSuite {

  object HelloJava extends TestUtil.BaseModule{
    def millSourcePath =  TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
    object core extends JavaModule
    object main extends JavaModule{
      def moduleDeps = Seq(core)
    }
  }
  val resourcePath = pwd / 'scalalib / 'test / 'resources / "hello-java"

  def init()(implicit tp: TestPath) = {
    val eval = new TestEvaluator(HelloJava)
    rm(HelloJava.millSourcePath)
    rm(eval.outPath)
    mkdir(HelloJava.millSourcePath / up)
    cp(resourcePath, HelloJava.millSourcePath)
    eval
  }
  def tests: Tests = Tests {
    'scalaVersion - {
      val eval = init()

      val Right((res1, n1)) = eval.apply(HelloJava.core.compile)
      val Right((res2, 0)) = eval.apply(HelloJava.core.compile)
      val Right((res3, n2)) = eval.apply(HelloJava.main.compile)

      assert(
        res1 == res2,
        n1 != 0,
        n2 != 0,
        ls.rec(res1.classes.path).exists(_.last == "Core.class"),
        !ls.rec(res1.classes.path).exists(_.last == "Main.class"),
        ls.rec(res3.classes.path).exists(_.last == "Main.class"),
        !ls.rec(res3.classes.path).exists(_.last == "Core.class")
      )
    }
    'docJar  - {
      val eval = init()

      val Right((ref1, _)) = eval.apply(HelloJava.core.docJar)
      val Right((ref2, _)) = eval.apply(HelloJava.main.docJar)

      assert(
        %%("jar", "tf", ref1.path).out.lines.contains("hello/Core.html"),
        %%("jar", "tf", ref2.path).out.lines.contains("hello/Main.html")
      )
    }
  }
}

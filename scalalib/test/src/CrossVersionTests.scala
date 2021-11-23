package mill.scalalib

import mill.{Agg, Module, T}
import mill.api.{Loose, Result}
import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath

object CrossVersionTests extends TestSuite {

  trait TestBase extends TestUtil.BaseModule {
//    override def millSourcePath: os.Path =
//      TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  object HelloJava extends TestBase with JavaModule {
    override def moduleDeps = Seq(HelloScala213)
    override def ivyDeps = T { Agg(ivy"org.slf4j:slf4j-api:1.2.6") }
  }
  object HelloScala213 extends TestBase with ScalaModule {
    def scalaVersion = "2.13.6"
    override def moduleDeps = Seq(HelloScala3)
    override def ivyDeps = T { Agg(ivy"com.lihaoyi::sourcecode:0.2.7") }
    override def scalacOptions = T { Seq("-Ytasty-reader") }
  }
  object HelloScala3 extends TestBase with ScalaModule {
    def scalaVersion = "3.0.2"
    override def ivyDeps = T { Agg(ivy"com.lihaoyi::upickle:1.4.0") }
  }

//  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-java"

  def init()(implicit tp: TestPath) = {
    val eval = new TestEvaluator(HelloJava)
//    os.remove.all(HelloJava.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(HelloJava.millSourcePath / os.up)
//    os.copy(resourcePath, HelloJava.millSourcePath)
    eval
  }

  def tests: Tests = Tests {
    "transitive ivy deps have resolved cross-versions" - {
      val eval = init()

      {
        eval.apply(HelloScala3.ivyDepsTree())
        val Right((deps, _)) = eval.apply(HelloScala3.allIvyDeps)
        assert(
          deps.size == 2
          //        deps.forall(_.cross.isBinary)
        )
      }

      {
        eval.apply(HelloScala213.ivyDepsTree())
        val Right((deps, _)) = eval.apply(HelloScala213.transitiveIvyDeps)
        assert(
          deps.size == 4
//        deps.forall(_.cross.isConstant)
        )
      }

      {
        eval.apply(HelloJava.ivyDepsTree())
        val Right((deps, _)) = eval.apply(HelloJava.transitiveIvyDeps)
        assert(
          deps.size == 5,
//        deps.forall(_.cross.isConstant),
          deps.collect {
            case d @ Dep(_, CrossVersion.Constant("_3", _), _) => d
          }.size == 2,
          deps.collect {
            case d @ Dep(_, CrossVersion.Constant("_2.13", _), _) => d
          }.size == 1,
          deps.collect {
            case d @ Dep(_, CrossVersion.Constant("", _), _) => d
          }.size == 2
        )
      }
    }
  }
}

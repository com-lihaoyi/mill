package mill.scalaplugin

import ammonite.ops.ImplicitWd._
import ammonite.ops._
import mill.define.{Cross, Target, Task}
import mill.discover.Discovered
import mill.eval.{Evaluator, PathRef, Result}
import mill.modules.Jvm.jarUp
import mill.{Module, T}
import mill.util.OSet
import utest._
import mill.util.JsonFormatters._
object AcyclicBuild{
  val acyclic =
    for(crossVersion <- Cross("2.10.6", "2.11.8", "2.12.4"))
    yield new ScalaModule{outer =>
      def basePath = AcyclicTests.workspacePath
      def organization = "com.lihaoyi"
      def name = "acyclic"

      def version = "0.1.7"
      override def sources = basePath/'src/'main/'scala
      def scalaVersion = crossVersion
      override def compileIvyDeps = Seq(
        Dep.Java("org.scala-lang", "scala-compiler", scalaVersion())
      )
      object test extends this.Tests{
        def basePath = AcyclicTests.workspacePath
        override def ivyDeps = Seq(
          Dep("com.lihaoyi", "utest", "0.6.0")
        )
        override def sources = basePath/'src/'test/'scala
        def testFramework = "utest.runner.Framework"
      }
    }
}
object AcyclicTests extends TestSuite{
  val workspacePath = pwd / 'target / 'workspace / 'acyclic
  val srcPath = pwd / 'scalaplugin / 'src / 'test / 'resource / 'acyclic
  val tests = Tests{
    'acyclic - {
      rm(workspacePath)
      mkdir(workspacePath/up)
      cp(srcPath, workspacePath)
      val mapping = Discovered.mapping(AcyclicBuild)
      def eval[T](t: Task[T]): Either[Result.Failing, (T, Int)] = {
        val evaluator = new Evaluator(workspacePath, mapping, _ => ())
        val evaluated = evaluator.evaluate(OSet(t))

        if (evaluated.failing.keyCount == 0){
          Right(Tuple2(
            evaluated.rawValues(0).asInstanceOf[Result.Success[T]].value,
            evaluated.evaluated.collect{
              case t: Target[_] if mapping.contains(t) => t
              case t: mill.define.Command[_] => t
            }.size
          ))
        }else{
          Left(evaluated.failing.lookupKey(evaluated.failing.keys().next).items.next())
        }
      }

      // We can compile
      val Right((pathRef, evalCount)) = eval(AcyclicBuild.acyclic("2.12.4").compile)
      val outputPath = pathRef.path
      val outputFiles = ls.rec(outputPath)
      assert(
        evalCount > 0,
        outputFiles.contains(outputPath/'acyclic/'plugin/"GraphAnalysis.class"),
        outputFiles.contains(outputPath/'acyclic/'plugin/"PluginPhase.class")
      )

      // Compilation is cached
      val Right((_, evalCount2)) = eval(AcyclicBuild.acyclic("2.12.4").compile)
      assert(evalCount2 == 0)

      val packageScala = workspacePath/'src/'main/'scala/'acyclic/"package.scala"
      write.append(packageScala, "\n")

      // Caches are invalidated if code is changed
      val Right((_, evalCount3)) = eval(AcyclicBuild.acyclic("2.12.4").compile)
      assert(evalCount3 > 0)

      // Compilation can fail on broken code, and work when fixed
      write.append(packageScala, "\n}}")
      val Left(Result.Exception(ex)) = eval(AcyclicBuild.acyclic("2.12.4").compile)

      assert(ex.isInstanceOf[sbt.internal.inc.CompileFailed])

      write.write(packageScala, read(packageScala).dropRight(3))

      val Right((_, _)) = eval(AcyclicBuild.acyclic("2.12.4").compile)

      // Tests can run
//      val Right((_, _)) = eval(AcyclicBuild.acyclic("2.12.4").test.test())
    }
  }
}

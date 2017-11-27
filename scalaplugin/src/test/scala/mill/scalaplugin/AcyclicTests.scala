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
      override def ivyDeps = Seq(
        Dep.Java("org.scala-lang", "scala-compiler", scalaVersion())
      )
      object test extends this.Tests{
        def basePath = AcyclicTests.workspacePath
        override def forkWorkingDir = pwd/'scalaplugin/'src/'test/'resource/'acyclic
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
    val packageScala = workspacePath/'src/'main/'scala/'acyclic/"package.scala"

    'acyclic - {
      val scalaVersion = "2.12.4"
      // We can compile
      val Right((pathRef, evalCount)) = eval(AcyclicBuild.acyclic(scalaVersion).compile)
      val outputPath = pathRef.path
      val outputFiles = ls.rec(outputPath)
      assert(
        evalCount > 0,
        outputFiles.contains(outputPath/'acyclic/'plugin/"GraphAnalysis.class"),
        outputFiles.contains(outputPath/'acyclic/'plugin/"PluginPhase.class")
      )

      // Compilation is cached
      val Right((_, evalCount2)) = eval(AcyclicBuild.acyclic(scalaVersion).compile)
      assert(evalCount2 == 0)

      write.append(packageScala, "\n")

      // Caches are invalidated if code is changed
      val Right((_, evalCount3)) = eval(AcyclicBuild.acyclic(scalaVersion).compile)
      assert(evalCount3 > 0)

      // Compilation can fail on broken code, and work when fixed
      write.append(packageScala, "\n}}")
      val Left(Result.Exception(ex)) = eval(AcyclicBuild.acyclic(scalaVersion).compile)
      assert(ex.isInstanceOf[sbt.internal.inc.CompileFailed])

      write.write(packageScala, read(packageScala).dropRight(3))
      val Right(_) = eval(AcyclicBuild.acyclic(scalaVersion).compile)

      // Tests compile & run
      val Right(_) = eval(AcyclicBuild.acyclic(scalaVersion).test.forkTest())

      // Tests can be broken
      write.append(packageScala, "\n}}")
      val Left(_) = eval(AcyclicBuild.acyclic(scalaVersion).test.forkTest())

      // Tests can be fixed
      write.write(packageScala, read(packageScala).dropRight(3))
      val Right(_) = eval(AcyclicBuild.acyclic(scalaVersion).test.forkTest())
    }

  }
}

package mill.scalaplugin

import ammonite.ops.ImplicitWd._
import ammonite.ops._
import mill.define.{Cross,Task}
import mill.discover.Discovered
import mill.eval.Result
import utest._
import mill.util.JsonFormatters._
object AcyclicBuild{
  val acyclic =
    for(crossVersion <- Cross("2.10.6", "2.11.8", "2.12.3", "2.12.4"))
    yield new SbtScalaModule{outer =>
      def basePath = AcyclicTests.workspacePath
      override def organization = "com.lihaoyi"
      override def name = "acyclic"
      override def version = "0.1.7"
      def scalaVersion = crossVersion
      def ivyDeps = Seq(
        Dep.Java("org.scala-lang", "scala-compiler", scalaVersion())
      )
      object test extends this.Tests{
        def forkWorkingDir = pwd/'scalaplugin/'src/'test/'resource/'acyclic
        def ivyDeps = Seq(
          Dep("com.lihaoyi", "utest", "0.6.0")
        )
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
    def eval[T](t: Task[T]) = TestEvaluator.eval(mapping, workspacePath)(t)

    val packageScala = workspacePath/'src/'main/'scala/'acyclic/"package.scala"

    'scala210 - check("2.10.6", full = false)
    'scala211 - check("2.11.8", full = false)
    'scala2123 - check("2.12.3", full = true)
    'scala2124 - check("2.12.4", full = false)

    val allBinaryVersions = Seq("2.10", "2.11", "2.12")
    def check(scalaVersion: String, full: Boolean) = {
      // Dependencies are right; make sure every dependency is of the correct
      // binary Scala version, except for the compiler-bridge which is of the
      // same version as the host classpath.
      val Right((compileDepClasspath, _)) = eval(AcyclicBuild.acyclic(scalaVersion).compileDepClasspath)
      val binaryScalaVersion = scalaVersion.split('.').dropRight(1).mkString(".")
      val compileDeps = compileDepClasspath.map(_.path.toString())
      val offBinaryVersions = allBinaryVersions.filter(_ != binaryScalaVersion)
      val offVersionedDeps = compileDeps.filter(p => offBinaryVersions.exists(p.contains))
      assert(offVersionedDeps.forall(_.contains("compiler-bridge")))

      // We can compile
      val Right((pathRef, evalCount)) = eval(AcyclicBuild.acyclic(scalaVersion).compile)
      val outputPath = pathRef.classes.path
      val outputFiles = ls.rec(outputPath)
      assert(
        evalCount > 0,
        outputFiles.contains(outputPath/'acyclic/'plugin/"GraphAnalysis.class"),
        outputFiles.contains(outputPath/'acyclic/'plugin/"PluginPhase.class")
      )

      // Compilation is cached
      val Right((_, evalCount2)) = eval(AcyclicBuild.acyclic(scalaVersion).compile)
      assert(evalCount2 == 0)

      if (full){
        // Caches are invalidated if code is changed
        write.append(packageScala, "\n")
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
}

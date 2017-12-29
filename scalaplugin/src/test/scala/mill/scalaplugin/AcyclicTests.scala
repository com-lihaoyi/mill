package mill.scalaplugin

import ammonite.ops.ImplicitWd._
import ammonite.ops._
import mill.define.Cross
import mill.discover.Discovered
import mill.scalaplugin.publish._
import utest._
import mill.util.JsonFormatters._
import mill.util.TestEvaluator
object AcyclicBuild{
  val acyclic =
    for(crossVersion <- Cross("2.10.6", "2.11.8", "2.12.3", "2.12.4"))
    yield new SbtScalaModule with PublishModule {outer =>
      def basePath = AcyclicTests.workspacePath
      def artifactName = "acyclic"
      def publishVersion = "0.1.7"

      def pomSettings = PomSettings(
        description = artifactName(),
        organization = "com.lihaoyi",
        url = "https://github.com/lihaoyi/acyclic",
        licenses = Seq(
          License("MIT license", "http://www.opensource.org/licenses/mit-license.php")
        ),
        scm = SCM(
          "git://github.com/lihaoyi/acyclic.git",
          "scm:git://github.com/lihaoyi/acyclic.git"
        ),
        developers = Seq(
          Developer("lihaoyi", "Li Haoyi","https://github.com/lihaoyi")
        )
      )

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
    val eval = new TestEvaluator(mapping, workspacePath)

    def check(scalaVersion: String) = {
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
    }

    'scala211 - check("2.11.8")
    'scala2123 - check("2.12.3")

  }
}

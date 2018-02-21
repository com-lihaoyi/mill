package mill.scalalib

import java.util.jar.JarFile

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import mill._
import mill.define.{Discover, Target}
import mill.eval.{Evaluator, Result}
import mill.scalalib.publish._
import mill.util.{TestEvaluator, TestUtil}
import utest._

import utest.framework.TestPath

import scala.collection.JavaConverters._


object HelloWorldTests extends TestSuite {
  trait HelloBase extends TestUtil.BaseModule{
    def millSourcePath =  TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }
  trait HelloWorldModule extends scalalib.ScalaModule {
    def scalaVersion = "2.12.4"
  }


  object HelloWorld extends HelloBase {
    object core extends HelloWorldModule
  }
  object CrossHelloWorld extends HelloBase {
    object core extends Cross[HelloWorldCross]("2.10.6", "2.11.11", "2.12.3", "2.12.4")
    class HelloWorldCross(val crossScalaVersion: String) extends CrossScalaModule
  }

  object HelloWorldDefaultMain extends HelloBase {
    object core extends HelloWorldModule
  }

  object HelloWorldWithoutMain extends HelloBase {
    object core extends HelloWorldModule{
      def mainClass = None
    }
  }

  object HelloWorldWithMain extends HelloBase {
    object core extends HelloWorldModule{
      def mainClass = Some("Main")
    }
  }

  object HelloWorldWithMainAssembly extends HelloBase {
    object core extends HelloWorldModule{
      def mainClass = Some("Main")
      def assembly = T{
        mill.modules.Jvm.createAssembly(
          runClasspath().map(_.path).filter(exists),
          prependShellScript = prependShellScript(),
          mainClass = mainClass()
        )
      }
    }
  }

  object HelloWorldWarnUnused extends HelloBase{
    object core extends HelloWorldModule {
      def scalacOptions = T(Seq("-Ywarn-unused"))
    }
  }

  object HelloWorldFatalWarnings extends HelloBase{
    object core extends HelloWorldModule {
      def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
    }

  }

  object HelloWorldWithPublish extends HelloBase{
    object core extends HelloWorldModule with PublishModule{

      def artifactName = "hello-world"
      def publishVersion = "0.0.1"

      def pomSettings = PomSettings(
        organization = "com.lihaoyi",
        description = "hello world ready for real world publishing",
        url = "https://github.com/lihaoyi/hello-world-publish",
        licenses = Seq(
          License("Apache License, Version 2.0",
            "http://www.apache.org/licenses/LICENSE-2.0")),
        scm = SCM(
          "https://github.com/lihaoyi/hello-world-publish",
          "scm:git:https://github.com/lihaoyi/hello-world-publish"
        ),
        developers =
          Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
      )
    }
  }

  object HelloWorldScalaOverride extends HelloBase{
    object core extends HelloWorldModule {

      override def scalaVersion: Target[String] = "2.11.11"
    }
  }
  val resourcePath = pwd / 'scalalib / 'test / 'resources / "hello-world"

  def jarMainClass(jar: JarFile): Option[String] = {
    import java.util.jar.Attributes._
    val attrs = jar.getManifest.getMainAttributes.asScala
    attrs.get(Name.MAIN_CLASS).map(_.asInstanceOf[String])
  }

  def compileClassfiles = Seq[RelPath](
    "Main.class",
    "Main$.class",
    "Main0.class",
    "Main0$.class",
    "Main$delayedInit$body.class",
    "Person.class",
    "Person$.class"
  )

  def workspaceTest[T, M <: TestUtil.BaseModule](m: M, resourcePath: Path = resourcePath)
                                                (t: TestEvaluator[M] => T)
                                                (implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    rm(m.millSourcePath)
    rm(eval.outPath)
    mkdir(m.millSourcePath / up)
    cp(resourcePath, m.millSourcePath)
    t(eval)
  }
  



  def tests: Tests = Tests {
    'scalaVersion - {
      
      'fromBuild - workspaceTest(HelloWorld){eval => 
        val Right((result, evalCount)) = eval.apply(HelloWorld.core.scalaVersion)

        assert(
          result == "2.12.4",
          evalCount > 0
        )
      }
      'override - workspaceTest(HelloWorldScalaOverride){eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldScalaOverride.core.scalaVersion)

        assert(
          result == "2.11.11",
          evalCount > 0
        )
      }
    }
    'scalacOptions - {
      'emptyByDefault - workspaceTest(HelloWorld){eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorld.core.scalacOptions)

        assert(
          result.isEmpty,
          evalCount > 0
        )
      }
      'override - workspaceTest(HelloWorldFatalWarnings){ eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldFatalWarnings.core.scalacOptions)

        assert(
          result == Seq("-Ywarn-unused", "-Xfatal-warnings"),
          evalCount > 0
        )
      }
    }
    'compile - {
      'fromScratch - workspaceTest(HelloWorld){eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorld.core.compile)

        val analysisFile = result.analysisFile
        val outputFiles = ls.rec(result.classes.path)
        val expectedClassfiles = compileClassfiles.map(
          eval.outPath / 'core / 'compile / 'dest / 'classes / _
        )
        assert(
          result.classes.path == eval.outPath / 'core / 'compile / 'dest / 'classes,
          exists(analysisFile),
          outputFiles.nonEmpty,
          outputFiles.forall(expectedClassfiles.contains),
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) = eval.apply(HelloWorld.core.compile)

        assert(unchangedEvalCount == 0)
      }
      'recompileOnChange - workspaceTest(HelloWorld){eval =>
        val Right((_, freshCount)) = eval.apply(HelloWorld.core.compile)
        assert(freshCount > 0)

        write.append(HelloWorld.millSourcePath / 'core / 'src / "Main.scala", "\n")

        val Right((_, incCompileCount)) = eval.apply(HelloWorld.core.compile)
        assert(incCompileCount > 0, incCompileCount < freshCount)
      }
      'failOnError - workspaceTest(HelloWorld){eval =>
        write.append(HelloWorld.millSourcePath / 'core / 'src / "Main.scala", "val x: ")

        val Left(Result.Failure("Compilation failed", _)) = eval.apply(HelloWorld.core.compile)


        val paths = Evaluator.resolveDestPaths(
          eval.outPath,
          HelloWorld.core.compile.ctx.segments
        )

        assert(
          ls.rec(paths.dest / 'classes).isEmpty,
          !exists(paths.meta)
        )
        // Works when fixed
        write.over(
          HelloWorld.millSourcePath / 'core / 'src / "Main.scala",
          read(HelloWorld.millSourcePath / 'core / 'src / "Main.scala").dropRight("val x: ".length)
        )

        val Right((result, evalCount)) = eval.apply(HelloWorld.core.compile)
      }
      'passScalacOptions - workspaceTest(HelloWorldFatalWarnings){ eval =>
        // compilation fails because of "-Xfatal-warnings" flag
        val Left(Result.Failure("Compilation failed", _)) = eval.apply(HelloWorldFatalWarnings.core.compile)

      }
    }
    'runMain - {
      'runMainObject - workspaceTest(HelloWorld){eval =>
        val runResult = eval.outPath / 'core / 'runMain / 'dest / "hello-mill"

        val Right((_, evalCount)) = eval.apply(HelloWorld.core.runMain("Main", runResult.toString))
        assert(evalCount > 0)

        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      'runCross - {
        def cross(eval: TestEvaluator[_], v: String, expectedOut: String) {

          val runResult = eval.outPath / "hello-mill"

          val Right((_, evalCount)) = eval.apply(
            CrossHelloWorld.core(v).runMain("Shim", runResult.toString)
          )

          assert(evalCount > 0)


          assert(
            exists(runResult),
            read(runResult) == expectedOut
          )
        }
        'v210 - workspaceTest(CrossHelloWorld)(cross(_, "2.10.6", "2.10.6 rox"))
        'v211 - workspaceTest(CrossHelloWorld)(cross(_, "2.11.11", "2.11.11 pwns"))
        'v2123 - workspaceTest(CrossHelloWorld)(cross(_, "2.12.3", "2.12.3 leet"))
        'v2124 - workspaceTest(CrossHelloWorld)(cross(_, "2.12.4", "2.12.4 leet"))
      }


      'notRunInvalidMainObject - workspaceTest(HelloWorld){eval =>
        val Left(Result.Exception(err, _)) = eval.apply(HelloWorld.core.runMain("Invalid"))

        assert(
          err.isInstanceOf[InteractiveShelloutException]
        )
      }
      'notRunWhenCompileFailed - workspaceTest(HelloWorld){eval =>
        write.append(HelloWorld.millSourcePath / 'core / 'src / "Main.scala", "val x: ")

        val Left(Result.Failure("Compilation failed", _)) = eval.apply(HelloWorld.core.runMain("Main"))

      }
    }

    'forkRun - {
      'runIfMainClassProvided - workspaceTest(HelloWorldWithMain){eval =>
        val runResult = eval.outPath / 'core / 'run / 'dest / "hello-mill"
        val Right((_, evalCount)) = eval.apply(
          HelloWorldWithMain.core.run(runResult.toString)
        )

        assert(evalCount > 0)


        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      'notRunWithoutMainClass - workspaceTest(
        HelloWorldWithoutMain,
        pwd / 'scalalib / 'test / 'resources / "hello-world-no-main"
      ){eval =>
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldWithoutMain.core.run())
      }

      'runDiscoverMainClass - workspaceTest(HelloWorldWithoutMain){eval =>
        // Make sure even if there isn't a main class defined explicitly, it gets
        // discovered by Zinc and used
        val runResult = eval.outPath / 'core / 'run / 'dest / "hello-mill"
        val Right((_, evalCount)) = eval.apply(
          HelloWorldWithoutMain.core.run(runResult.toString)
        )

        assert(evalCount > 0)


        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
    }
    'run - {
      'runIfMainClassProvided - workspaceTest(HelloWorldWithMain){eval =>
        val runResult = eval.outPath / 'core / 'run / 'dest / "hello-mill"
        val Right((_, evalCount)) = eval.apply(
          HelloWorldWithMain.core.runLocal(runResult.toString)
        )

        assert(evalCount > 0)


        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      'runWithDefaultMain - workspaceTest(HelloWorldDefaultMain){eval =>
        val runResult = eval.outPath / 'core / 'run / 'dest / "hello-mill"
        val Right((_, evalCount)) = eval.apply(
          HelloWorldDefaultMain.core.runLocal(runResult.toString)
        )

        assert(evalCount > 0)


        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      'notRunWithoutMainClass - workspaceTest(
        HelloWorldWithoutMain,
        pwd / 'scalalib / 'test / 'resources / "hello-world-no-main"
      ){eval =>
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldWithoutMain.core.runLocal())

      }
    }
    'jar - {
      'nonEmpty - workspaceTest(HelloWorldWithMain){eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithMain.core.jar)

        assert(
          exists(result.path),
          evalCount > 0
        )

        val jarFile = new JarFile(result.path.toIO)
        val entries = jarFile.entries().asScala.map(_.getName).toSet

        val manifestFiles = Seq[RelPath](
          "META-INF" / "MANIFEST.MF"
        )
        val expectedFiles = compileClassfiles ++ manifestFiles

        assert(
          entries.nonEmpty,
          entries == expectedFiles.map(_.toString()).toSet
        )

        val mainClass = jarMainClass(jarFile)
        assert(mainClass.contains("Main"))
      }
      'logOutputToFile - workspaceTest(HelloWorld){eval =>
        val outPath = eval.outPath
        eval.apply(HelloWorld.core.compile)

        val logFile = outPath / 'core / 'compile / 'log
        assert(exists(logFile))
      }
    }

    'assembly - {
      'assembly - workspaceTest(HelloWorldWithMainAssembly){ eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithMainAssembly.core.assembly)
        assert(
          exists(result.path),
          evalCount > 0
        )
        val jarFile = new JarFile(result.path.toIO)
        val entries = jarFile.entries().asScala.map(_.getName).toSet

        assert(entries.contains("Main.class"))
        assert(entries.exists(s => s.contains("scala/Predef.class")))

        val mainClass = jarMainClass(jarFile)
        assert(mainClass.contains("Main"))
      }
      'run - workspaceTest(HelloWorldWithMainAssembly){eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithMainAssembly.core.assembly)

        assert(
          exists(result.path),
          evalCount > 0
        )
        val runResult = eval.outPath / "hello-mill"

        %%("java", "-jar", result.path, runResult)(wd = eval.outPath)

        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
    }
  }


}

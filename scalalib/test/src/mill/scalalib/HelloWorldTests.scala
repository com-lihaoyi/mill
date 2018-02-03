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
import mill.util.TestEvaluator.implicitDisover
import utest.framework.TestPath

import scala.collection.JavaConverters._


object HelloWorldTests extends TestSuite {
  trait HelloWorldModule
  extends TestUtil.BaseModule with scalalib.ScalaModule {
    def scalaVersion = "2.12.4"
    def millSourcePath =  TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }


  object HelloWorld extends HelloWorldModule
  object CrossHelloWorld extends TestUtil.BaseModule {
    object cross extends Cross[HelloWorldCross]("2.10.6", "2.11.11", "2.12.3", "2.12.4")
    class HelloWorldCross(v: String) extends HelloWorldModule {
      def millSourcePath = super.millSourcePath / up
      def scalaVersion = v
    }
  }

  object HelloWorldWithMain extends HelloWorldModule {
    def mainClass = Some("Main")
  }

  object HelloWorldWithMainAssembly extends HelloWorldModule {
    def mainClass = Some("Main")
    def assembly = T{
      mill.modules.Jvm.createAssembly(
        runClasspath().map(_.path).filter(exists),
        prependShellScript = prependShellScript(),
        mainClass = mainClass()
      )
    }
  }

  object HelloWorldWarnUnused extends HelloWorldModule {
    def scalacOptions = T(Seq("-Ywarn-unused"))
  }

  object HelloWorldFatalWarnings extends HelloWorldModule {
    def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
  }

  object HelloWorldWithPublish extends HelloWorldModule with PublishModule {
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
  object HelloWorldScalaOverride extends HelloWorldModule {
    override def scalaVersion: Target[String] = "2.11.11"
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
    "Main$delayedInit$body.class",
    "Person.class",
    "Person$.class"
  )

  def workspaceTest[T, M <: TestUtil.TestBuild: Discover](m: M)
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
        val Right((result, evalCount)) = eval.apply(HelloWorld.scalaVersion)

        assert(
          result == "2.12.4",
          evalCount > 0
        )
      }
      'override - workspaceTest(HelloWorldScalaOverride){eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldScalaOverride.scalaVersion)

        assert(
          result == "2.11.11",
          evalCount > 0
        )
      }
    }
    'scalacOptions - {
      'emptyByDefault - workspaceTest(HelloWorld){eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorld.scalacOptions)

        assert(
          result.isEmpty,
          evalCount > 0
        )
      }
      'override - workspaceTest(HelloWorldFatalWarnings){ eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldFatalWarnings.scalacOptions)

        assert(
          result == Seq("-Ywarn-unused", "-Xfatal-warnings"),
          evalCount > 0
        )
      }
    }
    'compile - {
      'fromScratch - workspaceTest(HelloWorld){eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorld.compile)

        val analysisFile = result.analysisFile
        val outputFiles = ls.rec(result.classes.path)
        val expectedClassfiles = compileClassfiles.map(eval.outPath / 'compile / 'dest / 'classes / _)
        assert(
          result.classes.path == eval.outPath / 'compile / 'dest / 'classes,
          exists(analysisFile),
          outputFiles.nonEmpty,
          outputFiles.forall(expectedClassfiles.contains),
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) = eval.apply(HelloWorld.compile)

        assert(unchangedEvalCount == 0)
      }
      'recompileOnChange - workspaceTest(HelloWorld){eval =>
        val Right((_, freshCount)) = eval.apply(HelloWorld.compile)
        assert(freshCount > 0)

        write.append(HelloWorld.millSourcePath / 'src / "Main.scala", "\n")

        val Right((_, incCompileCount)) = eval.apply(HelloWorld.compile)
        assert(incCompileCount > 0, incCompileCount < freshCount)
      }
      'failOnError - workspaceTest(HelloWorld){eval =>
        write.append(HelloWorld.millSourcePath / 'src / "Main.scala", "val x: ")

        val Left(Result.Exception(err, _)) = eval.apply(HelloWorld.compile)

//        assert(err.isInstanceOf[CompileFailed])

        val paths = Evaluator.resolveDestPaths(
          eval.outPath,
          HelloWorld.compile.ctx.segments
        )

        assert(
          ls.rec(paths.dest / 'classes).isEmpty,
          !exists(paths.meta)
        )
        // Works when fixed
        write.over(
          HelloWorld.millSourcePath / 'src / "Main.scala",
          read(HelloWorld.millSourcePath / 'src / "Main.scala").dropRight("val x: ".length)
        )

        val Right((result, evalCount)) = eval.apply(HelloWorld.compile)
      }
      'passScalacOptions - workspaceTest(HelloWorldFatalWarnings){ eval =>
        // compilation fails because of "-Xfatal-warnings" flag
        val Left(Result.Exception(err, _)) = eval.apply(HelloWorldFatalWarnings.compile)

//        assert(err.isInstanceOf[CompileFailed])
      }
    }
    'runMain - {
      'runMainObject - workspaceTest(HelloWorld){eval =>
        val runResult = eval.outPath/ 'runMain / 'dest / "hello-mill"

        val Right((_, evalCount)) = eval.apply(HelloWorld.runMain("Main", runResult.toString))
        assert(evalCount > 0)

        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      'runCross - {
        def cross(eval: TestEvaluator[_], v: String) {

          val runResult = eval.outPath / 'cross / v / 'runMain / 'dest / "hello-mill"

          val Right((_, evalCount)) = eval.apply(
            CrossHelloWorld.cross(v).runMain("Main", runResult.toString)
          )

          assert(evalCount > 0)


          assert(
            exists(runResult),
            read(runResult) == "hello rockjam, your age is: 25"
          )
        }
        'v210 - workspaceTest(CrossHelloWorld)(cross(_, "2.10.6"))
        'v211 - workspaceTest(CrossHelloWorld)(cross(_, "2.11.11"))
        'v2123 - workspaceTest(CrossHelloWorld)(cross(_, "2.12.3"))
        'v2124 - workspaceTest(CrossHelloWorld)(cross(_, "2.12.4"))
      }


      'notRunInvalidMainObject - workspaceTest(HelloWorld){eval =>
        val Left(Result.Exception(err, _)) = eval.apply(HelloWorld.runMain("Invalid"))

        assert(
          err.isInstanceOf[InteractiveShelloutException]
        )
      }
      'notRunWhenComplileFailed - workspaceTest(HelloWorld){eval =>
        write.append(HelloWorld.millSourcePath / 'src / "Main.scala", "val x: ")

        val Left(Result.Exception(err, _)) = eval.apply(HelloWorld.runMain("Main"))

//        assert(
//          err.isInstanceOf[CompileFailed]
//        )
      }
    }

    'forkRun - {
      'runIfMainClassProvided - workspaceTest(HelloWorldWithMain){eval =>
        val runResult = eval.outPath / 'run / 'dest / "hello-mill"
        val Right((_, evalCount)) = eval.apply(
          HelloWorldWithMain.run(runResult.toString)
        )

        assert(evalCount > 0)


        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      'notRunWithoutMainClass - workspaceTest(HelloWorld){eval =>
        val Left(Result.Exception(err, _)) = eval.apply(HelloWorld.run())

        assert(
          err.isInstanceOf[RuntimeException]
        )
      }
    }
    'run - {
      'runIfMainClassProvided - workspaceTest(HelloWorldWithMain){eval =>
        val runResult = eval.outPath / 'run / 'dest / "hello-mill"
        val Right((_, evalCount)) = eval.apply(
          HelloWorldWithMain.runLocal(runResult.toString)
        )

        assert(evalCount > 0)


        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      'notRunWithoutMainClass - workspaceTest(HelloWorld){eval =>
        val Left(Result.Exception(err, _)) = eval.apply(HelloWorld.runLocal())

        assert(
          err.isInstanceOf[RuntimeException]
        )
      }
    }
    'jar - {
      'nonEmpty - workspaceTest(HelloWorldWithMain){eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithMain.jar)

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
        eval.apply(HelloWorld.compile)

        val logFile = outPath / 'compile / 'log
        assert(exists(logFile))
      }
    }

    'assembly - {
      'assembly - workspaceTest(HelloWorldWithMainAssembly){ eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithMainAssembly.assembly)
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
        val Right((result, evalCount)) = eval.apply(HelloWorldWithMainAssembly.assembly)

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

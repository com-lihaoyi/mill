package mill.scalaplugin

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import mill._
import mill.define.{Target, Task}
import mill.discover.Discovered
import mill.discover.Mirror.LabelledTarget
import mill.eval.Result
import mill.scalaplugin.publish._
import sbt.internal.inc.CompileFailed
import utest._

trait HelloWorldModule extends ScalaModule {
  def scalaVersion = "2.12.4"
  def basePath = HelloWorldTests.workspacePath
}

object HelloWorld extends HelloWorldModule

object HelloWorldWithMain extends HelloWorldModule {
  def mainClass = Some("Main")
}

object HelloWorldWarnUnused extends HelloWorldModule {
  def scalacOptions = T(Seq("-Ywarn-unused"))
}

object HelloWorldFatalWarnings extends HelloWorldModule {
  def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
}

object HelloWorldWithPublish extends HelloWorldModule with PublishModule {
  def publishName = "hello-world"
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

object HelloWorldTests extends TestSuite {

  val srcPath = pwd / 'scalaplugin / 'src / 'test / 'resource / "hello-world"
  val workspacePath = pwd / 'target / 'workspace / "hello-world"
  val outputPath = workspacePath / 'out
  val mainObject = workspacePath / 'src / 'main / 'scala / "Main.scala"

  def eval[T](t: Task[T], mapping: Map[Target[_], LabelledTarget[_]]) =
    TestEvaluator.eval(mapping, outputPath)(t)

  val helloWorldMapping = Discovered.mapping(HelloWorld)
  val helloWorldWithMainMapping = Discovered.mapping(HelloWorldWithMain)

  def tests: Tests = Tests {
    prepareWorkspace()
    'scalaVersion - {
      'fromBuild - {
        val Right((result, evalCount)) =
          eval(HelloWorld.scalaVersion, helloWorldMapping.value)

        assert(
          result == "2.12.4",
          evalCount > 0
        )
      }
      'override - {
        object HelloWorldScalaOverride extends HelloWorldModule {
          override def scalaVersion: Target[String] = "2.11.11"
        }

        val Right((result, evalCount)) =
          eval(HelloWorldScalaOverride.scalaVersion,
               Discovered.mapping(HelloWorldScalaOverride).value)

        assert(
          result == "2.11.11",
          evalCount > 0
        )
      }
    }
    'scalacOptions - {
      'emptyByDefault - {
        val Right((result, evalCount)) =
          eval(HelloWorld.scalacOptions, helloWorldMapping.value)

        assert(
          result.isEmpty,
          evalCount > 0
        )
      }
      'override - {
        val Right((result, evalCount)) =
          eval(HelloWorldFatalWarnings.scalacOptions,
               Discovered.mapping(HelloWorldFatalWarnings).value)

        assert(
          result == Seq("-Ywarn-unused", "-Xfatal-warnings"),
          evalCount > 0
        )
      }
    }
    'compile - {
      'fromScratch - {
        val Right((result, evalCount)) =
          eval(HelloWorld.compile, helloWorldMapping.value)

        val outPath = result.classes.path
        val analysisFile = result.analysisFile
        val outputFiles = ls.rec(outPath)
        val expectedClassfiles =
          compileClassfiles(outputPath / 'compile / 'classes)
        assert(
          outPath == outputPath / 'compile / 'classes,
          exists(analysisFile),
          outputFiles.nonEmpty,
          outputFiles.forall(expectedClassfiles.contains),
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) =
          eval(HelloWorld.compile, helloWorldMapping.value)
        assert(unchangedEvalCount == 0)
      }
      'recompileOnChange - {
        val Right((_, freshCount)) =
          eval(HelloWorld.compile, helloWorldMapping.value)
        assert(freshCount > 0)

        write.append(mainObject, "\n")

        val Right((_, incCompileCount)) =
          eval(HelloWorld.compile, helloWorldMapping.value)
        assert(incCompileCount > 0, incCompileCount < freshCount)
      }
      'failOnError - {
        write.append(mainObject, "val x: ")

        val Left(Result.Exception(err)) =
          eval(HelloWorld.compile, helloWorldMapping.value)

        assert(err.isInstanceOf[CompileFailed])

        val (compilePath, compileMetadataPath) =
          TestEvaluator.resolveDestPaths(outputPath)(
            helloWorldMapping.value(HelloWorld.compile))

        assert(
          ls.rec(compilePath / 'classes).isEmpty,
          !exists(compileMetadataPath)
        )
      }
      'passScalacOptions - {
        // compilation fails because of "-Xfatal-warnings" flag
        val Left(Result.Exception(err)) =
          eval(HelloWorldFatalWarnings.compile,
               Discovered.mapping(HelloWorldFatalWarnings).value)

        assert(err.isInstanceOf[CompileFailed])
      }
    }
    'runMain - {
      'runMainObject - {
        val Right((_, evalCount)) =
          eval(HelloWorld.runMain("Main"), helloWorldMapping.value)

        assert(evalCount > 0)

        val runResult = workspacePath / "hello-mill"
        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      'notRunInvalidMainObject - {
        val Left(Result.Exception(err)) =
          eval(HelloWorld.runMain("Invalid"), helloWorldMapping.value)

        assert(
          err.isInstanceOf[InteractiveShelloutException]
        )
      }
      'notRunWhenComplileFailed - {
        write.append(mainObject, "val x: ")

        val Left(Result.Exception(err)) =
          eval(HelloWorld.runMain("Main"), helloWorldMapping.value)

        assert(
          err.isInstanceOf[CompileFailed]
        )
      }
    }
    'run - {
      'runIfMainClassProvided - {
        val Right((_, evalCount)) =
          eval(HelloWorldWithMain.run(), helloWorldWithMainMapping.value)

        assert(evalCount > 0)

        val runResult = workspacePath / "hello-mill"
        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      'notRunWithoutMainClass - {
        val Left(Result.Exception(err)) =
          eval(HelloWorld.run(), helloWorldMapping.value)

        assert(
          err.isInstanceOf[RuntimeException]
        )
      }
    }
    'jar - {
      'nonEmpty - {
        val Right((result, evalCount)) =
          eval(HelloWorld.jar, helloWorldMapping.value)

        assert(
          exists(result.path),
          evalCount > 0
        )

        val unJarPath = outputPath / 'unjar
        mkdir(unJarPath)
        %("tar", "xf", result.path, "-C", unJarPath)

        val manifestFiles = Seq(
          unJarPath / "META-INF",
          unJarPath / "META-INF" / "MANIFEST.MF"
        )
        val expectedFiles = compileClassfiles(unJarPath) ++ manifestFiles

        val jarFiles = ls.rec(unJarPath)
        assert(
          jarFiles.nonEmpty,
          jarFiles.forall(expectedFiles.contains)
        )
      }
      'runJar - {
        val Right((result, evalCount)) =
          eval(HelloWorldWithMain.jar, helloWorldWithMainMapping.value)

        assert(
          exists(result.path),
          evalCount > 0
        )

        %("scala", result.path)

        val runResult = workspacePath / "hello-mill"
        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      'logOutputToFile {
        eval(HelloWorld.compile, helloWorldMapping.value)

        val logFile = outputPath / "compile.log"
        assert(exists(logFile))
      }
    }
  }

  def compileClassfiles(parentDir: Path) = Seq(
    parentDir / "Main.class",
    parentDir / "Main$.class",
    parentDir / "Main$delayedInit$body.class",
    parentDir / "Person.class",
    parentDir / "Person$.class"
  )

  def prepareWorkspace(): Unit = {
    rm(workspacePath)
    mkdir(workspacePath / up)
    cp(srcPath, workspacePath)
  }

}

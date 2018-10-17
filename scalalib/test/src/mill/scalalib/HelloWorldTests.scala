package mill.scalalib

import java.util.jar.JarFile

import ammonite.ops._
import mill._
import mill.define.Target
import mill.eval.Result.Exception
import mill.eval.{Evaluator, Result}
import mill.modules.Assembly
import mill.scalalib.publish._
import mill.util.{TestEvaluator, TestUtil}
import mill.scalalib.publish.VersionControl
import utest._
import utest.framework.TestPath

import scala.collection.JavaConverters._
import scala.util.Properties.isJavaAtLeast


object HelloWorldTests extends TestSuite {
  trait HelloBase extends TestUtil.BaseModule{
    def millSourcePath =  TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  trait HelloWorldModule extends scalalib.ScalaModule {
    def scalaVersion = "2.12.4"
  }

  trait HelloWorldModuleWithMain extends HelloWorldModule {
    def mainClass = Some("Main")
  }

  object HelloWorld extends HelloBase {
    object core extends HelloWorldModule
  }
  object CrossHelloWorld extends HelloBase {
    object core extends Cross[HelloWorldCross]("2.10.6", "2.11.11", "2.12.3", "2.12.4", "2.13.0-M3")
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
    object core extends HelloWorldModuleWithMain
  }

  val akkaHttpDeps = Agg(ivy"com.typesafe.akka::akka-http:10.0.13")

  object HelloWorldAkkaHttpAppend extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      def ivyDeps = akkaHttpDeps

      def assemblyRules = Seq(Assembly.Rule.Append("reference.conf"))
    }
  }

  object HelloWorldAkkaHttpExclude extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      def ivyDeps = akkaHttpDeps

      def assemblyRules = Seq(Assembly.Rule.Exclude("reference.conf"))
    }
  }

  object HelloWorldAkkaHttpAppendPattern extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      def ivyDeps = akkaHttpDeps

      def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf"))
    }
  }

  object HelloWorldAkkaHttpExcludePattern extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      def ivyDeps = akkaHttpDeps

      def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.conf"))
    }
  }

  object HelloWorldAkkaHttpNoRules extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      def ivyDeps = akkaHttpDeps

      def assemblyRules = Seq.empty
    }
  }

  object HelloWorldMultiAppend extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      def moduleDeps = Seq(model)

      def assemblyRules = Seq(Assembly.Rule.Append("reference.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiExclude extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      def moduleDeps = Seq(model)

      def assemblyRules = Seq(Assembly.Rule.Exclude("reference.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiAppendPattern extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      def moduleDeps = Seq(model)

      def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiExcludePattern extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      def moduleDeps = Seq(model)

      def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiNoRules extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      def moduleDeps = Seq(model)

      def assemblyRules = Seq.empty
    }
    object model extends HelloWorldModule
  }

  object HelloWorldWarnUnused extends HelloBase {
    object core extends HelloWorldModule {
      def scalacOptions = T(Seq("-Ywarn-unused"))
    }
  }

  object HelloWorldFatalWarnings extends HelloBase {
    object core extends HelloWorldModule {
      def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
    }
  }

  object HelloWorldWithDocVersion extends HelloBase {
    object core extends HelloWorldModule {
      def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
      def scalaDocOptions = super.scalaDocOptions() ++ Seq("-doc-version", "1.2.3")
    }
  }

  object HelloWorldOnlyDocVersion extends HelloBase {
    object core extends HelloWorldModule {
      def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
      def scalaDocOptions = T(Seq("-doc-version", "1.2.3"))
    }
  }

  object HelloWorldDocTitle extends HelloBase {
    object core extends HelloWorldModule {
      def scalaDocOptions = T(Seq("-doc-title", "Hello World"))
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
        licenses = Seq(License.Common.Apache2),
        versionControl = VersionControl.github("lihaoyi", "hello-world-publish"),
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

  object HelloWorldIvyDeps extends HelloBase{
    object moduleA extends HelloWorldModule {

      override def ivyDeps = Agg(ivy"com.lihaoyi::sourcecode:0.1.3")
    }
    object moduleB extends HelloWorldModule {
      override def moduleDeps = Seq(moduleA)
      override def ivyDeps = Agg(ivy"com.lihaoyi::sourcecode:0.1.4")
    }
  }

  object HelloWorldTypeLevel extends HelloBase{
    object foo extends ScalaModule {
      def scalaVersion = "2.11.8"
      override def scalaOrganization = "org.typelevel"

      def ivyDeps = Agg(
        ivy"com.github.julien-truffaut::monocle-macro::1.4.0"
      )
      def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(
        ivy"org.scalamacros:::paradise:2.1.0"
      )
      def scalaDocPluginIvyDeps = super.scalaDocPluginIvyDeps() ++ Agg(
        ivy"com.typesafe.genjavadoc:::genjavadoc-plugin:0.11"
      )
    }
  }

  object HelloWorldMacros extends HelloBase{
    object core extends ScalaModule {
      def scalaVersion = "2.12.4"

      def ivyDeps = Agg(
        ivy"com.github.julien-truffaut::monocle-macro::1.4.0"
      )
      def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(
        ivy"org.scalamacros:::paradise:2.1.0"
      )
    }
  }

  object HelloWorldFlags extends HelloBase{
    object core extends ScalaModule {
      def scalaVersion = "2.12.4"
      
      def scalacOptions = super.scalacOptions() ++ Seq(
        "-Ypartial-unification"
      )
    }
  }

  object HelloScalacheck extends HelloBase{
    object foo extends ScalaModule {
      def scalaVersion = "2.12.4"
      object test extends Tests {
        def ivyDeps     = Agg(ivy"org.scalacheck::scalacheck:1.13.5")
        def testFrameworks = Seq("org.scalacheck.ScalaCheckFramework")
      }
    }
  }

  object HelloDotty extends HelloBase{
    object foo extends ScalaModule {
      def scalaVersion = "0.9.0-RC1"
      def ivyDeps = Agg(ivy"org.typelevel::cats-core:1.2.0".withDottyCompat(scalaVersion()))
     }
  }

  val resourcePath = pwd / 'scalalib / 'test / 'resources / "hello-world"

  def jarMainClass(jar: JarFile): Option[String] = {
    import java.util.jar.Attributes._
    val attrs = jar.getManifest.getMainAttributes.asScala
    attrs.get(Name.MAIN_CLASS).map(_.asInstanceOf[String])
  }

  def jarEntries(jar: JarFile): Set[String] = {
    jar.entries().asScala.map(_.getName).toSet
  }

  def readFileFromJar(jar: JarFile, name: String): String = {
    val is = jar.getInputStream(jar.getEntry(name))
    read(is)
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

  def workspaceTest[T](m: TestUtil.BaseModule, resourcePath: Path = resourcePath)
                      (t: TestEvaluator => T)
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

    'scalaDocOptions - {
      'emptyByDefault - workspaceTest(HelloWorld){eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorld.core.scalaDocOptions)
        assert(
          result.isEmpty,
          evalCount > 0
        )
      }
      'override - workspaceTest(HelloWorldDocTitle){ eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldDocTitle.core.scalaDocOptions)
        assert(
          result == Seq("-doc-title", "Hello World"),
          evalCount > 0
        )
      }
      'extend - workspaceTest(HelloWorldWithDocVersion){ eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithDocVersion.core.scalaDocOptions)
        assert(
          result == Seq("-Ywarn-unused", "-Xfatal-warnings", "-doc-version", "1.2.3"),
          evalCount > 0
        )
      }
      // make sure options are passed during ScalaDoc generation
      'docJarWithTitle - workspaceTest(
        HelloWorldDocTitle,
        resourcePath = pwd / 'scalalib / 'test / 'resources / "hello-world"
      ){ eval =>
        val Right((_, evalCount)) = eval.apply(HelloWorldDocTitle.core.docJar)
        assert(
          evalCount > 0,
          read(eval.outPath / 'core / 'docJar / 'dest / 'javadoc / "index.html").contains("<span id=\"doc-title\">Hello World")
        )
      }
      'docJarWithVersion - workspaceTest(
        HelloWorldWithDocVersion,
        resourcePath = pwd / 'scalalib / 'test / 'resources / "hello-world"
      ){ eval =>
        // scaladoc generation fails because of "-Xfatal-warnings" flag
        val Left(Result.Failure("docJar generation failed", None)) = eval.apply(HelloWorldWithDocVersion.core.docJar)
      }
      'docJarOnlyVersion - workspaceTest(
        HelloWorldOnlyDocVersion,
        resourcePath = pwd / 'scalalib / 'test / 'resources / "hello-world"
      ){ eval =>
        val Right((_, evalCount)) = eval.apply(HelloWorldOnlyDocVersion.core.docJar)
        assert(
          evalCount > 0,
          read(eval.outPath / 'core / 'docJar / 'dest / 'javadoc / "index.html").contains("<span id=\"doc-version\">1.2.3")
        )
      }
    }

    'scalacPluginClasspath - {
      'withMacroParadise - workspaceTest(HelloWorldTypeLevel){eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldTypeLevel.foo.scalacPluginClasspath)
        assert(
          result.nonEmpty,
          result.exists { pathRef => pathRef.path.segments.contains("scalamacros") },
          evalCount > 0
        )
      }
    }

    'scalaDocPluginClasspath - {
      'extend - workspaceTest(HelloWorldTypeLevel){eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldTypeLevel.foo.scalaDocPluginClasspath)
        assert(
          result.nonEmpty,
          result.exists { pathRef => pathRef.path.segments.contains("scalamacros") },
          result.exists { pathRef => pathRef.path.segments.contains("genjavadoc") },
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
        def cross(eval: TestEvaluator, v: String, expectedOut: String) {

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
        'v210 - TestUtil.disableInJava9OrAbove(workspaceTest(CrossHelloWorld)(cross(_, "2.10.6", "2.10.6 rox")))
        'v211 - TestUtil.disableInJava9OrAbove(workspaceTest(CrossHelloWorld)(cross(_, "2.11.11", "2.11.11 pwns")))
        'v2123 - workspaceTest(CrossHelloWorld)(cross(_, "2.12.3", "2.12.3 leet"))
        'v2124 - workspaceTest(CrossHelloWorld)(cross(_, "2.12.4", "2.12.4 leet"))
        'v2130M3 - workspaceTest(CrossHelloWorld)(cross(_, "2.13.0-M3", "2.13.0-M3 idk"))
      }


      'notRunInvalidMainObject - workspaceTest(HelloWorld){eval =>
        val Left(Result.Failure("subprocess failed", _)) = eval.apply(HelloWorld.core.runMain("Invalid"))
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

        val otherFiles = Seq[RelPath](
          "META-INF" / "MANIFEST.MF",
          "reference.conf"
        )
        val expectedFiles = compileClassfiles ++ otherFiles

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
      'assembly - workspaceTest(HelloWorldWithMain){ eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithMain.core.assembly)
        assert(
          exists(result.path),
          evalCount > 0
        )
        val jarFile = new JarFile(result.path.toIO)
        val entries = jarEntries(jarFile)

        val mainPresent = entries.contains("Main.class")
        assert(mainPresent)
        assert(entries.exists(s => s.contains("scala/Predef.class")))

        val mainClass = jarMainClass(jarFile)
        assert(mainClass.contains("Main"))
      }

      'assemblyRules - {
        def checkAppend[M <: TestUtil.BaseModule](module: M,
                                                  target: Target[PathRef]) =
          workspaceTest(module) { eval =>
            val Right((result, _)) = eval.apply(target)

            val jarFile = new JarFile(result.path.toIO)

            assert(jarEntries(jarFile).contains("reference.conf"))

            val referenceContent = readFileFromJar(jarFile, "reference.conf")

            assert(
              // akka modules configs are present
              referenceContent.contains("akka-http Reference Config File"),
              referenceContent.contains("akka-http-core Reference Config File"),
              referenceContent.contains("Akka Actor Reference Config File"),
              referenceContent.contains("Akka Stream Reference Config File"),
              // our application config is present too
              referenceContent.contains("My application Reference Config File"),
              referenceContent.contains(
                """akka.http.client.user-agent-header="hello-world-client""""
              )
            )
          }

        val helloWorldMultiResourcePath = pwd / 'scalalib / 'test / 'resources / "hello-world-multi"

        def checkAppendMulti[M <: TestUtil.BaseModule](
            module: M,
            target: Target[PathRef]) =
          workspaceTest(
            module,
            resourcePath = helloWorldMultiResourcePath
          ) { eval =>
            val Right((result, _)) = eval.apply(target)

            val jarFile = new JarFile(result.path.toIO)

            assert(jarEntries(jarFile).contains("reference.conf"))

            val referenceContent = readFileFromJar(jarFile, "reference.conf")

            assert(
              // reference config from core module
              referenceContent.contains("Core Reference Config File"),
              // reference config from model module
              referenceContent.contains("Model Reference Config File"),
              // concatenated content
              referenceContent.contains("bar.baz=hello"),
              referenceContent.contains("foo.bar=2")
            )
          }

        'appendWithDeps - checkAppend(
          HelloWorldAkkaHttpAppend,
          HelloWorldAkkaHttpAppend.core.assembly
        )
        'appendMultiModule - checkAppendMulti(
          HelloWorldMultiAppend,
          HelloWorldMultiAppend.core.assembly
        )
        'appendPatternWithDeps - checkAppend(
          HelloWorldAkkaHttpAppendPattern,
          HelloWorldAkkaHttpAppendPattern.core.assembly
        )
        'appendPatternMultiModule - checkAppendMulti(
          HelloWorldMultiAppendPattern,
          HelloWorldMultiAppendPattern.core.assembly
        )

        def checkExclude[M <: TestUtil.BaseModule](module: M,
                                                   target: Target[PathRef],
                                                   resourcePath: Path = resourcePath
                                                  ) =
          workspaceTest(module, resourcePath) { eval =>
            val Right((result, _)) = eval.apply(target)

            val jarFile = new JarFile(result.path.toIO)

            assert(!jarEntries(jarFile).contains("reference.conf"))
          }

        'excludeWithDeps - checkExclude(
          HelloWorldAkkaHttpExclude,
          HelloWorldAkkaHttpExclude.core.assembly
        )
        'excludeMultiModule - checkExclude(
          HelloWorldMultiExclude,
          HelloWorldMultiExclude.core.assembly,
          resourcePath = helloWorldMultiResourcePath

        )
        'excludePatternWithDeps - checkExclude(
          HelloWorldAkkaHttpExcludePattern,
          HelloWorldAkkaHttpExcludePattern.core.assembly
        )
        'excludePatternMultiModule - checkExclude(
          HelloWorldMultiExcludePattern,
          HelloWorldMultiExcludePattern.core.assembly,
          resourcePath = helloWorldMultiResourcePath
        )

        'writeFirstWhenNoRule - {
          'withDeps - workspaceTest(HelloWorldAkkaHttpNoRules) { eval =>
            val Right((result, _)) = eval.apply(HelloWorldAkkaHttpNoRules.core.assembly)

            val jarFile = new JarFile(result.path.toIO)

            assert(jarEntries(jarFile).contains("reference.conf"))

            val referenceContent = readFileFromJar(jarFile, "reference.conf")

            val allOccurrences = Seq(
              referenceContent.contains("akka-http Reference Config File"),
              referenceContent.contains("akka-http-core Reference Config File"),
              referenceContent.contains("Akka Actor Reference Config File"),
              referenceContent.contains("Akka Stream Reference Config File"),
              referenceContent.contains("My application Reference Config File")
            )

            val timesOcccurres = allOccurrences.find(identity).size

            assert(timesOcccurres == 1)
          }

          'multiModule - workspaceTest(
            HelloWorldMultiNoRules,
            resourcePath = helloWorldMultiResourcePath
          ) { eval =>
            val Right((result, _)) = eval.apply(HelloWorldMultiNoRules.core.assembly)

            val jarFile = new JarFile(result.path.toIO)

            assert(jarEntries(jarFile).contains("reference.conf"))

            val referenceContent = readFileFromJar(jarFile, "reference.conf")

            assert(
              referenceContent.contains("Model Reference Config File"),
              referenceContent.contains("foo.bar=2"),

              !referenceContent.contains("Core Reference Config File"),
              !referenceContent.contains("bar.baz=hello")
            )
          }
        }
      }

      'run - workspaceTest(HelloWorldWithMain){eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithMain.core.assembly)

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

    'ivyDeps - workspaceTest(HelloWorldIvyDeps){ eval =>
      val Right((result, _)) = eval.apply(HelloWorldIvyDeps.moduleA.runClasspath)
      assert(
        result.exists(_.path.last == "sourcecode_2.12-0.1.3.jar"),
        !result.exists(_.path.last == "sourcecode_2.12-0.1.4.jar")
      )

      val Right((result2, _)) = eval.apply(HelloWorldIvyDeps.moduleB.runClasspath)
      assert(
        result2.exists(_.path.last == "sourcecode_2.12-0.1.4.jar"),
        !result2.exists(_.path.last == "sourcecode_2.12-0.1.3.jar")
      )
    }

    'typeLevel - workspaceTest(HelloWorldTypeLevel){ eval =>
      val classPathsToCheck = Seq(
        HelloWorldTypeLevel.foo.runClasspath,
        HelloWorldTypeLevel.foo.ammoniteReplClasspath,
        HelloWorldTypeLevel.foo.compileClasspath
      )
      for(cp <- classPathsToCheck){
        val Right((result, _)) = eval.apply(cp)
        assert(
          // Make sure every relevant piece org.scala-lang has been substituted for org.typelevel
          !result.map(_.toString).exists(x =>
            x.contains("scala-lang") &&
            (x.contains("scala-library") || x.contains("scala-compiler") || x.contains("scala-reflect"))
          ),
          result.map(_.toString).exists(x => x.contains("typelevel") && x.contains("scala-library"))

        )
      }
    }

    'macros - {
      // make sure macros are applied when compiling/running
      'runMain - workspaceTest(
        HelloWorldMacros,
        resourcePath = pwd / 'scalalib / 'test / 'resources / "hello-world-macros"
      ){ eval =>
        val Right((_, evalCount)) = eval.apply(HelloWorldMacros.core.runMain("Main"))
        assert(evalCount > 0)
      }
      // make sure macros are applied when compiling during scaladoc generation
      'docJar - workspaceTest(
        HelloWorldMacros,
        resourcePath = pwd / 'scalalib / 'test / 'resources / "hello-world-macros"
      ){ eval =>
        val Right((_, evalCount)) = eval.apply(HelloWorldMacros.core.docJar)
        assert(evalCount > 0)
      }
    }

    'flags - {
      // make sure flags are passed when compiling/running
      'runMain - workspaceTest(
        HelloWorldFlags,
        resourcePath = pwd / 'scalalib / 'test / 'resources / "hello-world-flags"
      ){ eval =>
        val Right((_, evalCount)) = eval.apply(HelloWorldFlags.core.runMain("Main"))
        assert(evalCount > 0)
      }
      // make sure flags are passed during ScalaDoc generation
      'docJar - workspaceTest(
        HelloWorldFlags,
        resourcePath = pwd / 'scalalib / 'test / 'resources / "hello-world-flags"
      ){ eval =>
        val Right((_, evalCount)) = eval.apply(HelloWorldFlags.core.docJar)
        assert(evalCount > 0)
      }
    }

    'scalacheck - workspaceTest(
      HelloScalacheck,
      resourcePath = pwd / 'scalalib / 'test / 'resources / "hello-scalacheck"
    ){ eval =>
      val Right((res, evalCount)) = eval.apply(HelloScalacheck.foo.test.test())
      assert(
        evalCount > 0,
        res._2.map(_.selector) == Seq(
          "String.startsWith",
          "String.endsWith",
          "String.substring",
          "String.substring"
        )
      )
    }

    'dotty - workspaceTest(
      HelloDotty,
      resourcePath = pwd / 'scalalib / 'test / 'resources / "hello-dotty"
    ){ eval =>
      if (isJavaAtLeast("9")) {
        // Skip the test because Dotty does not support Java >= 9 yet
        // (see https://github.com/lampepfl/dotty/pull/3138)
      } else {
        val Right((_, evalCount)) = eval.apply(HelloDotty.foo.run())
        assert(evalCount > 0)
      }
    }
  }
}

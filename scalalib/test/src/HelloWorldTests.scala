package mill.scalalib

import java.io.ByteArrayOutputStream
import java.util.jar.JarFile
import scala.jdk.CollectionConverters._
import scala.util.{Properties, Using}
import scala.xml.NodeSeq
import mill._
import mill.api.Result
import mill.define.{Input, Target}
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.modules.Assembly
import mill.scalalib.publish.{VersionControl, _}
import mill.util.{TestEvaluator, TestUtil}
import os.{RelPath, SubPath}
import utest._
import utest.framework.TestPath

object HelloWorldTests extends TestSuite {

  val scala210Version = sys.props.getOrElse("TEST_SCALA_2_10_VERSION", ???)
  val scala211Version = sys.props.getOrElse("TEST_SCALA_2_11_VERSION", ???)
  val scala2123Version = "2.12.3"
  val scala212Version = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
  val scala213Version = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

  trait HelloBase extends TestUtil.BaseModule {
    override def millSourcePath: os.Path =
      TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  trait HelloWorldModule extends scalalib.ScalaModule {
    def scalaVersion = scala212Version
    override def semanticDbVersion: Input[String] = T.input {
      // The latest semanticDB release for Scala 2.12.6
      "4.1.9"
    }
  }

  trait HelloWorldModuleWithMain extends HelloWorldModule {
    override def mainClass: T[Option[String]] = Some("Main")
  }

  object HelloWorld extends HelloBase {
    object core extends HelloWorldModule
  }
  object CrossHelloWorld extends HelloBase {
    object core extends Cross[HelloWorldCross](
          scala210Version,
          scala211Version,
          scala2123Version,
          scala212Version,
          scala213Version
        )
    class HelloWorldCross(val crossScalaVersion: String) extends CrossScalaModule
  }

  object HelloWorldDefaultMain extends HelloBase {
    object core extends HelloWorldModule
  }

  object HelloWorldWithoutMain extends HelloBase {
    object core extends HelloWorldModule {
      override def mainClass = None
    }
  }

  object HelloWorldWithMain extends HelloBase {
    object core extends HelloWorldModuleWithMain
  }

  val akkaHttpDeps = Agg(ivy"com.typesafe.akka::akka-http:10.0.13")

  object HelloWorldAkkaHttpAppend extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Append("reference.conf"))
    }
  }

  object HelloWorldAkkaHttpExclude extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Exclude("reference.conf"))
    }
  }

  object HelloWorldAkkaHttpAppendPattern extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf"))
    }
  }

  object HelloWorldAkkaHttpExcludePattern extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.conf"))
    }
  }

  object HelloWorldAkkaHttpRelocate extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Relocate("akka.**", "shaded.akka.@1"))
    }
  }

  object HelloWorldAkkaHttpNoRules extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq.empty
    }
  }

  object HelloWorldMultiAppend extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.Append("reference.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiExclude extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.Exclude("reference.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiAppendPattern extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiAppendByPatternWithSeparator extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf", "\n"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiExcludePattern extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiNoRules extends HelloBase {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq.empty
    }
    object model extends HelloWorldModule
  }

  object HelloWorldWarnUnused extends HelloBase {
    object core extends HelloWorldModule {
      override def scalacOptions = T(Seq("-Ywarn-unused"))
    }
  }

  object HelloWorldFatalWarnings extends HelloBase {
    object core extends HelloWorldModule {
      override def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
    }
  }

  object HelloWorldWithDocVersion extends HelloBase {
    object core extends HelloWorldModule {
      override def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
      override def scalaDocOptions = super.scalaDocOptions() ++ Seq("-doc-version", "1.2.3")
    }
  }

  object HelloWorldOnlyDocVersion extends HelloBase {
    object core extends HelloWorldModule {
      override def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
      override def scalaDocOptions = T(Seq("-doc-version", "1.2.3"))
    }
  }

  object HelloWorldDocTitle extends HelloBase {
    object core extends HelloWorldModule {
      override def scalaDocOptions = T(Seq("-doc-title", "Hello World"))
    }
  }

  object HelloWorldWithPublish extends HelloBase {
    object core extends HelloWorldModule with PublishModule {
      override def artifactName = "hello-world"
      override def publishVersion = "0.0.1"
      override def pomSettings = PomSettings(
        organization = "com.lihaoyi",
        description = "hello world ready for real world publishing",
        url = "https://github.com/lihaoyi/hello-world-publish",
        licenses = Seq(License.Common.Apache2),
        versionControl = VersionControl.github("lihaoyi", "hello-world-publish"),
        developers =
          Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
      )
      override def versionScheme = Some(VersionScheme.EarlySemVer)

      def checkSonatypeCreds(sonatypeCreds: String) = T.command {
        PublishModule.checkSonatypeCreds(sonatypeCreds)
      }
    }
  }

  object HelloWorldScalaOverride extends HelloBase {
    object core extends HelloWorldModule {
      override def scalaVersion: Target[String] = scala213Version
    }
  }

  object HelloWorldIvyDeps extends HelloBase {
    object moduleA extends HelloWorldModule {
      override def ivyDeps = Agg(ivy"com.lihaoyi::sourcecode:0.1.3")
    }
    object moduleB extends HelloWorldModule {
      override def moduleDeps = Seq(moduleA)
      override def ivyDeps = Agg(ivy"com.lihaoyi::sourcecode:0.1.4")
    }
  }

  object HelloWorldTypeLevel extends HelloBase {
    object foo extends ScalaModule {
      override def scalaVersion = "2.11.8"
      override def scalaOrganization = "org.typelevel"
      override def ammoniteVersion = "1.6.7"

      override def ivyDeps = Agg(
        ivy"com.github.julien-truffaut::monocle-macro::1.4.0"
      )
      override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(
        ivy"org.scalamacros:::paradise:2.1.0"
      )
      override def scalaDocPluginIvyDeps = super.scalaDocPluginIvyDeps() ++ Agg(
        ivy"com.typesafe.genjavadoc:::genjavadoc-plugin:0.11"
      )
    }
  }

  object HelloWorldMacros212 extends HelloBase {
    object core extends ScalaModule {
      override def scalaVersion = scala212Version
      override def ivyDeps = Agg(
        ivy"com.github.julien-truffaut::monocle-macro::1.6.0"
      )
      override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(
        ivy"org.scalamacros:::paradise:2.1.0"
      )
    }
  }

  object HelloWorldMacros213 extends HelloBase {
    object core extends ScalaModule {
      override def scalaVersion = scala213Version
      override def ivyDeps = Agg(ivy"com.github.julien-truffaut::monocle-macro::2.1.0")
      override def scalacOptions = super.scalacOptions() ++ Seq("-Ymacro-annotations")
    }
  }

  object HelloWorldFlags extends HelloBase {
    object core extends ScalaModule {
      def scalaVersion = scala212Version

      override def scalacOptions = super.scalacOptions() ++ Seq(
        "-Ypartial-unification"
      )
    }
  }

  object HelloScalacheck extends HelloBase {
    object foo extends ScalaModule {
      def scalaVersion = scala212Version
      object test extends Tests {
        override def ivyDeps = Agg(ivy"org.scalacheck::scalacheck:1.13.5")
        override def testFramework = "org.scalacheck.ScalaCheckFramework"
      }
    }
  }

  object Dotty213 extends HelloBase {
    object foo extends ScalaModule {
      def scalaVersion = "0.18.1-RC1"
      override def ivyDeps =
        Agg(ivy"org.scala-lang.modules::scala-xml:1.2.0".withDottyCompat(scalaVersion()))
    }
  }

  object AmmoniteReplMainClass extends HelloBase {
    object oldAmmonite extends ScalaModule {
      override def scalaVersion = T("2.13.5")
      override def ammoniteVersion = T("2.4.1")
    }
    object newAmmonite extends ScalaModule {
      override def scalaVersion = T("2.13.5")
      override def ammoniteVersion = T("2.5.0")
    }
  }

  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-world"

  def jarMainClass(jar: JarFile): Option[String] = {
    import java.util.jar.Attributes._
    val attrs = jar.getManifest.getMainAttributes.asScala
    attrs.get(Name.MAIN_CLASS).map(_.asInstanceOf[String])
  }

  def jarEntries(jar: JarFile): Set[String] = {
    jar.entries().asScala.map(_.getName).toSet
  }

  def readFileFromJar(jar: JarFile, name: String): String = {
    Using.resource(jar.getInputStream(jar.getEntry(name))) { is =>
      val baos = new ByteArrayOutputStream()
      os.Internals.transfer(is, baos)
      new String(baos.toByteArray)
    }
  }

  def compileClassfiles: Seq[os.RelPath] = Seq(
    os.rel / "Main.class",
    os.rel / "Main$.class",
    os.rel / "Main0.class",
    os.rel / "Main0$.class",
    os.rel / "Main$delayedInit$body.class",
    os.rel / "Person.class",
    os.rel / "Person$.class"
  )
  def semanticDbFiles: Seq[os.SubPath] = Seq(
    os.sub / "core" / "src" / "Main.scala.semanticdb",
    os.sub / "core" / "src" / "Result.scala.semanticdb"
  ).map(os.sub / "META-INF" / "semanticdb" / _)

  def workspaceTest[T](
      m: TestUtil.BaseModule,
      resourcePath: os.Path = resourcePath,
      env: Map[String, String] = Evaluator.defaultEnv
  )(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m, env = env)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {
    "scalaVersion" - {

      "fromBuild" - workspaceTest(HelloWorld) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorld.core.scalaVersion)

        assert(
          result == scala212Version,
          evalCount > 0
        )
      }
      "override" - workspaceTest(HelloWorldScalaOverride) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldScalaOverride.core.scalaVersion)

        assert(
          result == scala213Version,
          evalCount > 0
        )
      }
    }

    "scalacOptions" - {
      "emptyByDefault" - workspaceTest(HelloWorld) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorld.core.scalacOptions)

        assert(
          result.isEmpty,
          evalCount > 0
        )
      }
      "override" - workspaceTest(HelloWorldFatalWarnings) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldFatalWarnings.core.scalacOptions)

        assert(
          result == Seq("-Ywarn-unused", "-Xfatal-warnings"),
          evalCount > 0
        )
      }
    }

    "scalaDocOptions" - {
      "emptyByDefault" - workspaceTest(HelloWorld) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorld.core.scalaDocOptions)
        assert(
          result.isEmpty,
          evalCount > 0
        )
      }
      "override" - workspaceTest(HelloWorldDocTitle) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldDocTitle.core.scalaDocOptions)
        assert(
          result == Seq("-doc-title", "Hello World"),
          evalCount > 0
        )
      }
      "extend" - workspaceTest(HelloWorldWithDocVersion) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithDocVersion.core.scalaDocOptions)
        assert(
          result == Seq("-Ywarn-unused", "-Xfatal-warnings", "-doc-version", "1.2.3"),
          evalCount > 0
        )
      }
      // make sure options are passed during ScalaDoc generation
      "docJarWithTitle" - workspaceTest(
        HelloWorldDocTitle,
        resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-world"
      ) { eval =>
        val Right((_, evalCount)) = eval.apply(HelloWorldDocTitle.core.docJar)
        assert(
          evalCount > 0,
          os.read(eval.outPath / "core" / "docJar.dest" / "javadoc" / "index.html").contains(
            "<span id=\"doc-title\">Hello World"
          )
        )
      }
      "docJarWithVersion" - workspaceTest(
        HelloWorldWithDocVersion,
        resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-world"
      ) { eval =>
        // scaladoc generation fails because of "-Xfatal-warnings" flag
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldWithDocVersion.core.docJar)
      }
      "docJarOnlyVersion" - workspaceTest(
        HelloWorldOnlyDocVersion,
        resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-world"
      ) { eval =>
        // `docJar` requires the `compile` task to succeed (since the addition of Scaladoc 3)
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldOnlyDocVersion.core.docJar)
      }
    }

    "scalacPluginClasspath" - {
      "withMacroParadise" - workspaceTest(HelloWorldTypeLevel) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldTypeLevel.foo.scalacPluginClasspath)
        assert(
          result.nonEmpty,
          result.iterator.exists { pathRef => pathRef.path.segments.contains("scalamacros") },
          evalCount > 0
        )
      }
    }

    "scalaDocPluginClasspath" - {
      "extend" - workspaceTest(HelloWorldTypeLevel) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldTypeLevel.foo.scalaDocPluginClasspath)
        assert(
          result.iterator.nonEmpty,
          result.iterator.exists { pathRef => pathRef.path.segments.contains("scalamacros") },
          result.iterator.exists { pathRef => pathRef.path.segments.contains("genjavadoc") },
          evalCount > 0
        )
      }
    }

    "compile" - {
      "fromScratch" - workspaceTest(HelloWorld) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorld.core.compile)

        val classesPath = eval.outPath / "core" / "compile.dest" / "classes"
        val analysisFile = result.analysisFile
        val outputFiles = os.walk(result.classes.path)
        val expectedClassfiles = compileClassfiles.map(classesPath / _)
        assert(
          result.classes.path == classesPath,
          os.exists(analysisFile),
          outputFiles.nonEmpty,
          outputFiles.forall(expectedClassfiles.contains),
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) = eval.apply(HelloWorld.core.compile)

        assert(unchangedEvalCount == 0)
      }
      "recompileOnChange" - workspaceTest(HelloWorld) { eval =>
        val Right((_, freshCount)) = eval.apply(HelloWorld.core.compile)
        assert(freshCount > 0)

        os.write.append(HelloWorld.millSourcePath / "core" / "src" / "Main.scala", "\n")

        val Right((_, incCompileCount)) = eval.apply(HelloWorld.core.compile)
        assert(incCompileCount > 0, incCompileCount < freshCount)
      }
      "failOnError" - workspaceTest(HelloWorld) { eval =>
        os.write.append(HelloWorld.millSourcePath / "core" / "src" / "Main.scala", "val x: ")

        val Left(Result.Failure("Compilation failed", _)) = eval.apply(HelloWorld.core.compile)

        val paths = EvaluatorPaths.resolveDestPaths(eval.outPath, HelloWorld.core.compile)

        assert(
          os.walk(paths.dest / "classes").isEmpty,
          !os.exists(paths.meta)
        )
        // Works when fixed
        os.write.over(
          HelloWorld.millSourcePath / "core" / "src" / "Main.scala",
          os.read(HelloWorld.millSourcePath / "core" / "src" / "Main.scala").dropRight(
            "val x: ".length
          )
        )

        val Right((result, evalCount)) = eval.apply(HelloWorld.core.compile)
      }
      "passScalacOptions" - workspaceTest(HelloWorldFatalWarnings) { eval =>
        // compilation fails because of "-Xfatal-warnings" flag
        val Left(Result.Failure("Compilation failed", _)) =
          eval.apply(HelloWorldFatalWarnings.core.compile)
      }
    }

    "semanticDbData" - {
      "fromScratch" - workspaceTest(HelloWorld) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorld.core.semanticDbData)

        val outputFiles = os.walk(result.path).filter(os.isFile)
        val dataPath = eval.outPath / "core" / "semanticDbData.dest" / "data"

        val expectedSemFiles = semanticDbFiles.map(dataPath / _)
        assert(
          result.path == dataPath,
          outputFiles.nonEmpty,
          outputFiles.forall(expectedSemFiles.contains),
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) = eval.apply(HelloWorld.core.semanticDbData)
        assert(unchangedEvalCount == 0)
      }
    }

    "artifactNameCross" - {
      workspaceTest(CrossHelloWorld) { eval =>
        val Right((artifactName, _)) =
          eval.apply(CrossHelloWorld.core(scala213Version).artifactName)
        assert(artifactName == "core")
      }
    }

    "runMain" - {
      "runMainObject" - workspaceTest(HelloWorld) { eval =>
        val runResult = eval.outPath / "core" / "runMain.dest" / "hello-mill"

        val Right((_, evalCount)) = eval.apply(HelloWorld.core.runMain("Main", runResult.toString))
        assert(evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      "runCross" - {
        def cross(eval: TestEvaluator, v: String, expectedOut: String): Unit = {

          val runResult = eval.outPath / "hello-mill"

          val Right((_, evalCount)) = eval.apply(
            CrossHelloWorld.core(v).runMain("Shim", runResult.toString)
          )

          assert(evalCount > 0)

          assert(
            os.exists(runResult),
            os.read(runResult) == expectedOut
          )
        }
        "v210" - TestUtil.disableInJava9OrAbove("Scala 2.10 tests don't work with Java 9+")(
          workspaceTest(CrossHelloWorld)(cross(
            _,
            scala210Version,
            s"${scala210Version} rox"
          ))
        )
        "v211" - TestUtil.disableInJava9OrAbove("Scala 2.11 tests don't work with Java 9+")(
          workspaceTest(CrossHelloWorld)(cross(
            _,
            scala211Version,
            s"${scala211Version} pwns"
          ))
        )
        "v2123" - workspaceTest(CrossHelloWorld)(cross(
          _,
          scala2123Version,
          s"${scala2123Version} leet"
        ))
        "v2124" - workspaceTest(CrossHelloWorld)(cross(
          _,
          scala212Version,
          s"${scala212Version} leet"
        ))
        "v2131" - workspaceTest(CrossHelloWorld)(cross(
          _,
          scala213Version,
          s"${scala213Version} idk"
        ))
      }

      "notRunInvalidMainObject" - workspaceTest(HelloWorld) { eval =>
        val Left(Result.Failure("subprocess failed", _)) =
          eval.apply(HelloWorld.core.runMain("Invalid"))
      }
      "notRunWhenCompileFailed" - workspaceTest(HelloWorld) { eval =>
        os.write.append(HelloWorld.millSourcePath / "core" / "src" / "Main.scala", "val x: ")

        val Left(Result.Failure("Compilation failed", _)) =
          eval.apply(HelloWorld.core.runMain("Main"))

      }
    }

    "forkRun" - {
      "runIfMainClassProvided" - workspaceTest(HelloWorldWithMain) { eval =>
        val runResult = eval.outPath / "core" / "run.dest" / "hello-mill"
        val Right((_, evalCount)) = eval.apply(
          HelloWorldWithMain.core.run(runResult.toString)
        )

        assert(evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      "notRunWithoutMainClass" - workspaceTest(
        HelloWorldWithoutMain,
        os.pwd / "scalalib" / "test" / "resources" / "hello-world-no-main"
      ) { eval =>
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldWithoutMain.core.run())
      }

      "runDiscoverMainClass" - workspaceTest(HelloWorldWithoutMain) { eval =>
        // Make sure even if there isn't a main class defined explicitly, it gets
        // discovered by Zinc and used
        val runResult = eval.outPath / "core" / "run.dest" / "hello-mill"
        val Right((_, evalCount)) = eval.apply(
          HelloWorldWithoutMain.core.run(runResult.toString)
        )

        assert(evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
    }

    "run" - {
      "runIfMainClassProvided" - workspaceTest(HelloWorldWithMain) { eval =>
        val runResult = eval.outPath / "core" / "run.dest" / "hello-mill"
        val Right((_, evalCount)) = eval.apply(
          HelloWorldWithMain.core.runLocal(runResult.toString)
        )

        assert(evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      "runWithDefaultMain" - workspaceTest(HelloWorldDefaultMain) { eval =>
        val runResult = eval.outPath / "core" / "run.dest" / "hello-mill"
        val Right((_, evalCount)) = eval.apply(
          HelloWorldDefaultMain.core.runLocal(runResult.toString)
        )

        assert(evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      "notRunWithoutMainClass" - workspaceTest(
        HelloWorldWithoutMain,
        os.pwd / "scalalib" / "test" / "resources" / "hello-world-no-main"
      ) { eval =>
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldWithoutMain.core.runLocal())

      }
    }

    "jar" - {
      "nonEmpty" - workspaceTest(HelloWorldWithMain) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithMain.core.jar)

        assert(
          os.exists(result.path),
          evalCount > 0
        )

        Using.resource(new JarFile(result.path.toIO)) { jarFile =>
          val entries = jarFile.entries().asScala.map(_.getName).toSeq.sorted

          val otherFiles = Seq(
            "META-INF/",
            "META-INF/MANIFEST.MF",
            "reference.conf"
          )
          val expectedFiles = (compileClassfiles.map(_.toString()) ++ otherFiles).sorted

          assert(
            entries.nonEmpty,
            entries == expectedFiles
          )

          val mainClass = jarMainClass(jarFile)
          assert(mainClass.contains("Main"))
        }
      }

      "logOutputToFile" - workspaceTest(HelloWorld) { eval =>
        val outPath = eval.outPath
        eval.apply(HelloWorld.core.compile)

        val logFile = outPath / "core" / "compile.log"
        assert(os.exists(logFile))
      }
    }

    "assembly" - {
      "assembly" - workspaceTest(HelloWorldWithMain) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithMain.core.assembly)
        assert(
          os.exists(result.path),
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

      "assemblyRules" - {
        def checkAppend[M <: TestUtil.BaseModule](module: M, target: Target[PathRef]) =
          workspaceTest(module) { eval =>
            val Right((result, _)) = eval.apply(target)

            Using.resource(new JarFile(result.path.toIO)) { jarFile =>
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
          }

        val helloWorldMultiResourcePath =
          os.pwd / "scalalib" / "test" / "resources" / "hello-world-multi"

        def checkAppendMulti[M <: TestUtil.BaseModule](
            module: M,
            target: Target[PathRef]
        ): Unit =
          workspaceTest(
            module,
            resourcePath = helloWorldMultiResourcePath
          ) { eval =>
            val Right((result, _)) = eval.apply(target)

            Using.resource(new JarFile(result.path.toIO)) { jarFile =>
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
          }

        def checkAppendWithSeparator[M <: TestUtil.BaseModule](
            module: M,
            target: Target[PathRef]
        ): Unit =
          workspaceTest(
            module,
            resourcePath = helloWorldMultiResourcePath
          ) { eval =>
            val Right((result, _)) = eval.apply(target)

            Using.resource(new JarFile(result.path.toIO)) { jarFile =>
              assert(jarEntries(jarFile).contains("without-new-line.conf"))

              val result = readFileFromJar(jarFile, "without-new-line.conf").split('\n').toSet
              val expected = Set("without-new-line.first=first", "without-new-line.second=second")
              assert(result == expected)
            }
          }

        "appendWithDeps" - checkAppend(
          HelloWorldAkkaHttpAppend,
          HelloWorldAkkaHttpAppend.core.assembly
        )
        "appendMultiModule" - checkAppendMulti(
          HelloWorldMultiAppend,
          HelloWorldMultiAppend.core.assembly
        )
        "appendPatternWithDeps" - checkAppend(
          HelloWorldAkkaHttpAppendPattern,
          HelloWorldAkkaHttpAppendPattern.core.assembly
        )
        "appendPatternMultiModule" - checkAppendMulti(
          HelloWorldMultiAppendPattern,
          HelloWorldMultiAppendPattern.core.assembly
        )
        "appendPatternMultiModuleWithSeparator" - checkAppendWithSeparator(
          HelloWorldMultiAppendByPatternWithSeparator,
          HelloWorldMultiAppendByPatternWithSeparator.core.assembly
        )

        def checkExclude[M <: TestUtil.BaseModule](
            module: M,
            target: Target[PathRef],
            resourcePath: os.Path = resourcePath
        ) =
          workspaceTest(module, resourcePath) { eval =>
            val Right((result, _)) = eval.apply(target)

            Using.resource(new JarFile(result.path.toIO)) { jarFile =>
              assert(!jarEntries(jarFile).contains("reference.conf"))
            }
          }

        "excludeWithDeps" - checkExclude(
          HelloWorldAkkaHttpExclude,
          HelloWorldAkkaHttpExclude.core.assembly
        )
        "excludeMultiModule" - checkExclude(
          HelloWorldMultiExclude,
          HelloWorldMultiExclude.core.assembly,
          resourcePath = helloWorldMultiResourcePath
        )
        "excludePatternWithDeps" - checkExclude(
          HelloWorldAkkaHttpExcludePattern,
          HelloWorldAkkaHttpExcludePattern.core.assembly
        )
        "excludePatternMultiModule" - checkExclude(
          HelloWorldMultiExcludePattern,
          HelloWorldMultiExcludePattern.core.assembly,
          resourcePath = helloWorldMultiResourcePath
        )

        def checkRelocate[M <: TestUtil.BaseModule](
            module: M,
            target: Target[PathRef],
            resourcePath: os.Path = resourcePath
        ) =
          workspaceTest(module, resourcePath) { eval =>
            val Right((result, _)) = eval.apply(target)
            Using.resource(new JarFile(result.path.toIO)) { jarFile =>
              assert(!jarEntries(jarFile).contains("akka/http/scaladsl/model/HttpEntity.class"))
              assert(
                jarEntries(jarFile).contains("shaded/akka/http/scaladsl/model/HttpEntity.class")
              )
            }
          }

        "relocate" - {
          "withDeps" - checkRelocate(
            HelloWorldAkkaHttpRelocate,
            HelloWorldAkkaHttpRelocate.core.assembly
          )

          "run" - workspaceTest(
            HelloWorldAkkaHttpRelocate,
            resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-world-deps"
          ) { eval =>
            val Right((_, evalCount)) = eval.apply(HelloWorldAkkaHttpRelocate.core.runMain("Main"))
            assert(evalCount > 0)
          }
        }

        "writeDownstreamWhenNoRule" - {
          "withDeps" - workspaceTest(HelloWorldAkkaHttpNoRules) { eval =>
            val Right((result, _)) = eval.apply(HelloWorldAkkaHttpNoRules.core.assembly)

            Using.resource(new JarFile(result.path.toIO)) { jarFile =>
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
          }

          "multiModule" - workspaceTest(
            HelloWorldMultiNoRules,
            resourcePath = helloWorldMultiResourcePath
          ) { eval =>
            val Right((result, _)) = eval.apply(HelloWorldMultiNoRules.core.assembly)

            Using.resource(new JarFile(result.path.toIO)) { jarFile =>
              assert(jarEntries(jarFile).contains("reference.conf"))

              val referenceContent = readFileFromJar(jarFile, "reference.conf")

              assert(
                !referenceContent.contains("Model Reference Config File"),
                !referenceContent.contains("foo.bar=2"),
                referenceContent.contains("Core Reference Config File"),
                referenceContent.contains("bar.baz=hello")
              )
            }
          }
        }
      }

      "run" - workspaceTest(HelloWorldWithMain) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithMain.core.assembly)

        assert(
          os.exists(result.path),
          evalCount > 0
        )
        val runResult = eval.outPath / "hello-mill"

        os.proc("java", "-jar", result.path, runResult).call(cwd = eval.outPath)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
    }

    "ivyDeps" - workspaceTest(HelloWorldIvyDeps) { eval =>
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

    "typeLevel" - workspaceTest(HelloWorldTypeLevel) { eval =>
      val classPathsToCheck = Seq(
        HelloWorldTypeLevel.foo.runClasspath,
        HelloWorldTypeLevel.foo.ammoniteReplClasspath,
        HelloWorldTypeLevel.foo.compileClasspath
      )
      for (cp <- classPathsToCheck) {
        val Right((result, _)) = eval.apply(cp)
        assert(
          // Make sure every relevant piece org.scala-lang has been substituted for org.typelevel
          !result.map(_.toString).exists(x =>
            x.contains("scala-lang") &&
              (x.contains("scala-library") || x.contains("scala-compiler") || x.contains(
                "scala-reflect"
              ))
          ),
          result.map(_.toString).exists(x => x.contains("typelevel") && x.contains("scala-library"))
        )
      }
    }

    "macros" - {
      "scala-2.12" - {
        // Scala 2.12 does not always work with Java 17+
        // make sure macros are applied when compiling/running
        val mod = HelloWorldMacros212
        "runMain" - workspaceTest(
          mod,
          resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-world-macros"
        ) { eval =>
          if (Properties.isJavaAtLeast(17)) "skipped on Java 17+"
          else {
            val Right((_, evalCount)) = eval.apply(mod.core.runMain("Main"))
            assert(evalCount > 0)
          }
        }
        // make sure macros are applied when compiling during scaladoc generation
        "docJar" - workspaceTest(
          mod,
          resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-world-macros"
        ) { eval =>
          if (Properties.isJavaAtLeast(17)) "skipped on Java 17+"
          else {
            val Right((_, evalCount)) = eval.apply(mod.core.docJar)
            assert(evalCount > 0)
          }
        }
      }
      "scala-2.13" - {
        // make sure macros are applied when compiling/running
        val mod = HelloWorldMacros213
        "runMain" - workspaceTest(
          mod,
          resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-world-macros"
        ) { eval =>
          val Right((_, evalCount)) = eval.apply(mod.core.runMain("Main"))
          assert(evalCount > 0)
        }
        // make sure macros are applied when compiling during scaladoc generation
        "docJar" - workspaceTest(
          mod,
          resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-world-macros"
        ) { eval =>
          val Right((_, evalCount)) = eval.apply(mod.core.docJar)
          assert(evalCount > 0)
        }
      }
    }

    "flags" - {
      // make sure flags are passed when compiling/running
      "runMain" - workspaceTest(
        HelloWorldFlags,
        resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-world-flags"
      ) { eval =>
        val Right((_, evalCount)) = eval.apply(HelloWorldFlags.core.runMain("Main"))
        assert(evalCount > 0)
      }
      // make sure flags are passed during ScalaDoc generation
      "docJar" - workspaceTest(
        HelloWorldFlags,
        resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-world-flags"
      ) { eval =>
        val Right((_, evalCount)) = eval.apply(HelloWorldFlags.core.docJar)
        assert(evalCount > 0)
      }
    }

    "scalacheck" - workspaceTest(
      HelloScalacheck,
      resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-scalacheck"
    ) { eval =>
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

    "dotty213" - workspaceTest(
      Dotty213,
      resourcePath = os.pwd / "scalalib" / "test" / "resources" / "dotty213"
    ) { eval =>
      val Right((_, evalCount)) = eval.apply(Dotty213.foo.run())
      assert(evalCount > 0)
    }

    "pom" - {
      "should include scala-library dependency" - workspaceTest(HelloWorldWithPublish) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithPublish.core.pom)

        assert(
          os.exists(result.path),
          evalCount > 0
        )

        val pomXml = scala.xml.XML.loadFile(result.path.toString)
        val scalaLibrary = pomXml \ "dependencies" \ "dependency"
        assert(
          (scalaLibrary \ "artifactId").text == "scala-library",
          (scalaLibrary \ "groupId").text == "org.scala-lang"
        )
      }
      "versionScheme" - workspaceTest(HelloWorldWithPublish) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithPublish.core.pom)

        assert(
          os.exists(result.path),
          evalCount > 0
        )

        val pomXml = scala.xml.XML.loadFile(result.path.toString)
        val versionScheme = pomXml \ "properties" \ "info.versionScheme"
        assert(versionScheme.text == "early-semver")
      }
    }

    "publish" - {
      "should retrieve credentials from environment variables if direct argument is empty" - workspaceTest(
        HelloWorldWithPublish,
        env = Evaluator.defaultEnv ++ Seq(
          "SONATYPE_USERNAME" -> "user",
          "SONATYPE_PASSWORD" -> "password"
        )
      ) { eval =>
        val Right((credentials, evalCount)) =
          eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds(""))

        assert(
          credentials == "user:password",
          evalCount > 0
        )
      }
      "should prefer direct argument as credentials over environment variables" - workspaceTest(
        HelloWorldWithPublish,
        env = Evaluator.defaultEnv ++ Seq(
          "SONATYPE_USERNAME" -> "user",
          "SONATYPE_PASSWORD" -> "password"
        )
      ) { eval =>
        val directValue = "direct:value"
        val Right((credentials, evalCount)) =
          eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds(directValue))

        assert(
          credentials == directValue,
          evalCount > 0
        )
      }
      "should throw exception if neither environment variables or direct argument were not passed" - workspaceTest(
        HelloWorldWithPublish
      ) { eval =>
        val Left(Result.Failure(msg, None)) =
          eval.apply(HelloWorldWithPublish.core.checkSonatypeCreds(""))

        assert(
          msg.contains("Consider using SONATYPE_USERNAME/SONATYPE_PASSWORD environment variables")
        )
      }
    }

    "ivy" - {
      "should include scala-library dependency" - workspaceTest(HelloWorldWithPublish) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorldWithPublish.core.ivy)

        assert(
          os.exists(result.path),
          evalCount > 0
        )

        val ivyXml = scala.xml.XML.loadFile(result.path.toString)
        val deps: NodeSeq = (ivyXml \ "dependencies" \ "dependency")
        assert(deps.exists(n =>
          (n \ "@conf").text == "compile->default(compile)" &&
            (n \ "@name").text == "scala-library" && (n \ "@org").text == "org.scala-lang"
        ))
      }
    }

    "replAmmoniteMainClass" - workspaceTest(AmmoniteReplMainClass) { eval =>
      val Right((oldResult, _)) = eval.apply(AmmoniteReplMainClass.oldAmmonite.ammoniteMainClass)
      assert(oldResult == "ammonite.Main")
      val Right((newResult, _)) = eval.apply(AmmoniteReplMainClass.newAmmonite.ammoniteMainClass)
      assert(newResult == "ammonite.AmmoniteMain")
    }
  }
}

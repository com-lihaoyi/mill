package mill.scalalib

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.jar.JarFile
import scala.jdk.CollectionConverters._
import scala.util.{Properties, Using}
import mill._
import mill.api.Result
import mill.define.NamedTask
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import mill.util.TestUtil
import utest._
import utest.framework.TestPath

object HelloWorldTests extends TestSuite {

  val scala2123Version = "2.12.3"
  val scala212Version = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
  val scala213Version = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  val scala32Version = sys.props.getOrElse("TEST_SCALA_3_2_VERSION", ???)
  val scala33Version = sys.props.getOrElse("TEST_SCALA_3_3_VERSION", ???)
  val zincVersion = sys.props.getOrElse("TEST_ZINC_VERSION", ???)

  trait HelloWorldModule extends scalalib.ScalaModule {
    def scalaVersion = scala212Version
    override def semanticDbVersion: T[String] = T {
      // The latest semanticDB release for Scala 2.12.6
      "4.1.9"
    }
  }
  trait SemanticModule extends scalalib.ScalaModule {
    def scalaVersion = scala213Version
  }
  trait HelloWorldModuleWithMain extends HelloWorldModule {
    override def mainClass: T[Option[String]] = Some("Main")
  }

  object HelloWorld extends TestBaseModule {
    object core extends HelloWorldModule
  }
  object SemanticWorld extends TestBaseModule {
    object core extends SemanticModule
  }
  object HelloWorldNonPrecompiledBridge extends TestBaseModule {
    object core extends HelloWorldModule {
      override def scalaVersion = "2.12.1"
    }
  }
  object CrossHelloWorld extends TestBaseModule {
    object core extends Cross[HelloWorldCross](
          scala2123Version,
          scala212Version,
          scala213Version
        )
    trait HelloWorldCross extends CrossScalaModule
  }
  object CrossModuleDeps extends TestBaseModule {
    object stable extends Cross[Stable](scala212Version, scala32Version)
    trait Stable extends CrossScalaModule

    object cuttingEdge extends Cross[CuttingEdge](scala213Version, scala33Version)
    trait CuttingEdge extends CrossScalaModule {
      def moduleDeps = Seq(stable())
    }
  }

  object HelloWorldDefaultMain extends TestBaseModule {
    object core extends HelloWorldModule
  }

  object HelloWorldWithoutMain extends TestBaseModule {
    object core extends HelloWorldModule {
      override def mainClass = None
    }
  }

  object HelloWorldWithMain extends TestBaseModule {
    object core extends HelloWorldModuleWithMain
  }

  val akkaHttpDeps = Agg(ivy"com.typesafe.akka::akka-http:10.0.13")

  object HelloWorldAkkaHttpAppend extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Append("reference.conf"))
    }
  }

  object HelloWorldAkkaHttpExclude extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Exclude("reference.conf"))
    }
  }

  object HelloWorldAkkaHttpAppendPattern extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf"))
    }
  }

  object HelloWorldAkkaHttpExcludePattern extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.conf"))
    }
  }

  object HelloWorldAkkaHttpRelocate extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Relocate("akka.**", "shaded.akka.@1"))
    }
  }

  object HelloWorldAkkaHttpNoRules extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq.empty
    }
  }

  object HelloWorldMultiAppend extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.Append("reference.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiExclude extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.Exclude("reference.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiAppendPattern extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiAppendByPatternWithSeparator extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf", "\n"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiExcludePattern extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiNoRules extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq.empty
    }
    object model extends HelloWorldModule
  }

  object HelloWorldWarnUnused extends TestBaseModule {
    object core extends HelloWorldModule {
      override def scalacOptions = T(Seq("-Ywarn-unused"))
    }
  }

  object HelloWorldFatalWarnings extends TestBaseModule {
    object core extends HelloWorldModule {
      override def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
    }
  }

  object HelloWorldWithDocVersion extends TestBaseModule {
    object core extends HelloWorldModule {
      override def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
      override def scalaDocOptions = super.scalaDocOptions() ++ Seq("-doc-version", "1.2.3")
    }
  }

  object HelloWorldOnlyDocVersion extends TestBaseModule {
    object core extends HelloWorldModule {
      override def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
      override def scalaDocOptions = T(Seq("-doc-version", "1.2.3"))
    }
  }

  object HelloWorldDocTitle extends TestBaseModule {
    object core extends HelloWorldModule {
      override def scalaDocOptions = T(Seq("-doc-title", "Hello World"))
    }
  }

  object HelloWorldScalaOverride extends TestBaseModule {
    object core extends HelloWorldModule {
      override def scalaVersion: Target[String] = scala213Version
    }
  }

  object HelloWorldIvyDeps extends TestBaseModule {
    object moduleA extends HelloWorldModule {
      override def ivyDeps = Agg(ivy"com.lihaoyi::sourcecode:0.1.3")
    }
    object moduleB extends HelloWorldModule {
      override def moduleDeps = Seq(moduleA)
      override def ivyDeps = Agg(ivy"com.lihaoyi::sourcecode:0.1.4")
    }
  }

  object HelloWorldTypeLevel extends TestBaseModule {
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

  object HelloWorldMacros212 extends TestBaseModule {
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

  object HelloWorldMacros213 extends TestBaseModule {
    object core extends ScalaModule {
      override def scalaVersion = scala213Version
      override def ivyDeps = Agg(ivy"com.github.julien-truffaut::monocle-macro::2.1.0")
      override def scalacOptions = super.scalacOptions() ++ Seq("-Ymacro-annotations")
    }
  }

  object HelloWorldFlags extends TestBaseModule {
    object core extends ScalaModule {
      def scalaVersion = scala212Version

      override def scalacOptions = super.scalacOptions() ++ Seq(
        "-Ypartial-unification"
      )
    }
  }

  object HelloWorldColorOutput extends TestBaseModule {
    object core extends ScalaModule {
      def scalaVersion = scala213Version

      override def scalacOptions = super.scalacOptions() ++ Seq(
        "-Vimplicits"
      )
    }
  }

  object HelloScalacheck extends TestBaseModule {
    object foo extends ScalaModule {
      def scalaVersion = scala212Version
      object test extends ScalaTests {
        override def ivyDeps = Agg(ivy"org.scalacheck::scalacheck:1.13.5")
        override def testFramework = "org.scalacheck.ScalaCheckFramework"
      }
    }
  }

  object Dotty213 extends TestBaseModule {
    object foo extends ScalaModule {
      def scalaVersion = "0.18.1-RC1"
      override def ivyDeps =
        Agg(ivy"org.scala-lang.modules::scala-xml:1.2.0".withDottyCompat(scalaVersion()))
    }
  }

  object AmmoniteReplMainClass extends TestBaseModule {
    object oldAmmonite extends ScalaModule {
      override def scalaVersion = T("2.13.5")
      override def ammoniteVersion = T("2.4.1")
    }
    object newAmmonite extends ScalaModule {
      override def scalaVersion = T("2.13.5")
      override def ammoniteVersion = T("2.5.0")
    }
  }

  object ValidatedTarget extends TestBaseModule {
    private def mkDirWithFile = T.task {
      os.write(T.dest / "dummy", "dummy", createFolders = true)
      PathRef(T.dest)
    }
    def uncheckedPathRef: T[PathRef] = T { mkDirWithFile() }
    def uncheckedSeqPathRef: T[Seq[PathRef]] = T { Seq(mkDirWithFile()) }
    def uncheckedAggPathRef: T[Agg[PathRef]] = T { Agg(mkDirWithFile()) }
    def uncheckedTuplePathRef: T[Tuple1[PathRef]] = T { Tuple1(mkDirWithFile()) }

    def checkedPathRef: T[PathRef] = T { mkDirWithFile().withRevalidateOnce }
    def checkedSeqPathRef: T[Seq[PathRef]] = T { Seq(mkDirWithFile()).map(_.withRevalidateOnce) }
    def checkedAggPathRef: T[Agg[PathRef]] = T { Agg(mkDirWithFile()).map(_.withRevalidateOnce) }
    def checkedTuplePathRef: T[Tuple1[PathRef]] = T { Tuple1(mkDirWithFile().withRevalidateOnce) }
  }

  object MultiModuleClasspaths extends TestBaseModule {
    trait FooModule extends ScalaModule {
      def scalaVersion = "2.13.12"

      def ivyDeps = Agg(ivy"com.lihaoyi::sourcecode:0.2.2")
      def compileIvyDeps = Agg(ivy"com.lihaoyi::geny:0.4.2")
      def runIvyDeps = Agg(ivy"com.lihaoyi::utest:0.8.4")
      def unmanagedClasspath = T { Agg(PathRef(millSourcePath / "unmanaged")) }
    }
    trait BarModule extends ScalaModule {
      def scalaVersion = "2.13.12"

      def ivyDeps = Agg(ivy"com.lihaoyi::sourcecode:0.2.1")
      def compileIvyDeps = Agg(ivy"com.lihaoyi::geny:0.4.1")
      def runIvyDeps = Agg(ivy"com.lihaoyi::utest:0.8.4")
      def unmanagedClasspath = T { Agg(PathRef(millSourcePath / "unmanaged")) }
    }
    trait QuxModule extends ScalaModule {
      def scalaVersion = "2.13.12"

      def ivyDeps = Agg(ivy"com.lihaoyi::sourcecode:0.2.0")
      def compileIvyDeps = Agg(ivy"com.lihaoyi::geny:0.4.0")
      def runIvyDeps = Agg(ivy"com.lihaoyi::utest:0.8.4")
      def unmanagedClasspath = T { Agg(PathRef(millSourcePath / "unmanaged")) }
    }
    object ModMod extends Module {
      object foo extends FooModule
      object bar extends BarModule {
        def moduleDeps = Seq(foo)
      }
      object qux extends QuxModule {
        def moduleDeps = Seq(bar)
      }
    }
    object ModCompile extends Module {
      object foo extends FooModule
      object bar extends BarModule {
        def moduleDeps = Seq(foo)
      }
      object qux extends QuxModule {
        def compileModuleDeps = Seq(bar)
      }
    }
    object CompileMod extends Module {
      object foo extends FooModule
      object bar extends BarModule {
        def compileModuleDeps = Seq(foo)
      }
      object qux extends QuxModule {
        def moduleDeps = Seq(bar)
      }
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world"

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

  def tests: Tests = Tests {
    test("scalaVersion") {

      test("fromBuild") {
        val eval = UnitTester(HelloWorld, resourcePath)
        val Right(result) = eval.apply(HelloWorld.core.scalaVersion)

        assert(
          result.value == scala212Version,
          result.evalCount > 0
        )
      }
      test("override") {
        val eval = UnitTester(HelloWorldScalaOverride, resourcePath)
        val Right(result) = eval.apply(HelloWorldScalaOverride.core.scalaVersion)

        assert(
          result.value == scala213Version,
          result.evalCount > 0
        )
      }
    }

    test("scalacOptions") {
      test("emptyByDefault") {
        val eval = UnitTester(HelloWorld, resourcePath)
        val Right(result) = eval.apply(HelloWorld.core.scalacOptions)

        assert(
          result.value.isEmpty,
          result.evalCount > 0
        )
      }
      test("override") {
        val eval = UnitTester(HelloWorldFatalWarnings, resourcePath)
        val Right(result) = eval.apply(HelloWorldFatalWarnings.core.scalacOptions)

        assert(
          result.value == Seq("-Ywarn-unused", "-Xfatal-warnings"),
          result.evalCount > 0
        )
      }
    }

    test("scalaDocOptions") {
      test("emptyByDefault") {
        val eval = UnitTester(HelloWorld, resourcePath)
        val Right(result) = eval.apply(HelloWorld.core.scalaDocOptions)
        assert(
          result.value.isEmpty,
          result.evalCount > 0
        )
      }
      test("override") {
        val eval = UnitTester(HelloWorldDocTitle, resourcePath)
        val Right(result) = eval.apply(HelloWorldDocTitle.core.scalaDocOptions)
        assert(
          result.value == Seq("-doc-title", "Hello World"),
          result.evalCount > 0
        )
      }
      test("extend") {
        val eval = UnitTester(HelloWorldWithDocVersion, resourcePath)
        val Right(result) = eval.apply(HelloWorldWithDocVersion.core.scalaDocOptions)
        assert(
          result.value == Seq("-Ywarn-unused", "-Xfatal-warnings", "-doc-version", "1.2.3"),
          result.evalCount > 0
        )
      }
      // make sure options are passed during ScalaDoc generation
      test("docJarWithTitle") {
        val eval = UnitTester(
          HelloWorldDocTitle,
          sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world"
        )
        val Right(result) = eval.apply(HelloWorldDocTitle.core.docJar)
        assert(
          result.evalCount > 0,
          os.read(eval.outPath / "core" / "docJar.dest" / "javadoc" / "index.html").contains(
            "<span id=\"doc-title\">Hello World"
          )
        )
      }
      test("docJarWithVersion") {
        val eval = UnitTester(
          HelloWorldWithDocVersion,
          sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world"
        )
        // scaladoc generation fails because of "-Xfatal-warnings" flag
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldWithDocVersion.core.docJar)
      }
      test("docJarOnlyVersion") {
        val eval = UnitTester(
          HelloWorldOnlyDocVersion,
          sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world"
        )
        // `docJar` requires the `compile` task to succeed (since the addition of Scaladoc 3)
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldOnlyDocVersion.core.docJar)
      }
    }

    test("scalacPluginClasspath") {
      test("withMacroParadise") {
        val eval = UnitTester(HelloWorldTypeLevel, resourcePath)
        val Right(result) = eval.apply(HelloWorldTypeLevel.foo.scalacPluginClasspath)
        assert(
          result.value.nonEmpty,
          result.value.iterator.exists { pathRef => pathRef.path.segments.contains("scalamacros") },
          result.evalCount > 0
        )
      }
    }

//    test("scalaDocPluginClasspath") {
//      test("extend") {
//        val eval = UnitTester(HelloWorldTypeLevel, sourceFileRoot = resourcePath)
//        val Right(result) = eval.apply(HelloWorldTypeLevel.foo.scalaDocPluginClasspath)
//        assert(
//          result.value.iterator.nonEmpty,
//          result.value.iterator.exists { pathRef => pathRef.path.segments.contains("scalamacros") },
//          result.value.iterator.exists { pathRef => pathRef.path.segments.contains("genjavadoc") },
//          result.evalCount > 0
//        )
//      }
//    }
//
//    test("compile") {
//      test("fromScratch") {
//        val eval = UnitTester(HelloWorld, sourceFileRoot = resourcePath)
//        val Right(result) = eval.apply(HelloWorld.core.compile)
//
//        val classesPath = eval.outPath / "core" / "compile.dest" / "classes"
//        val analysisFile = result.value.analysisFile
//        val outputFiles = os.walk(result.value.classes.path)
//        val expectedClassfiles = compileClassfiles.map(classesPath / _)
//        assert(
//          result.value.classes.path == classesPath,
//          os.exists(analysisFile),
//          outputFiles.nonEmpty,
//          outputFiles.forall(expectedClassfiles.contains),
//          result.evalCount > 0
//        )
//
//        // don't recompile if nothing changed
//        val Right(result2) = eval.apply(HelloWorld.core.compile)
//
//        assert(result2.evalCount == 0)
//
//        // Make sure we *do not* end up compiling the compiler bridge, since
//        // it's using a pre-compiled bridge value
//        assert(!os.exists(
//          eval.outPath / "mill" / "scalalib" / "ZincWorkerModule" / "worker.dest" / s"zinc-${zincVersion}"
//        ))
//      }
//
//      test("nonPreCompiledBridge") {
//        val eval = UnitTester(HelloWorldNonPrecompiledBridge, sourceFileRoot = resourcePath)
//        val Right(result) = eval.apply(HelloWorldNonPrecompiledBridge.core.compile)
//
//        val classesPath = eval.outPath / "core" / "compile.dest" / "classes"
//
//        val analysisFile = result.value.analysisFile
//        val outputFiles = os.walk(result.value.classes.path)
//        val expectedClassfiles = compileClassfiles.map(classesPath / _)
//        assert(
//          result.value.classes.path == classesPath,
//          os.exists(analysisFile),
//          outputFiles.nonEmpty,
//          outputFiles.forall(expectedClassfiles.contains),
//          result.evalCount > 0
//        )
//
//        // don't recompile if nothing changed
//        val Right(result2) = eval.apply(HelloWorldNonPrecompiledBridge.core.compile)
//
//        assert(result2.evalCount == 0)
//
//        // Make sure we *do* end up compiling the compiler bridge, since it's
//        // *not* using a pre-compiled bridge value
//        assert(os.exists(
//          eval.outPath / "mill" / "scalalib" / "ZincWorkerModule" / "worker.dest" / s"zinc-${zincVersion}"
//        ))
//      }
//
//      test("recompileOnChange") {
//        val eval = UnitTester(HelloWorld, sourceFileRoot = resourcePath)
//        val Right(result) = eval.apply(HelloWorld.core.compile)
//        assert(result.evalCount > 0)
//
//        os.write.append(HelloWorld.millSourcePath / "core" / "src" / "Main.scala", "\n")
//
//        val Right(result2) = eval.apply(HelloWorld.core.compile)
//        assert(result2.evalCount > 0, result2.evalCount < result.evalCount)
//      }
//      test("failOnError") {
//        val eval = UnitTester(HelloWorld, sourceFileRoot = resourcePath)
//        os.write.append(HelloWorld.millSourcePath / "core" / "src" / "Main.scala", "val x: ")
//
//        val Left(Result.Failure("Compilation failed", _)) = eval.apply(HelloWorld.core.compile)
//
//        val paths = EvaluatorPaths.resolveDestPaths(eval.outPath, HelloWorld.core.compile)
//
//        assert(
//          os.walk(paths.dest / "classes").isEmpty,
//          !os.exists(paths.meta)
//        )
//        // Works when fixed
//        os.write.over(
//          HelloWorld.millSourcePath / "core" / "src" / "Main.scala",
//          os.read(HelloWorld.millSourcePath / "core" / "src" / "Main.scala").dropRight(
//            "val x: ".length
//          )
//        )
//
//        val Right(_) = eval.apply(HelloWorld.core.compile)
//      }
//      test("passScalacOptions") {
//        val eval = UnitTester(HelloWorldFatalWarnings, sourceFileRoot = resourcePath)
//        // compilation fails because of "-Xfatal-warnings" flag
//        val Left(Result.Failure("Compilation failed", _)) =
//          eval.apply(HelloWorldFatalWarnings.core.compile)
//      }
//    }
//
//    test("semanticDbData") {
//      def semanticDbFiles: Set[os.SubPath] = Set(
//        os.sub / "META-INF" / "semanticdb" / "core" / "src" / "Main.scala.semanticdb",
//        os.sub / "META-INF" / "semanticdb" / "core" / "src" / "Result.scala.semanticdb"
//      )
//
//      test("fromScratch") {
//        val eval = UnitTester(SemanticWorld, sourceFileRoot = resourcePath)
//        {
//          println("first - expected full compile")
//          val Right(result) = eval.apply(SemanticWorld.core.semanticDbData)
//
//          val dataPath = eval.outPath / "core" / "semanticDbData.dest" / "data"
//          val outputFiles =
//            os.walk(result.value.path).filter(os.isFile).map(_.relativeTo(result.value.path))
//
//          val expectedSemFiles = semanticDbFiles
//          assert(
//            result.value.path == dataPath,
//            outputFiles.nonEmpty,
//            outputFiles.toSet == expectedSemFiles,
//            result.evalCount > 0,
//            os.exists(dataPath / os.up / "zinc")
//          )
//        }
//        {
//          println("second - expected no compile")
//          // don't recompile if nothing changed
//          val Right(result2) = eval.apply(SemanticWorld.core.semanticDbData)
//          assert(result2.evalCount == 0)
//        }
//      }
//      test("incremental") {
//        val eval = UnitTester(SemanticWorld, sourceFileRoot = resourcePath, debugEnabled = true)
//        // create some more source file to have a reasonable low incremental change later
//        val extraFiles = Seq("Second", "Third", "Fourth").map { f =>
//          val file = eval.evaluator.workspace / "core" / "src" / "hello" / s"${f}.scala"
//          os.write(
//            file,
//            s"""package hello
//               |class ${f}
//               |""".stripMargin,
//            createFolders = true
//          )
//          val sem =
//            os.sub / "META-INF" / "semanticdb" / "core" / "src" / "hello" / s"${f}.scala.semanticdb"
//          (file, sem)
//        }
////        val resultFile = eval.evaluator.workspace / "core" / "src" / "Result.scala"
//
//        {
//          println("first - expected full compile")
//          val Right(result) = eval.apply(SemanticWorld.core.semanticDbData)
//
//          val dataPath = eval.outPath / "core" / "semanticDbData.dest" / "data"
//          val outputFiles =
//            os.walk(result.value.path).filter(os.isFile).map(_.relativeTo(result.value.path))
//
//          val expectedSemFiles = semanticDbFiles ++ extraFiles.map(_._2)
//          assert(
//            result.value.path == dataPath,
//            outputFiles.toSet == expectedSemFiles,
//            result.evalCount > 0
//          )
//        }
//        // change nothing
//        {
//          println("second - expect no compile due to Mill caching")
//          val Right(result2) = eval.apply(SemanticWorld.core.semanticDbData)
//          assert(result2.evalCount == 0)
//        }
//
//        // change one
//        {
//          println("third - expect inc compile of one file\n")
//          os.write.append(extraFiles.head._1, "  ")
//
//          val Right(result) = eval.apply(SemanticWorld.core.semanticDbData)
//          val outputFiles =
//            os.walk(result.value.path).filter(os.isFile).map(_.relativeTo(result.value.path))
//          val expectedFiles = semanticDbFiles ++ extraFiles.map(_._2)
//          assert(
//            outputFiles.toSet == expectedFiles,
//            result.evalCount > 0
//          )
//        }
//        // remove one
//        {
//          println("fourth - expect inc compile with one deleted file")
//          os.remove(extraFiles.head._1)
//
//          val Right(result) = eval.apply(SemanticWorld.core.semanticDbData)
//          val outputFiles =
//            os.walk(result.value.path).filter(os.isFile).map(_.relativeTo(result.value.path))
//          val expectedFiles = semanticDbFiles ++ extraFiles.map(_._2).drop(1)
//          assert(
//            outputFiles.toSet == expectedFiles,
//            result.evalCount > 0
//          )
//        }
//      }
//    }
//
//    test("artifactNameCross") {
//      val eval = UnitTester(CrossHelloWorld, sourceFileRoot = resourcePath)
//      val Right(result) =
//        eval.apply(CrossHelloWorld.core(scala213Version).artifactName)
//      assert(result.value == "core")
//    }
//
//    test("scala-33-depend-on-scala-32-works") {
//      CrossModuleDeps.cuttingEdge(scala33Version).moduleDeps
//    }
//    test("scala-213-depend-on-scala-212-fails") {
//      val message = intercept[Exception](
//        CrossModuleDeps.cuttingEdge(scala213Version).moduleDeps
//      ).getMessage
//      assert(
//        message == s"Unable to find compatible cross version between ${scala213Version} and 2.12.6,3.2.0"
//      )
//    }

    test("runMain") {
      test("runMainObject") {
        val eval = UnitTester(HelloWorld, resourcePath)
        val runResult = eval.outPath / "core" / "runMain.dest" / "hello-mill"

        val Right(result) = eval.apply(HelloWorld.core.runMain("Main", runResult.toString))
        assert(result.evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      test("runCross") {
        def cross(eval: UnitTester, v: String, expectedOut: String): Unit = {

          val runResult = eval.outPath / "hello-mill"

          val Right(result) = eval.apply(
            CrossHelloWorld.core(v).runMain("Shim", runResult.toString)
          )

          assert(result.evalCount > 0)

          assert(
            os.exists(runResult),
            os.read(runResult) == expectedOut
          )
        }

        test("v2123") - {
          val eval = UnitTester(CrossHelloWorld, resourcePath)
          cross(eval, scala2123Version, s"${scala2123Version} leet")
        }
        test("v2124") {
          val eval = UnitTester(CrossHelloWorld, resourcePath)
          cross(eval, scala212Version, s"${scala212Version} leet")
        }
        test("v2131") {
          val eval = UnitTester(CrossHelloWorld, resourcePath)
          cross(eval, scala213Version, s"${scala213Version} idk")
        }
      }

      test("notRunInvalidMainObject") {
        val eval = UnitTester(HelloWorld, resourcePath)
        val Left(Result.Failure("Subprocess failed", _)) =
          eval.apply(HelloWorld.core.runMain("Invalid"))
      }
      test("notRunWhenCompileFailed") {
        val eval = UnitTester(HelloWorld, resourcePath)
        os.write.append(HelloWorld.millSourcePath / "core" / "src" / "Main.scala", "val x: ")

        val Left(Result.Failure("Compilation failed", _)) =
          eval.apply(HelloWorld.core.runMain("Main"))

      }
    }

    test("forkRun") {
      test("runIfMainClassProvided") {
        val eval = UnitTester(HelloWorldWithMain, resourcePath)
        val runResult = eval.outPath / "core" / "run.dest" / "hello-mill"
        val Right(result) = eval.apply(
          HelloWorldWithMain.core.run(T.task(Args(runResult.toString)))
        )

        assert(result.evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      test("notRunWithoutMainClass") {

        val eval = UnitTester(
          HelloWorldWithoutMain,
          sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world-no-main"
        )
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldWithoutMain.core.run())
      }

      test("runDiscoverMainClass") {
        val eval = UnitTester(HelloWorldWithoutMain, resourcePath)
        // Make sure even if there isn't a main class defined explicitly, it gets
        // discovered by Zinc and used
        val runResult = eval.outPath / "core" / "run.dest" / "hello-mill"
        val Right(result) = eval.apply(
          HelloWorldWithoutMain.core.run(T.task(Args(runResult.toString)))
        )

        assert(result.evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
    }

    test("run") {
      test("runIfMainClassProvided") {
        val eval = UnitTester(HelloWorldWithMain, resourcePath)
        val runResult = eval.outPath / "core" / "run.dest" / "hello-mill"
        val Right(result) = eval.apply(
          HelloWorldWithMain.core.runLocal(T.task(Args(runResult.toString)))
        )

        assert(result.evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      test("runWithDefaultMain") {
        val eval = UnitTester(HelloWorldDefaultMain, resourcePath)
        val runResult = eval.outPath / "core" / "run.dest" / "hello-mill"
        val Right(result) = eval.apply(
          HelloWorldDefaultMain.core.runLocal(T.task(Args(runResult.toString)))
        )

        assert(result.evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      test("notRunWithoutMainClass") {
        val eval = UnitTester(
          HelloWorldWithoutMain,
          sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world-no-main"
        )
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldWithoutMain.core.runLocal())
      }
    }

    test("jar") {
      test("nonEmpty") {
        val eval = UnitTester(HelloWorldWithMain, resourcePath)
        val Right(result) = eval.apply(HelloWorldWithMain.core.jar)

        assert(
          os.exists(result.value.path),
          result.evalCount > 0
        )

        Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
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

      test("logOutputToFile") {
        val eval = UnitTester(HelloWorld, resourcePath)
        val outPath = eval.outPath
        eval.apply(HelloWorld.core.compile)

        val logFile = outPath / "core" / "compile.log"
        assert(os.exists(logFile))
      }
    }

    test("assembly") {
      test("assembly") {
        val eval = UnitTester(HelloWorldWithMain, resourcePath)
        val Right(result) = eval.apply(HelloWorldWithMain.core.assembly)
        assert(
          os.exists(result.value.path),
          result.evalCount > 0
        )
        val jarFile = new JarFile(result.value.path.toIO)
        val entries = jarEntries(jarFile)

        val mainPresent = entries.contains("Main.class")
        assert(mainPresent)
        assert(entries.exists(s => s.contains("scala/Predef.class")))

        val mainClass = jarMainClass(jarFile)
        assert(mainClass.contains("Main"))
      }

      test("assemblyRules") {
        def checkAppend[M <: mill.testkit.TestBaseModule](module: M, target: Target[PathRef]) = {
          val eval = UnitTester(module, resourcePath)
          val Right(result) = eval.apply(target)

          Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
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
          os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world-multi"

        def checkAppendMulti[M <: mill.testkit.TestBaseModule](
            module: M,
            target: Target[PathRef]
        ): Unit = {
          val eval = UnitTester(
            module,
            sourceRoot = helloWorldMultiResourcePath
          )
          val Right(result) = eval.apply(target)

          Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
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

        def checkAppendWithSeparator[M <: mill.testkit.TestBaseModule](
            module: M,
            target: Target[PathRef]
        ): Unit = {
          val eval = UnitTester(
            module,
            sourceRoot = helloWorldMultiResourcePath
          )
          val Right(result) = eval.apply(target)

          Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
            assert(jarEntries(jarFile).contains("without-new-line.conf"))

            val result = readFileFromJar(jarFile, "without-new-line.conf").split('\n').toSet
            val expected = Set("without-new-line.first=first", "without-new-line.second=second")
            assert(result == expected)
          }
        }

        test("appendWithDeps") - checkAppend(
          HelloWorldAkkaHttpAppend,
          HelloWorldAkkaHttpAppend.core.assembly
        )
        test("appendMultiModule") - checkAppendMulti(
          HelloWorldMultiAppend,
          HelloWorldMultiAppend.core.assembly
        )
        test("appendPatternWithDeps") - checkAppend(
          HelloWorldAkkaHttpAppendPattern,
          HelloWorldAkkaHttpAppendPattern.core.assembly
        )
        test("appendPatternMultiModule") - checkAppendMulti(
          HelloWorldMultiAppendPattern,
          HelloWorldMultiAppendPattern.core.assembly
        )
        test("appendPatternMultiModuleWithSeparator") - checkAppendWithSeparator(
          HelloWorldMultiAppendByPatternWithSeparator,
          HelloWorldMultiAppendByPatternWithSeparator.core.assembly
        )

        def checkExclude[M <: mill.testkit.TestBaseModule](
            module: M,
            target: Target[PathRef],
            resourcePath: os.Path = resourcePath
        ) = {
          val eval = UnitTester(module, resourcePath)
          val Right(result) = eval.apply(target)

          Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
            assert(!jarEntries(jarFile).contains("reference.conf"))
          }
        }

        test("excludeWithDeps") - checkExclude(
          HelloWorldAkkaHttpExclude,
          HelloWorldAkkaHttpExclude.core.assembly
        )
        test("excludeMultiModule") - checkExclude(
          HelloWorldMultiExclude,
          HelloWorldMultiExclude.core.assembly,
          resourcePath = helloWorldMultiResourcePath
        )
        test("excludePatternWithDeps") - checkExclude(
          HelloWorldAkkaHttpExcludePattern,
          HelloWorldAkkaHttpExcludePattern.core.assembly
        )
        test("excludePatternMultiModule") - checkExclude(
          HelloWorldMultiExcludePattern,
          HelloWorldMultiExcludePattern.core.assembly,
          resourcePath = helloWorldMultiResourcePath
        )

        def checkRelocate[M <: mill.testkit.TestBaseModule](
            module: M,
            target: Target[PathRef],
            resourcePath: os.Path = resourcePath
        ) = {
          val eval = UnitTester(module, resourcePath)
          val Right(result) = eval.apply(target)
          Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
            assert(!jarEntries(jarFile).contains("akka/http/scaladsl/model/HttpEntity.class"))
            assert(
              jarEntries(jarFile).contains("shaded/akka/http/scaladsl/model/HttpEntity.class")
            )
          }
        }

        test("relocate") {
          test("withDeps") - checkRelocate(
            HelloWorldAkkaHttpRelocate,
            HelloWorldAkkaHttpRelocate.core.assembly
          )

          test("run") {
            val eval = UnitTester(
              HelloWorldAkkaHttpRelocate,
              sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world-deps"
            )
            val Right(result) = eval.apply(HelloWorldAkkaHttpRelocate.core.runMain("Main"))
            assert(result.evalCount > 0)
          }
        }

        test("writeDownstreamWhenNoRule") {
          test("withDeps") {
            val eval = UnitTester(HelloWorldAkkaHttpNoRules, null)
            val Right(result) = eval.apply(HelloWorldAkkaHttpNoRules.core.assembly)

            Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
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

          test("multiModule") {
            val eval = UnitTester(
              HelloWorldMultiNoRules,
              sourceRoot = helloWorldMultiResourcePath
            )
            val Right(result) = eval.apply(HelloWorldMultiNoRules.core.assembly)

            Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
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

      test("run") {
        val eval = UnitTester(HelloWorldWithMain, resourcePath)
        val Right(result) = eval.apply(HelloWorldWithMain.core.assembly)

        assert(
          os.exists(result.value.path),
          result.evalCount > 0
        )
        val runResult = eval.outPath / "hello-mill"

        os.proc("java", "-jar", result.value.path, runResult).call(cwd = eval.outPath)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
    }

    test("ivyDeps") {
      val eval = UnitTester(HelloWorldIvyDeps, resourcePath)
      val Right(result) = eval.apply(HelloWorldIvyDeps.moduleA.runClasspath)
      assert(
        result.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar"),
        !result.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar")
      )

      val Right(result2) = eval.apply(HelloWorldIvyDeps.moduleB.runClasspath)
      assert(
        result2.value.exists(_.path.last == "sourcecode_2.12-0.1.4.jar"),
        !result2.value.exists(_.path.last == "sourcecode_2.12-0.1.3.jar")
      )
    }

    test("typeLevel") {
      val eval = UnitTester(HelloWorldTypeLevel, null)
      val classPathsToCheck = Seq(
        HelloWorldTypeLevel.foo.runClasspath,
        HelloWorldTypeLevel.foo.ammoniteReplClasspath,
        HelloWorldTypeLevel.foo.compileClasspath
      )
      for (cp <- classPathsToCheck) {
        val Right(result) = eval.apply(cp)
        assert(
          // Make sure every relevant piece org.scala-lang has been substituted for org.typelevel
          !result.value.map(_.toString).exists(x =>
            x.contains("scala-lang") &&
              (x.contains("scala-library") || x.contains("scala-compiler") || x.contains(
                "scala-reflect"
              ))
          ),
          result.value.map(_.toString).exists(x =>
            x.contains("typelevel") && x.contains("scala-library")
          )
        )
      }
    }

    test("macros") {
      test("scala-2.12") {
        // Scala 2.12 does not always work with Java 17+
        // make sure macros are applied when compiling/running
        val mod = HelloWorldMacros212
        test("runMain") {
          val eval = UnitTester(
            mod,
            sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world-macros"
          )
          if (Properties.isJavaAtLeast(17)) "skipped on Java 17+"
          else {
            val Right(result) = eval.apply(mod.core.runMain("Main"))
            assert(result.evalCount > 0)
          }
        }
        // make sure macros are applied when compiling during scaladoc generation
        test("docJar") {
          val eval = UnitTester(
            mod,
            sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world-macros"
          )
          if (Properties.isJavaAtLeast(17)) "skipped on Java 17+"
          else {
            val Right(result) = eval.apply(mod.core.docJar)
            assert(result.evalCount > 0)
          }
        }
      }
      test("scala-2.13") {
        // make sure macros are applied when compiling/running
        val mod = HelloWorldMacros213
        test("runMain") {
          val eval = UnitTester(
            mod,
            sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world-macros"
          )
          val Right(result) = eval.apply(mod.core.runMain("Main"))
          assert(result.evalCount > 0)
        }
        // make sure macros are applied when compiling during scaladoc generation
        test("docJar") {
          val eval = UnitTester(
            mod,
            sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world-macros"
          )
          val Right(result) = eval.apply(mod.core.docJar)
          assert(result.evalCount > 0)
        }
      }
    }

    test("flags") {
      // make sure flags are passed when compiling/running
      test("runMain") {
        val eval = UnitTester(
          HelloWorldFlags,
          sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world-flags"
        )
        val Right(result) = eval.apply(HelloWorldFlags.core.runMain("Main"))
        assert(result.evalCount > 0)
      }
      // make sure flags are passed during ScalaDoc generation
      test("docJar") {
        val eval = UnitTester(
          HelloWorldFlags,
          sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world-flags"
        )
        val Right(result) = eval.apply(HelloWorldFlags.core.docJar)
        assert(result.evalCount > 0)
      }
    }
    test("color-output") {
      val errStream = new ByteArrayOutputStream()

      val eval = UnitTester(
        HelloWorldColorOutput,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-world-color-output",
        errStream = new PrintStream(errStream, true)
      )
      val Left(Result.Failure("Compilation failed", _)) =
        eval.apply(HelloWorldColorOutput.core.compile)
      val output = errStream.toString
      assert(output.contains(s"${Console.RED}!${Console.RESET}${Console.BLUE}I"))
      assert(output.contains(
        s"${Console.GREEN}example.Show[scala.Option[java.lang.String]]${Console.RESET}"
      ))
    }

    test("scalacheck") {
      val eval = UnitTester(
        HelloScalacheck,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-scalacheck"
      )
      val Right(result) = eval.apply(HelloScalacheck.foo.test.test())
      assert(
        result.evalCount > 0,
        result.value._2.map(_.selector) == Seq(
          "String.startsWith",
          "String.endsWith",
          "String.substring",
          "String.substring"
        )
      )
    }

    test("dotty213") {
      val eval = UnitTester(
        Dotty213,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "dotty213"
      )
      val Right(result) = eval.apply(Dotty213.foo.run())
      assert(result.evalCount > 0)
    }

    test("replAmmoniteMainClass") {
      val eval = UnitTester(AmmoniteReplMainClass, null)
      val Right(result) = eval.apply(AmmoniteReplMainClass.oldAmmonite.ammoniteMainClass)
      assert(result.value == "ammonite.Main")
      val Right(result2) = eval.apply(AmmoniteReplMainClass.newAmmonite.ammoniteMainClass)
      assert(result2.value == "ammonite.AmmoniteMain")
    }

    test("validated") {
      test("PathRef") {
        def check(t: Target[PathRef], flip: Boolean) = {
          val eval = UnitTester(ValidatedTarget, null)
          // we reconstruct faulty behavior
          val Right(result) = eval.apply(t)
          assert(
            result.value.path.last == (t.asInstanceOf[NamedTask[_]].label + ".dest"),
            os.exists(result.value.path)
          )
          os.remove.all(result.value.path)
          val Right(result2) = eval.apply(t)
          assert(
            result2.value.path.last == (t.asInstanceOf[NamedTask[_]].label + ".dest"),
            // as the result was cached but not checked, this path is missing
            os.exists(result2.value.path) == flip
          )
        }
        test("unchecked") - check(ValidatedTarget.uncheckedPathRef, false)
        test("checked") - check(ValidatedTarget.checkedPathRef, true)
      }
      test("SeqPathRef") {
        def check(t: Target[Seq[PathRef]], flip: Boolean) {
          val eval = UnitTester(ValidatedTarget, null)
          // we reconstruct faulty behavior
          val Right(result) = eval.apply(t)
          assert(
            result.value.map(_.path.last) == Seq(t.asInstanceOf[NamedTask[_]].label + ".dest"),
            result.value.forall(p => os.exists(p.path))
          )
          result.value.foreach(p => os.remove.all(p.path))
          val Right(result2) = eval.apply(t)
          assert(
            result2.value.map(_.path.last) == Seq(t.asInstanceOf[NamedTask[_]].label + ".dest"),
            // as the result was cached but not checked, this path is missing
            result2.value.forall(p => os.exists(p.path) == flip)
          )
        }
        test("unchecked") - check(ValidatedTarget.uncheckedSeqPathRef, false)
        test("checked") - check(ValidatedTarget.checkedSeqPathRef, true)
      }
      test("AggPathRef") {
        def check(t: Target[Agg[PathRef]], flip: Boolean) = {
          val eval = UnitTester(ValidatedTarget, null)
          // we reconstruct faulty behavior
          val Right(result) = eval.apply(t)
          assert(
            result.value.map(_.path.last) == Agg(t.asInstanceOf[NamedTask[_]].label + ".dest"),
            result.value.forall(p => os.exists(p.path))
          )
          result.value.foreach(p => os.remove.all(p.path))
          val Right(result2) = eval.apply(t)
          assert(
            result2.value.map(_.path.last) == Agg(t.asInstanceOf[NamedTask[_]].label + ".dest"),
            // as the result was cached but not checked, this path is missing
            result2.value.forall(p => os.exists(p.path) == flip)
          )
        }
        test("unchecked") - check(ValidatedTarget.uncheckedAggPathRef, false)
        test("checked") - check(ValidatedTarget.checkedAggPathRef, true)
      }
      test("other") {
        def check(t: Target[Tuple1[PathRef]], flip: Boolean) = {
          val eval = UnitTester(ValidatedTarget, null)
          // we reconstruct faulty behavior
          val Right(result) = eval.apply(t)
          assert(
            result.value._1.path.last == (t.asInstanceOf[NamedTask[_]].label + ".dest"),
            os.exists(result.value._1.path)
          )
          os.remove.all(result.value._1.path)
          val Right(result2) = eval.apply(t)
          assert(
            result2.value._1.path.last == (t.asInstanceOf[NamedTask[_]].label + ".dest"),
            // as the result was cached but not checked, this path is missing
            os.exists(result2.value._1.path) == flip
          )
        }
        test("unchecked") - check(ValidatedTarget.uncheckedTuplePathRef, false)
        test("checked") - check(ValidatedTarget.checkedTuplePathRef, true)
      }

    }

    test("multiModuleClasspaths") {
      // Make sure that a bunch of modules dependent on each other has their various
      // {classpaths,moduleDeps,ivyDeps}x{run,compile,normal} properly aggregated
      def check(
          eval: UnitTester,
          mod: ScalaModule,
          expectedRunClasspath: Seq[String],
          expectedCompileClasspath: Seq[String],
          expectedLocalClasspath: Seq[String]
      ) = {
        val Right(runClasspathRes) = eval.apply(mod.runClasspath)
        val Right(compileClasspathRes) = eval.apply(mod.compileClasspath)
        val Right(upstreamAssemblyClasspathRes) = eval.apply(mod.upstreamAssemblyClasspath)
        val Right(localClasspathRes) = eval.apply(mod.localClasspath)

        val start = eval.evaluator.rootModule.millSourcePath
        val startToken = Set("org", "com")
        def simplify(cp: Seq[PathRef]) = {
          cp.map(_.path).map { p =>
            if (p.startsWith(start)) p.relativeTo(start).toString()
            else p.segments.dropWhile(!startToken.contains(_)).mkString("/")
          }
        }

        val simplerRunClasspath = simplify(runClasspathRes.value)
        val simplerCompileClasspath = simplify(compileClasspathRes.value.toSeq)
        val simplerLocalClasspath = simplify(localClasspathRes.value)

        assert(expectedRunClasspath == simplerRunClasspath)
        assert(expectedCompileClasspath == simplerCompileClasspath)
        assert(expectedLocalClasspath == simplerLocalClasspath)
        // invariant: the `upstreamAssemblyClasspath` used to make the `upstreamAssembly`
        // and the `localClasspath` used to complete it to make the final `assembly` must
        // have the same entries as the `runClasspath` used to execute things
        assert(
          runClasspathRes.value == upstreamAssemblyClasspathRes.value.toSeq ++ localClasspathRes.value
        )
      }

      test("modMod") {
        val eval = UnitTester(MultiModuleClasspaths, resourcePath)
        // Make sure that `compileClasspath` has all the same things as `runClasspath`,
        // but without the `/resources`
        check(
          eval,
          MultiModuleClasspaths.ModMod.qux,
          expectedRunClasspath = List(
            // We pick up the oldest version of utest 0.7.0 from the current module, because
            // utest is a `runIvyDeps` and not picked up transitively
            "com/lihaoyi/utest_2.13/0.8.4/utest_2.13-0.8.4.jar",
            // We pick up the newest version of sourcecode 0.2.4 from the upstream module, because
            // sourcecode is a `ivyDeps` and `runIvyDeps` and those are picked up transitively
            "com/lihaoyi/sourcecode_2.13/0.2.2/sourcecode_2.13-0.2.2.jar",
            //
            "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
            "org/scala-sbt/test-interface/1.0/test-interface-1.0.jar",
            "org/portable-scala/portable-scala-reflect_2.13/1.1.3/portable-scala-reflect_2.13-1.1.3.jar",
            "org/scala-lang/scala-reflect/2.13.12/scala-reflect-2.13.12.jar",
            //
            "ModMod/bar/compile-resources",
            "ModMod/bar/unmanaged",
            "ModMod/bar/resources",
            "out/ModMod/bar/compile.dest/classes",
            //
            "ModMod/foo/compile-resources",
            "ModMod/foo/unmanaged",
            "ModMod/foo/resources",
            "out/ModMod/foo/compile.dest/classes",
            //
            "ModMod/qux/compile-resources",
            "ModMod/qux/unmanaged",
            "ModMod/qux/resources",
            "out/ModMod/qux/compile.dest/classes"
          ),
          expectedCompileClasspath = List(
            // Make sure we only have geny 0.6.4 from the current module, and not newer
            // versions pulled in by the upstream modules, because as `compileIvyDeps` it
            // is not picked up transitively
            "com/lihaoyi/geny_2.13/0.4.0/geny_2.13-0.4.0.jar",
            "com/lihaoyi/sourcecode_2.13/0.2.2/sourcecode_2.13-0.2.2.jar",
            //
            "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
            //
            "ModMod/bar/compile-resources",
            "ModMod/bar/unmanaged",
            "out/ModMod/bar/compile.dest/classes",
            //
            "ModMod/foo/compile-resources",
            "ModMod/foo/unmanaged",
            "out/ModMod/foo/compile.dest/classes",
            //
            "ModMod/qux/compile-resources",
            "ModMod/qux/unmanaged"
            // We do not include `qux/compile.dest/classes` here, because this is the input
            // that is required to compile `qux` in the first place
          ),
          expectedLocalClasspath = List(
            "ModMod/qux/compile-resources",
            "ModMod/qux/unmanaged",
            "ModMod/qux/resources",
            "out/ModMod/qux/compile.dest/classes"
          )
        )
      }

      test("modCompile") {
        val eval = UnitTester(MultiModuleClasspaths, resourcePath)
        // Mostly the same as `modMod` above, but with the dependency
        // from `qux` to `bar` being a `compileModuleDeps`
        check(
          eval,
          MultiModuleClasspaths.ModCompile.qux,
          expectedRunClasspath = List(
            // `utest` is a `runIvyDeps` and not picked up transitively
            "com/lihaoyi/utest_2.13/0.8.4/utest_2.13-0.8.4.jar",
            // Because `sourcecode` comes from `ivyDeps`, and the dependency from
            // `qux` to `bar` is a `compileModuleDeps`, we do not include its
            // dependencies for `qux`'s `runClasspath`
            "com/lihaoyi/sourcecode_2.13/0.2.0/sourcecode_2.13-0.2.0.jar",
            //
            "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
            "org/scala-sbt/test-interface/1.0/test-interface-1.0.jar",
            "org/portable-scala/portable-scala-reflect_2.13/1.1.3/portable-scala-reflect_2.13-1.1.3.jar",
            "org/scala-lang/scala-reflect/2.13.12/scala-reflect-2.13.12.jar",
            //
            "ModCompile/bar/compile-resources",
            "ModCompile/bar/unmanaged",
            "ModCompile/bar/resources",
            "out/ModCompile/bar/compile.dest/classes",
            //
            "ModCompile/foo/compile-resources",
            "ModCompile/foo/unmanaged",
            "ModCompile/foo/resources",
            "out/ModCompile/foo/compile.dest/classes",
            //
            "ModCompile/qux/compile-resources",
            "ModCompile/qux/unmanaged",
            "ModCompile/qux/resources",
            "out/ModCompile/qux/compile.dest/classes"
          ),
          expectedCompileClasspath = List(
            "com/lihaoyi/geny_2.13/0.4.0/geny_2.13-0.4.0.jar",
            // `sourcecode` is a `ivyDeps` from a `compileModuleDeps, which still
            // gets picked up transitively, but only for compilation. This is necessary
            // in order to make sure that we can correctly compile against the upstream
            // module's classes.
            "com/lihaoyi/sourcecode_2.13/0.2.2/sourcecode_2.13-0.2.2.jar",
            //
            "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
            //
            "ModCompile/bar/compile-resources",
            "ModCompile/bar/unmanaged",
            "out/ModCompile/bar/compile.dest/classes",
            //
            "ModCompile/foo/compile-resources",
            "ModCompile/foo/unmanaged",
            "out/ModCompile/foo/compile.dest/classes",
            //
            "ModCompile/qux/compile-resources",
            "ModCompile/qux/unmanaged"
          ),
          expectedLocalClasspath = List(
            "ModCompile/qux/compile-resources",
            "ModCompile/qux/unmanaged",
            "ModCompile/qux/resources",
            "out/ModCompile/qux/compile.dest/classes"
          )
        )
      }

      test("compileMod") {
        val eval = UnitTester(MultiModuleClasspaths, resourcePath)
        // Both the `runClasspath` and `compileClasspath` should not have `foo` on the
        // classpath, nor should it have the versions of libraries pulled in by `foo`
        // (e.g. `sourcecode-0.2.4`), because it is a `compileModuleDep` of an upstream
        // module and thus it is not transitive
        check(
          eval,
          MultiModuleClasspaths.CompileMod.qux,
          expectedRunClasspath = List(
            "com/lihaoyi/utest_2.13/0.8.4/utest_2.13-0.8.4.jar",
            // We pick up the version of `sourcecode` from `ivyDeps` from `bar` because
            // we have a normal `moduleDeps` from `qux` to `bar`, but do not pick it up
            // from `foo` because it's a `compileIvyDeps` from `bar` to `foo` and
            // `compileIvyDeps` are not transitive
            "com/lihaoyi/sourcecode_2.13/0.2.1/sourcecode_2.13-0.2.1.jar",
            //
            "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
            "org/scala-sbt/test-interface/1.0/test-interface-1.0.jar",
            "org/portable-scala/portable-scala-reflect_2.13/1.1.3/portable-scala-reflect_2.13-1.1.3.jar",
            "org/scala-lang/scala-reflect/2.13.12/scala-reflect-2.13.12.jar",
            //
            "CompileMod/bar/compile-resources",
            "CompileMod/bar/unmanaged",
            "CompileMod/bar/resources",
            "out/CompileMod/bar/compile.dest/classes",
            //
            "CompileMod/qux/compile-resources",
            "CompileMod/qux/unmanaged",
            "CompileMod/qux/resources",
            "out/CompileMod/qux/compile.dest/classes"
          ),
          expectedCompileClasspath = List(
            "com/lihaoyi/geny_2.13/0.4.0/geny_2.13-0.4.0.jar",
            "com/lihaoyi/sourcecode_2.13/0.2.1/sourcecode_2.13-0.2.1.jar",
            //
            "org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar",
            // We do not include `foo`s compile output here, because `foo` is a
            // `compileModuleDep` of `bar`, and `compileModuleDep`s are non-transitive
            //
            "CompileMod/bar/compile-resources",
            "CompileMod/bar/unmanaged",
            "out/CompileMod/bar/compile.dest/classes",
            //
            "CompileMod/qux/compile-resources",
            "CompileMod/qux/unmanaged"
          ),
          expectedLocalClasspath = List(
            "CompileMod/qux/compile-resources",
            "CompileMod/qux/unmanaged",
            "CompileMod/qux/resources",
            "out/CompileMod/qux/compile.dest/classes"
          )
        )
      }
    }
  }
}

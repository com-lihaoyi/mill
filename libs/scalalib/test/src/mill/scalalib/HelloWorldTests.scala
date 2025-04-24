package mill.scalalib

import java.io.ByteArrayOutputStream
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*
import scala.util.Using
import mill.*
import mill.api.ExecResult
import mill.define.Discover
import mill.define.ExecutionPaths
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.*

object HelloWorldTests extends TestSuite {

  val scala2123Version = "2.12.3"
  val scala212Version = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
  val scala213Version = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  val scala32Version = sys.props.getOrElse("TEST_SCALA_3_2_VERSION", ???)
  val scala33Version = sys.props.getOrElse("TEST_SCALA_3_3_VERSION", ???)
  val zincVersion = sys.props.getOrElse("TEST_ZINC_VERSION", ???)

  trait HelloWorldModule extends scalalib.ScalaModule {
    def scalaVersion = scala212Version
    override def semanticDbVersion: T[String] = Task {
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
    lazy val millDiscover = Discover[this.type]
  }
  object HelloWorldNonPrecompiledBridge extends TestBaseModule {
    object core extends HelloWorldModule {
      override def scalaVersion = "2.12.1"
    }
    lazy val millDiscover = Discover[this.type]

  }
  object CrossHelloWorld extends TestBaseModule {
    object core extends Cross[HelloWorldCross](
          scala2123Version,
          scala212Version,
          scala213Version
        )
    trait HelloWorldCross extends CrossScalaModule
    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldDefaultMain extends TestBaseModule {
    object core extends HelloWorldModule
    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldWithoutMain extends TestBaseModule {
    object core extends HelloWorldModule {
      override def mainClass = None
    }
    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldWithMain extends TestBaseModule {
    object core extends HelloWorldModuleWithMain
    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldFatalWarnings extends TestBaseModule {
    object core extends HelloWorldModule {
      override def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
    }
    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldScalaOverride extends TestBaseModule {
    object core extends HelloWorldModule {
      override def scalaVersion: T[String] = scala213Version
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world"

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

      test("fromBuild") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorld.core.scalaVersion): @unchecked

        assert(
          result.value == scala212Version,
          result.evalCount > 0
        )
      }
      test("override") - UnitTester(HelloWorldScalaOverride, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldScalaOverride.core.scalaVersion): @unchecked

        assert(
          result.value == scala213Version,
          result.evalCount > 0
        )
      }
    }

    test("scalacOptions") {
      test("emptyByDefault") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorld.core.scalacOptions): @unchecked

        assert(
          result.value.isEmpty,
          result.evalCount > 0
        )
      }
      test("override") - UnitTester(HelloWorldFatalWarnings, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldFatalWarnings.core.scalacOptions): @unchecked

        assert(
          result.value == Seq("-Ywarn-unused", "-Xfatal-warnings"),
          result.evalCount > 0
        )
      }
    }

    test("compile") {
      test("fromScratch") - UnitTester(HelloWorld, sourceRoot = resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorld.core.compile): @unchecked

        val classesPath = eval.outPath / "core/compile.dest/classes"
        val analysisFile = result.value.analysisFile
        val outputFiles = os.walk(result.value.classes.path)
        val expectedClassfiles = compileClassfiles.map(classesPath / _)
        assert(
          result.value.classes.path == classesPath,
          os.exists(analysisFile),
          outputFiles.nonEmpty,
          outputFiles.forall(expectedClassfiles.contains),
          result.evalCount > 0
        )

        // don't recompile if nothing changed
        val Right(result2) = eval.apply(HelloWorld.core.compile): @unchecked

        assert(result2.evalCount == 0)

        // Make sure we *do not* end up compiling the compiler bridge, since
        // it's using a pre-compiled bridge value
        assert(!os.exists(
          eval.outPath / "mill/scalalib/JvmWorkerModule/worker.dest" / s"zinc-${zincVersion}"
        ))
      }

      test("nonPreCompiledBridge") - UnitTester(
        HelloWorldNonPrecompiledBridge,
        sourceRoot = resourcePath
      ).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldNonPrecompiledBridge.core.compile): @unchecked

        val classesPath = eval.outPath / "core/compile.dest/classes"

        val analysisFile = result.value.analysisFile
        val outputFiles = os.walk(result.value.classes.path)
        val expectedClassfiles = compileClassfiles.map(classesPath / _)
        assert(
          result.value.classes.path == classesPath,
          os.exists(analysisFile),
          outputFiles.nonEmpty,
          outputFiles.forall(expectedClassfiles.contains),
          result.evalCount > 0
        )

        // don't recompile if nothing changed
        val Right(result2) = eval.apply(HelloWorldNonPrecompiledBridge.core.compile): @unchecked

        assert(result2.evalCount == 0)

        // Make sure we *do* end up compiling the compiler bridge, since it's
        // *not* using a pre-compiled bridge value
        assert(os.exists(
          eval.outPath / "mill/scalalib/JvmWorkerModule/worker.dest" / s"zinc-${zincVersion}"
        ))
      }

      test("recompileOnChange") - UnitTester(HelloWorld, sourceRoot = resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorld.core.compile): @unchecked
        assert(result.evalCount > 0)

        os.write.append(HelloWorld.moduleDir / "core/src/Main.scala", "\n")

        val Right(result2) = eval.apply(HelloWorld.core.compile): @unchecked
        assert(result2.evalCount > 0, result2.evalCount < result.evalCount)
      }
      test("failOnError") - UnitTester(HelloWorld, sourceRoot = resourcePath).scoped { eval =>
        os.write.append(HelloWorld.moduleDir / "core/src/Main.scala", "val x: ")

        val Left(ExecResult.Failure("Compilation failed")) =
          eval.apply(HelloWorld.core.compile): @unchecked

        val paths = ExecutionPaths.resolve(eval.outPath, HelloWorld.core.compile)

        assert(
          os.walk(paths.dest / "classes").isEmpty,
          !os.exists(paths.meta)
        )
        // Works when fixed
        os.write.over(
          HelloWorld.moduleDir / "core/src/Main.scala",
          os.read(HelloWorld.moduleDir / "core/src/Main.scala").dropRight(
            "val x: ".length
          )
        )

        val Right(_) = eval.apply(HelloWorld.core.compile): @unchecked
      }
      test("passScalacOptions") - UnitTester(
        HelloWorldFatalWarnings,
        sourceRoot = resourcePath
      ).scoped { eval =>
        // compilation fails because of "-Xfatal-warnings" flag
        val Left(ExecResult.Failure("Compilation failed")) =
          eval.apply(HelloWorldFatalWarnings.core.compile): @unchecked
      }
    }

    test("artifactNameCross") - UnitTester(CrossHelloWorld, sourceRoot = resourcePath).scoped {
      eval =>
        val Right(result) =
          eval.apply(CrossHelloWorld.core(scala213Version).artifactName): @unchecked
        assert(result.value == "core")
    }

    test("jar") {
      test("nonEmpty") - UnitTester(HelloWorldWithMain, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldWithMain.core.jar): @unchecked

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

      test("logOutputToFile") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
        val outPath = eval.outPath
        eval.apply(HelloWorld.core.compile)

        val logFile = outPath / "core/compile.log"
        assert(os.exists(logFile))
      }
    }
  }
}

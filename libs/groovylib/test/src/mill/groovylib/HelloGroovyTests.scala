package mill
package groovylib

import mill.javalib.{JavaModule, TestModule}
import mill.api.Task
import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import java.io.FileInputStream

object HelloGroovyTests extends TestSuite {

  val groovy4Version = "4.0.28"
  val groovy5Version = "5.0.1"
  val groovyVersions = Seq(groovy4Version, groovy5Version)
  val junit5Version = sys.props.getOrElse("TEST_JUNIT5_VERSION", "5.13.4")
  val spockGroovy4Version = "2.3-groovy-4.0"

  object HelloGroovy extends TestRootModule {

    trait GroovyVersionCross extends GroovyModule with Cross.Module[String] {
      override def groovyVersion: Task.Simple[String] = crossValue
    }

    lazy val millDiscover = Discover[this.type]

    // needed for a special test where only the tests are written in Groovy while appcode remains Java
    object `groovy-tests` extends JavaMavenModuleWithGroovyTests {

      object `test` extends GroovyMavenTests with TestModule.Junit5 {

        override def moduleDeps: Seq[JavaModule] = Seq(
          HelloGroovy.`groovy-tests`
        )

        override def groovyVersion: T[String] = groovy4Version
        override def jupiterVersion: T[String] = junit5Version
      }

    }

    /**
     * Currently Spock does not support Groovy 5, so that's why it's currently
     * pulled out of the cross compilation.
     */
    object spock extends GroovyModule {
      override def groovyVersion: T[String] = groovy4Version

      object tests extends GroovyTests with TestModule.Spock {
        override def jupiterVersion: T[String] = junit5Version
        override def spockVersion: T[String] = spockGroovy4Version
      }
    }

    /**
     * Test to verify BOM-resolution only done starting with the minimal version
     */
    object deps extends Module {

      object groovyBom extends GroovyModule {
        override def groovyVersion: T[String] = groovy4Version
      }

      object groovyNoBom extends GroovyModule {
        // Groovy-BOM available starting with 4.0.26
        override def groovyVersion: T[String] = "4.0.25"
      }

      object `spockBom` extends GroovyModule with TestModule.Spock {
        override def spockVersion: T[String] = spockGroovy4Version
        override def groovyVersion: T[String] = groovy4Version
      }

      object `spockNoBom` extends GroovyModule with TestModule.Spock {
        // Groovy-BOM available starting with 2.3
        override def spockVersion: T[String] = "2.2-groovy-4.0"
        override def groovyVersion: T[String] = groovy4Version
      }

    }

    trait Test extends GroovyVersionCross {

      override def mainClass = Some("hello.Hello")

      object script extends GroovyModule {
        override def groovyVersion: T[String] = crossValue
        override def mainClass = Some("HelloScript")
      }

      object staticcompile extends GroovyModule {
        override def groovyVersion: T[String] = crossValue
        override def mainClass = Some("hellostatic.HelloStatic")
      }

      object `joint-compile` extends GroovyModule {
        override def groovyVersion: T[String] = crossValue
        override def mainClass = Some("jointcompile.JavaMain")
      }

      object test extends GroovyTests with TestModule.Junit5 {
        override def jupiterVersion: T[String] = junit5Version
        override def junitPlatformVersion = "1.13.4"
      }

      object compileroptions extends GroovyModule {
        override def groovyVersion: T[String] = crossValue
        override def targetBytecode: Task.Simple[Option[String]] = Some("11")
        override def enablePreview: Task.Simple[Boolean] = true
        override def mainClass = Some("compileroptions.HelloCompilerOptions")
      }
    }
    object main extends Cross[Test](groovyVersions)
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-groovy"

  /**
   * Compiles test files located within resources
   */
  def testEval() = UnitTester(HelloGroovy, resourcePath)

  def tests: Tests = Tests {

    def main = HelloGroovy.main
    def spock = HelloGroovy.spock
    def mixed = HelloGroovy.`groovy-tests`
    def deps = HelloGroovy.deps

    test("running a Groovy script") {
      testEval().scoped { eval =>
        main.crossModules.foreach(m => {
          val Right(_) = eval.apply(m.script.run()): @unchecked
        })
      }
    }

    test("running a Groovy script") {
      testEval().scoped { eval =>
        main.crossModules.foreach(m => {
          val Right(_) = eval.apply(m.script.run()): @unchecked
        })
      }
    }

    test("compile & run Groovy module") {
      testEval().scoped { eval =>
        main.crossModules.foreach(m => {
          val Right(result) = eval.apply(m.compile): @unchecked

          assert(
            os.walk(result.value.classes.path).exists(_.last == "Hello.class")
          )

          val Right(_) = eval.apply(m.run()): @unchecked
        })
      }
    }

    test("compile & run Groovy JUnit5 test") {
      testEval().scoped { eval =>
        main.crossModules.foreach(m => {
          val Right(result) = eval.apply(m.test.compile): @unchecked

          assert(
            os.walk(result.value.classes.path).exists(_.last == "HelloTest.class")
          )

          val Right(discovered) = eval.apply(m.test.discoveredTestClasses): @unchecked
          assert(discovered.value == Seq("hello.tests.HelloTest"))

          val Right(_) = eval.apply(m.test.testForked()): @unchecked
        })
      }
    }

    test("compile & run a statically compiled Groovy") {
      testEval().scoped { eval =>
        main.crossModules.foreach(m => {
          val Right(result) = eval.apply(m.staticcompile.compile): @unchecked
          assert(
            os.walk(result.value.classes.path).exists(_.last == "HelloStatic.class")
          )
          val Right(_) = eval.apply(m.staticcompile.run()): @unchecked
        })
      }
    }

    test("compile joint (groovy <-> java cycle) & run") {
      testEval().scoped { eval =>
        main.crossModules.foreach(m => {
          val Right(result) = eval.apply(m.`joint-compile`.compile): @unchecked

          assert(
            os.walk(result.value.classes.path).exists(_.last == "JavaPrinter.class")
          )
          assert(
            os.walk(result.value.classes.path).exists(_.last == "GroovyGreeter.class")
          )

          val Right(_) = eval.apply(m.`joint-compile`.run()): @unchecked
        })
      }
    }

    test("compiles to Java 11 with Preview enabled") {
      import org.objectweb.asm.ClassReader

      case class BytecodeVersion(major: Int, minor: Int) {
        def javaVersion: String = major match {
          case 55 => "11"
          case _ => "Irrelevant"
        }

        def is11PreviewEnabled: Boolean = minor == 65535 // 0xFFFF
      }

      def getBytecodeVersion(classFilePath: os.Path): BytecodeVersion = {
        val classReader = new ClassReader(new FileInputStream(classFilePath.toIO))
        val buffer = classReader.b

        // Class file format: magic(4) + minor(2) + major(2) + ...
        val minor = ((buffer(4) & 0xff) << 8) | (buffer(5) & 0xff)
        val major = ((buffer(6) & 0xff) << 8) | (buffer(7) & 0xff)

        BytecodeVersion(major, minor)
      }

      testEval().scoped { eval =>
        main.crossModules.foreach(m => {
          val Right(result) = eval.apply(m.compileroptions.compile): @unchecked

          val compiledClassFile =
            os.walk(result.value.classes.path).find(_.last == "HelloCompilerOptions.class")

          assert(
            compiledClassFile.isDefined
          )

          val bytecodeVersion = getBytecodeVersion(compiledClassFile.get)

          assert(bytecodeVersion.major == 55)
          assert(bytecodeVersion.is11PreviewEnabled)

          val Right(_) = eval.apply(m.compileroptions.run()): @unchecked
        })
      }
    }

    test("compile & test module (only test uses Groovy)") {
      testEval().scoped { eval =>

        val Right(_) = eval.apply(mixed.test.compile): @unchecked
        val Right(discovered) = eval.apply(mixed.test.discoveredTestClasses): @unchecked
        assert(discovered.value == Seq("hello.maven.tests.HelloMavenTestOnly"))

        val Right(_) = eval.apply(mixed.test.testForked()): @unchecked
      }
    }

    test("compile & run Spock test") {
      testEval().scoped { eval =>
        val Right(result1) = eval.apply(spock.tests.compile): @unchecked
        assert(
          os.walk(result1.value.classes.path).exists(_.last == "SpockTest.class")
        )

        val Right(discovered) = eval.apply(spock.tests.discoveredTestClasses): @unchecked
        assert(discovered.value == Seq("hello.spock.SpockTest"))

        val Right(_) = eval.apply(spock.tests.testForked()): @unchecked
      }
    }

    test("dependency management") {

      test("groovy") {

        val groovyBom = mvn"org.apache.groovy:groovy-bom:$groovy4Version"

        test("groovy bom is added when version is at least 4.0.26") {
          testEval().scoped { eval =>
            val Right(result) = eval.apply(deps.groovyBom.bomMvnDeps): @unchecked

            assert(
              result.value.contains(groovyBom)
            )
          }
        }

        test("groovy bom is NOT added when version is below 4.0.26") {
          testEval().scoped { eval =>
            val Right(result) = eval.apply(deps.groovyNoBom.bomMvnDeps): @unchecked

            assert(
              !result.value.contains(groovyBom)
            )
          }
        }
      }

      test("spock") {

        val spockBom = mvn"org.spockframework:spock-bom:$spockGroovy4Version"

        test("spock bom is added when version is at least 2.3") {
          testEval().scoped { eval =>
            val Right(result) = eval.apply(deps.spockBom.bomMvnDeps): @unchecked

            assert(
              result.value.contains(spockBom)
            )
          }
        }

        test("spock bom is NOT added when version is below 2.3") {
          testEval().scoped { eval =>
            val Right(result) = eval.apply(deps.spockNoBom.bomMvnDeps): @unchecked

            assert(
              !result.value.contains(spockBom)
            )
          }
        }
      }
    }
  }
}

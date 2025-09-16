package mill
package groovylib

import mill.javalib.{JavaModule, TestModule}
import mill.api.{Task}
import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object HelloGroovyTests extends TestSuite {

  val groovy4Version = "4.0.28"
  val junit5Version = sys.props.getOrElse("TEST_JUNIT5_VERSION", "5.13.4")

  object HelloGroovy extends TestRootModule {

    lazy val millDiscover = Discover[this.type]

    // needed for a special test where only the tests are written in Groovy while appcode remains Java
    object `groovy-tests` extends JavaMavenModuleWithGroovyTests {

      object `test` extends GroovyMavenTests with TestModule.Junit5 {

        override def moduleDeps: Seq[JavaModule] = Seq(
          HelloGroovy.`groovy-tests`
        )

        override def groovyVersion = groovy4Version
        override def depManagement = Seq(
          mvn"org.junit.jupiter:junit-jupiter-engine:$junit5Version"
        )
        override def jupiterVersion = junit5Version
        override def junitPlatformVersion = "1.13.4"
      }

    }

    object `joint-compile` extends GroovyModule {
      override def groovyVersion: T[String] = groovy4Version
      override def mainClass = Some("jointcompile.JavaMain")
    }

    trait Test extends GroovyModule {

      override def mainClass = Some("hello.Hello")

      object test extends GroovyTests with TestModule.Junit5 {
        override def depManagement = Seq(
          mvn"org.junit.jupiter:junit-jupiter-engine:5.13.4"
        )
        override def jupiterVersion = "5.13.4"
        override def junitPlatformVersion = "1.13.4"
      }

      object script extends GroovyModule {
        override def groovyVersion = "4.0.28"
        override def mainClass = Some("HelloScript")
      }

      object staticcompile extends GroovyModule {
        override def groovyVersion = "4.0.28"
        override def mainClass = Some("hellostatic.HelloStatic")
      }

      object spock extends GroovyTests with TestModule.Spock {
        override def junitPlatformVersion = "1.13.4"
        override def spockVersion = "2.3-groovy-4.0"
        override def groovyVersion = "4.0.28"

        def bomMvnDeps = Seq(
          mvn"org.junit:junit-bom:5.13.4",
//          mvn"org.apache.groovy:groovy-bom:${groovyVersion()}",
          mvn"org.spockframework:spock-bom:${spockVersion()}"
        )
      }
    }
    object main extends Test {
      override def groovyVersion: T[String] = groovy4Version
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-groovy"

  /**
   * Compiles test files located within resources
   */
  def testEval() = UnitTester(HelloGroovy, resourcePath)

  def tests: Tests = Tests {

    def m = HelloGroovy.main
    def mixed = HelloGroovy.`groovy-tests`
    def joint = HelloGroovy.`joint-compile`

    test("running a Groovy script") {
      testEval().scoped { eval =>
        val Right(_) = eval.apply(m.script.run()): @unchecked
      }
    }

    test("running a Groovy script") {
      testEval().scoped { eval =>
        val Right(_) = eval.apply(m.script.run()): @unchecked
      }
    }

    test("compile & run Groovy module") {
      testEval().scoped { eval =>
        val Right(result) = eval.apply(m.compile): @unchecked

        assert(
          os.walk(result.value.classes.path).exists(_.last == "Hello.class")
        )

        val Right(_) = eval.apply(m.run()): @unchecked
      }
    }

    test("compile & run Groovy JUnit5 test") {
      testEval().scoped { eval =>

        val Right(result) = eval.apply(m.test.compile): @unchecked

        assert(
          os.walk(result.value.classes.path).exists(_.last == "HelloTest.class")
        )

        val Right(discovered) = eval.apply(m.test.discoveredTestClasses): @unchecked
        assert(discovered.value == Seq("hello.tests.HelloTest"))

        val Right(_) = eval.apply(m.test.testForked()): @unchecked
      }
    }

    test("compile & run a statically compiled Groovy") {
      testEval().scoped { eval =>
        val Right(result) = eval.apply(m.staticcompile.compile): @unchecked
        assert(
          os.walk(result.value.classes.path).exists(_.last == "HelloStatic.class")
        )
        val Right(_) = eval.apply(m.staticcompile.run()): @unchecked
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

        val Right(result1) = eval.apply(m.spock.compile): @unchecked
        assert(
          os.walk(result1.value.classes.path).exists(_.last == "SpockTest.class")
        )

        val Right(discovered) = eval.apply(m.spock.discoveredTestClasses): @unchecked
        assert(discovered.value == Seq("hello.spock.SpockTest"))

        val Right(_) = eval.apply(m.spock.testForked()): @unchecked
      }
    }

    test("compile joint (groovy <-> java cycle) & run") {
      testEval().scoped { eval =>
        val Right(result) = eval.apply(joint.compile): @unchecked

        assert(
          os.walk(result.value.classes.path).exists(_.last == "JavaPrinter.class")
        )
        assert(
          os.walk(result.value.classes.path).exists(_.last == "GroovyGreeter.class")
        )

        val Right(_) = eval.apply(joint.run()): @unchecked
      }
    }

  }
}

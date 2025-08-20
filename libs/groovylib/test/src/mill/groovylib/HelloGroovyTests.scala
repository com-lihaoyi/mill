package mill
package groovylib

import mill.javalib.{JavaModule, MavenModule, TestModule}
import mill.api.{ExecResult, Task}
import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object HelloGroovyTests extends TestSuite {

  val junit5Version = sys.props.getOrElse("TEST_JUNIT5_VERSION", "5.13.1")

  object HelloGroovy extends TestRootModule {

    lazy val millDiscover = Discover[this.type]

    object maven extends JavaModule with MavenModule {

      object `maven-test-only` extends TestGroovyMavenModule with TestModule.Junit5 {

        override def groovyVersion = "4.0.28"
        override def testModuleName: String = "test"

        override def depManagement = Seq(
          mvn"org.junit.jupiter:junit-jupiter-engine:5.13.4"
        )

        override def jupiterVersion = "5.13.4"
        override def junitPlatformVersion = "1.13.4"
      }

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

      object script extends GroovyTests with TestModule.Junit5 {
        override def depManagement = Seq(
          mvn"org.junit.jupiter:junit-jupiter-engine:5.13.4"
        )

        override def jupiterVersion = "5.13.4"
        override def junitPlatformVersion = "1.13.4"

        override def groovyVersion = "4.0.28"
        override def mainClass = Some("HelloScript")
      }

      object spock extends GroovyTests with TestModule.Junit5 {
        override def junitPlatformVersion = "1.13.4"
        def spockVersion: T[String] = "2.3-groovy-4.0"
        override def groovyVersion = "4.0.28"

        def bomMvnDeps = Seq(
          mvn"org.junit:junit-bom:5.13.4",
          mvn"org.apache.groovy:groovy-bom:${groovyVersion()}",
          mvn"org.spockframework:spock-bom:${spockVersion()}"
        )

        def mvnDeps = Seq(
          mvn"org.spockframework:spock-core"
        )
      }
    }
    object main extends Test {
      override def groovyVersion: T[String] = "4.0.28"
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-groovy"

  /**
   * Compiles test files located within resources
   */
  def testEval() = UnitTester(HelloGroovy, resourcePath)

  def tests: Tests = Tests {

    def m = HelloGroovy.main
    def mavenTestOnly = HelloGroovy.maven.`maven-test-only`

    test("running a Groovy script") {
      testEval().scoped { eval =>
        val Right(_) = eval.apply(m.script.run()): @unchecked
      }
    }

    test("compile Groovy module") {
      testEval().scoped { eval =>
        val Right(compiler) = eval.apply(m.groovyCompilerMvnDeps): @unchecked

        assert(
          compiler.value.map(_.dep.module)
            .map(m => m.organization.value -> m.name.value)
            .contains("org.apache.groovy" -> "groovy")
        )

        val Right(result) = eval.apply(m.compile): @unchecked

        assert(
          os.walk(result.value.classes.path).exists(_.last == "Hello.class")
        )
      }

      test("run Groovy module") {
        testEval().scoped { eval =>
          val Right(_) = eval.apply(m.run()): @unchecked
        }
      }
    }

    test("compile Groovy JUnit5 test") {
      testEval().scoped { eval =>

        val Right(result) = eval.apply(m.test.compile): @unchecked

        assert(
          os.walk(result.value.classes.path).exists(_.last == "HelloTest.class")
        )
      }
    }

    test("run JUnit5 test") {
      testEval().scoped { eval =>
        val Right(discovered) = eval.apply(m.test.discoveredTestClasses): @unchecked
        assert(discovered.value == Seq("hello.tests.HelloTest"))
      }
      testEval().scoped { eval =>
        val Left(ExecResult.Failure(_)) = eval.apply(m.test.testForked()): @unchecked
      }
    }

    test("compile and run test-only Maven JUnit5 test") {
      testEval().scoped { eval =>

        val Right(resultCompile) = eval.apply(mavenTestOnly.compile): @unchecked
        assert(
          os.walk(resultCompile.value.classes.path).exists(_.last == "HelloMavenTestOnly.class")
        )

        val Right(discovered) = eval.apply(mavenTestOnly.discoveredTestClasses): @unchecked
        assert(discovered.value == Seq("hello.maven.tests.HelloMavenTestOnly"))

        val Right(_) = eval.apply(mavenTestOnly.testForked()): @unchecked
      }
    }

    test("compile Spock test") {
      testEval().scoped { eval =>

        val Right(result1) = eval.apply(m.spock.compile): @unchecked
        assert(
          os.walk(result1.value.classes.path).exists(_.last == "SpockTest.class")
        )
      }
    }

    test("run Spock test") {
      testEval().scoped { eval =>

        val Right(discovered) = eval.apply(m.spock.discoveredTestClasses): @unchecked
        assert(discovered.value == Seq("hello.spock.SpockTest"))

        val Left(ExecResult.Failure(_)) = eval.apply(m.spock.testForked()): @unchecked
      }
    }

  }
}

package mill.kotlinlib.contrib.kover

import mill.kotlinlib.{DepSyntax, KotlinModule}
import mill.kotlinlib.TestModule
import mill.testkit.{TestBaseModule, UnitTester}
import mill.{Agg, T, Task, api}
import utest.{TestSuite, Tests, assert, test}

import scala.xml.{Node, XML}

object KoverModuleTests extends TestSuite {

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "contrib" / "kover"

  object module extends TestBaseModule {

    trait KotestTestModule extends TestModule.Junit5 {
      override def forkArgs: T[Seq[String]] = Task {
        super.forkArgs() ++ Seq("-Dkotest.framework.classpath.scanning.autoscan.disable=true")

      }
      override def ivyDeps = super.ivyDeps() ++ Agg(
        ivy"io.kotest:kotest-runner-junit5-jvm:5.9.1"
      )
    }

    object foo extends KotlinModule with KoverModule {
      def kotlinVersion = "1.9.24"
      object test extends KotlinModuleTests with module.KotestTestModule with KoverTests
    }

    object bar extends KotlinModule with KoverModule {
      def kotlinVersion = "1.9.24"
      object test extends KotlinModuleTests with module.KotestTestModule with KoverTests
    }

    // module not instrumented with Kover
    object qux extends KotlinModule {
      def kotlinVersion = "1.9.24"
      object test extends KotlinModuleTests with module.KotestTestModule
    }

    object kover extends KoverReportModule
  }

  def tests: Tests = Tests {

    test("reportAll") {

      val eval = UnitTester(module, resourcePath)

      Seq(module.foo.test.test(), module.bar.test.test(), module.qux.test.test())
        .foreach(
          eval(_)
            .fold(
              {
                case api.Result.Exception(cause, _) => throw cause
                case failure => throw failure
              },
              { _ => }
            )
        )

      val Right(result) = eval(module.kover.xmlReportAll(eval.evaluator))

      val xmlReportPath = result.value.path
      assert(os.exists(xmlReportPath))
      val relPath = xmlReportPath.segments.toVector.dropRight(1).takeRight(3)
      assert(relPath.head == "out")
      assert(relPath(1) == "kover")
      assert(relPath(2) == "xmlReportAll.dest")

      val xmlReport = XML.loadFile(xmlReportPath.toString)

      // total
      assert(instructionsCovered(xmlReport) != 0)
      // per package
      assert(instructionsCovered(packageNameChildNode(xmlReport, "foo").get) != 0)
      assert(instructionsCovered(packageNameChildNode(xmlReport, "bar").get) != 0)
      assert(instructionsCovered(packageNameChildNode(xmlReport, "qux").get) == 0)

    }

    test("report") {

      val eval = UnitTester(module, resourcePath)

      val Right(_) = eval(module.foo.test.test())

      val Right(result) = eval(module.foo.kover.xmlReport())

      val xmlReportPath = result.value.path
      assert(os.exists(xmlReportPath))

      // drop report name
      val relPath = xmlReportPath.segments.toVector.dropRight(1).takeRight(3)
      assert(relPath.head == "foo")
      assert(relPath(1) == "kover")
      assert(relPath(2) == "xmlReport.dest")

      val xmlReport = XML.loadFile(xmlReportPath.toString)

      // total
      assert(instructionsCovered(xmlReport) != 0)
      // per package
      assert(instructionsCovered(packageNameChildNode(xmlReport, "foo").get) != 0)
      assert(packageNameChildNode(xmlReport, "bar").isEmpty)
      assert(packageNameChildNode(xmlReport, "qux").isEmpty)

    }
  }

  private def instructionsCovered = (node: Node) => {
    val counter = (node \ "counter")
      .find(counter => (counter \@ "type") == "INSTRUCTION").get
    (counter \@ "covered").toInt
  }

  private def packageNameChildNode = (node: Node, packageName: String) => {
    (node \ "package").find(p => (p \@ "name") == packageName)
  }
}

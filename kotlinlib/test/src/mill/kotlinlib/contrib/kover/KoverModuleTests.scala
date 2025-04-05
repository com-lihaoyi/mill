package mill.kotlinlib.kover

import mill.define.Discover
import mill.util.TokenReaders._
import mill.kotlinlib.{DepSyntax, KotlinModule}
import mill.kotlinlib.TestModule
import mill.testkit.{TestBaseModule, UnitTester}
import mill.{T, Task, api}
import utest.{TestSuite, Tests, assert, test}

import scala.xml.{Node, XML}

object KoverModuleTests extends TestSuite {

  val kotlinVersion = "1.9.24"

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "contrib/kover"

  object module extends TestBaseModule {

    trait KotestTestModule extends TestModule.Junit5 {
      override def forkArgs: T[Seq[String]] = Task {
        super.forkArgs() ++ Seq("-Dkotest.framework.classpath.scanning.autoscan.disable=true")

      }
      override def ivyDeps = super.ivyDeps() ++ Seq(
        ivy"io.kotest:kotest-runner-junit5-jvm:5.9.1"
      )
    }

    object foo extends KotlinModule with KoverModule {
      def kotlinVersion = KoverModuleTests.kotlinVersion
      object test extends KotlinTests with module.KotestTestModule with KoverTests
    }

    object bar extends KotlinModule with KoverModule {
      def kotlinVersion = KoverModuleTests.kotlinVersion
      object test extends KotlinTests with module.KotestTestModule with KoverTests
    }

    // module not instrumented with Kover
    object qux extends KotlinModule {
      def kotlinVersion = KoverModuleTests.kotlinVersion
      object test extends KotlinTests with module.KotestTestModule
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("reportAll") {

      val eval = UnitTester(module, resourcePath)

      Seq(module.foo.test.testForked(), module.bar.test.testForked(), module.qux.test.testForked())
        .foreach(eval(_).get)

      val Right(result) = eval(Kover.xmlReportAll(eval.evaluator)): @unchecked

      val xmlReportPath = result.value.path
      assert(os.exists(xmlReportPath))
      val relPath = xmlReportPath.segments.toVector.takeRight(2)
      assert(relPath.head == "xmlReportAll.dest")
      assert(relPath.last == "kover-report.xml")

      val xmlReport = XML.loadFile(xmlReportPath.toString)

      // total
      assert(instructionsCovered(xmlReport) != 0)
      // per package
      assert(instructionsCovered(packageNameChildNode(xmlReport, "foo").get) != 0)
      assert(instructionsCovered(packageNameChildNode(xmlReport, "bar").get) != 0)
      assert(instructionsCovered(packageNameChildNode(xmlReport, "qux").get) == 0)

    }

    test("report-xml") {

      val eval = UnitTester(module, resourcePath)

      val Right(_) = eval(module.foo.test.testForked()): @unchecked

      val Right(result) = eval(module.foo.kover.xmlReport()): @unchecked

      val xmlReportPath = result.value.path
      assert(os.exists(xmlReportPath))
      assert(os.isFile(xmlReportPath))

      // drop report name
      val relPath = xmlReportPath.segments.toVector.takeRight(4)
      assert(relPath.head == "foo")
      assert(relPath(1) == "kover")
      assert(relPath(2) == "xmlReport.dest")
      assert(relPath(3) == "kover-report.xml")

      val xmlReport = XML.loadFile(xmlReportPath.toString)

      // total
      assert(instructionsCovered(xmlReport) != 0)
      // per package
      assert(instructionsCovered(packageNameChildNode(xmlReport, "foo").get) != 0)
      assert(packageNameChildNode(xmlReport, "bar").isEmpty)
      assert(packageNameChildNode(xmlReport, "qux").isEmpty)

    }

    test("report-html") {

      val eval = UnitTester(module, resourcePath)

      val Right(_) = eval(module.foo.test.testForked()): @unchecked

      val Right(result) = eval(module.foo.kover.htmlReport()): @unchecked

      val htmlReportPath = result.value.path
      assert(os.exists(htmlReportPath))
      assert(os.isDir(htmlReportPath))
      assert(os.walk(htmlReportPath)
        .exists(p => p.ext == "html"))

      // drop report name
      val relPath = htmlReportPath.segments.toVector.takeRight(4)
      assert(relPath.head == "foo")
      assert(relPath(1) == "kover")
      assert(relPath(2) == "htmlReport.dest")
      assert(relPath(3) == "kover-report")
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

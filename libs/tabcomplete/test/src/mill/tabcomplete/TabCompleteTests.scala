package mill.tabcomplete

import mill.Task
import mill.define.{Cross, Discover, Module}
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.{TestSuite, Tests, assert, test}

import java.io.{ByteArrayOutputStream, PrintStream}

object TabCompleteTests extends TestSuite {

  object mainModule extends TestRootModule {
    lazy val millDiscover = Discover[this.type]
    def task1 = Task { 123 }
    object foo extends Module
    object bar extends Module {
      def task2 = Task { 456 }

    }
    object qux extends Cross[QuxModule](12, 34, 56)
    trait QuxModule extends Cross.Module[Int] {
      def task3 = Task { 789 }
    }
  }
  override def tests: Tests = Tests {

    val outStream = new ByteArrayOutputStream()
    val errStream = new ByteArrayOutputStream()

    def evalComplete(s: String*) = {
      UnitTester(
        mainModule,
        null,
        outStream = new PrintStream(outStream),
        errStream = new PrintStream(errStream)
      ).scoped { tester =>
        tester.evaluator.evaluate(Seq("mill.tabcomplete.TabCompleteModule/complete") ++ s).get
      }
      outStream.toString
    }

    test("empty-bash") - {
      val out = evalComplete("1", "./mill", "")
      val expected =
        """bar
          |foo
          |qux
          |task1
          |""".stripMargin
      assert(out == expected)
    }
    test("empty-zsh") - {
      val out = evalComplete("1", "./mill")
      val expected =
        """bar
          |foo
          |qux
          |task1
          |""".stripMargin
      assert(out == expected)
    }
    test("task") - {
      val out = evalComplete("1", "./mill", "t")
      val expected =
        """task1
          |""".stripMargin
      assert(out == expected)
    }
    test("firstTask") - {
      val out = evalComplete("1", "./mill", "t", "bar.task2")
      val expected =
        """task1
          |""".stripMargin
      assert(out == expected)
    }

    test("secondTask") - {
      val out = evalComplete("2", "./mill", "bar.task2", "t")
      val expected =
        """task1
          |""".stripMargin
      assert(out == expected)
    }

    test("module") - {
      val out = evalComplete("1", "./mill", "fo")
      val expected =
        """foo
          |""".stripMargin
      assert(out == expected)
    }

    test("exactModule") - {
      val out = evalComplete("1", "./mill", "bar")
      val expected =
        """bar
          |bar.task2
          |""".stripMargin
      assert(out == expected)
    }

    test("nested") - {
      val out = evalComplete("1", "./mill", "bar.")
      val expected =
        """bar.task2
          |""".stripMargin
      assert(out == expected)
    }

    test("cross") - {
      val out = evalComplete("1", "./mill", "qux[")
      val expected =
        """qux[12]
          |qux[34]
          |qux[56]
          |""".stripMargin
      assert(out == expected)
    }

    test("crossPartial") - {
      val out = evalComplete("1", "./mill", "qux[1")
      val expected =
        """qux[12]
          |""".stripMargin
      assert(out == expected)
    }

    test("crossNested") - {
      val out = evalComplete("1", "./mill", "qux[12]")
      val expected =
        """qux[12].task3
          |""".stripMargin
      assert(out == expected)
    }

    test("crossNestedSlashed") - {
      val out = evalComplete("1", "./mill", "qux\\[12\\]")
      val expected =
        """qux[12].task3
          |""".stripMargin
      assert(out == expected)
    }
    test("crossNestedSingleQuoted") - {
      val out = evalComplete("1", "./mill", "'qux[12]")
      val expected =
        """qux[12].task3
          |""".stripMargin
      assert(out == expected)
    }
    test("crossNestedDoubleQuoted") - {
      val out = evalComplete("1", "./mill", "\"qux[12]")
      val expected =
        """qux[12].task3
          |""".stripMargin
      assert(out == expected)
    }

    test("crossComplete") - {
      val out = evalComplete("1", "./mill", "qux[12].task3")
      val expected =
        """qux[12].task3
          |""".stripMargin
      assert(out == expected)
    }
  }
}

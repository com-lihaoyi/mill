package mill.tabcompletion

import mill.Task
import mill.define.{Cross, Discover, Module}
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.{TestSuite, Tests, assert, test}

import java.io.{ByteArrayOutputStream, PrintStream}

object TabCompletionTests extends TestSuite {

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
    def unitTester = UnitTester(
      mainModule,
      null,
      outStream = new PrintStream(outStream),
      errStream = PrintStream(errStream)
    )

    test("task") - unitTester.scoped { tester =>
      tester.evaluator.evaluate(Seq("mill.tabcompletion/tabComplete", "0", "t")).get
      val out = outStream.toString
      val expected =
        """task1
          |""".stripMargin
      assert(out == expected)
    }

    test("module") - unitTester.scoped { tester =>
      tester.evaluator.evaluate(Seq("mill.tabcompletion/tabComplete", "0", "fo")).get
      val out = outStream.toString
      val expected =
        """foo
          |""".stripMargin
      assert(out == expected)
    }

    test("nested") - unitTester.scoped { tester =>
      tester.evaluator.evaluate(Seq("mill.tabcompletion/tabComplete", "0", "bar.")).get
      val out = outStream.toString
      val expected =
        """bar.task2
          |""".stripMargin
      assert(out == expected)
    }

    test("cross") - unitTester.scoped { tester =>
      tester.evaluator.evaluate(Seq("mill.tabcompletion/tabComplete", "0", "qux[")).get
      val out = outStream.toString
      val expected =
        """qux[12]
          |qux[34]
          |qux[56]
          |""".stripMargin
      assert(out == expected)
    }

    test("crossPartial") - unitTester.scoped { tester =>
      tester.evaluator.evaluate(Seq("mill.tabcompletion/tabComplete", "0", "qux[1")).get
      val out = outStream.toString
      val expected =
        """qux[12]
          |""".stripMargin
      assert(out == expected)
    }

    test("crossNested") - unitTester.scoped { tester =>
      tester.evaluator.evaluate(Seq("mill.tabcompletion/tabComplete", "0", "qux[12]")).get
      val out = outStream.toString
      val expected =
        """qux[12].task3
          |""".stripMargin
      assert(out == expected)
    }

    test("crossNestedSlashed") - unitTester.scoped { tester =>
      tester.evaluator.evaluate(Seq("mill.tabcompletion/tabComplete", "0", "qux\\[12\\]")).get
      val out = outStream.toString
      val expected =
        """qux[12].task3
          |""".stripMargin
      assert(out == expected)
    }

    test("crossComplete") - unitTester.scoped { tester =>
      tester.evaluator.evaluate(Seq("mill.tabcompletion/tabComplete", "0", "qux[12].task3")).get
      val out = outStream.toString
      val expected =
        """qux[12].task3
          |""".stripMargin
      assert(out == expected)
    }
  }
}

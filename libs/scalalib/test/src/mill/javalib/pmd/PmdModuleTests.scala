package mill.javalib.pmd

import mill.define.{Discover, Module, Task}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object PmdModuleTests extends TestSuite {

  val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "pmd"

  abstract class SingleModule extends TestRootModule, PmdModule {
    lazy val millDiscover = Discover[this.type]
  }

  abstract class MultiModule extends TestRootModule {
    lazy val millDiscover = Discover[this.type]
    object bar extends PmdModule
    object foo extends PmdModule
  }

  abstract class RootModule extends TestRootModule, PmdModule {
    lazy val millDiscover = Discover[this.type]
    object bar extends Module
    object foo extends Module
  }

  def tests = Tests {
    test("violations") {
      val logStream = ByteArrayOutputStream()
      object module extends SingleModule
      UnitTester(
        module,
        resources / "violations",
        outStream = PrintStream(logStream, true)
      ).scoped { eval =>
        val Left(_) = eval("pmd")
        val log = logStream.toString()
        assert(
          log.contains("ImmutableField.java:2:\tImmutableField:\tField 'x' may be declared final"),
          log.contains(
            "LooseCoupling.java:5:\tLooseCoupling:\tAvoid using implementation types like 'HashSet'; use the interface instead"
          )
        )
      }
    }

    test("no-violations") {
      object module extends SingleModule
      val logStream = ByteArrayOutputStream()
      UnitTester(
        module,
        resources / "no-violations",
        errStream = PrintStream(logStream, true)
      ).scoped { eval =>
        val Right(_) = eval("pmd")
        val log = logStream.toString()
        assert(log.contains("no violations found"))
      }
    }

    test("excludes") {
      val logStream = ByteArrayOutputStream()
      object module extends SingleModule {
        def pmdExcludes = Task.Sources("src/ImmutableField.java")
      }
      UnitTester(
        module,
        resources / "violations",
        outStream = PrintStream(logStream, true)
      ).scoped { eval =>
        val Left(_) = eval("pmd")
        val log = logStream.toString()
        assert(
          !log.contains("ImmutableField.java:2:\tImmutableField:\tField 'x' may be declared final"),
          log.contains(
            "LooseCoupling.java:5:\tLooseCoupling:\tAvoid using implementation types like 'HashSet'; use the interface instead"
          )
        )
      }
    }

    test("use-version") {
      val logStream = ByteArrayOutputStream()
      object module extends SingleModule
      UnitTester(
        module,
        resources / "use-version",
        outStream = PrintStream(logStream, true)
      ).scoped { eval =>
        val Left(_) = eval("pmd")
        val log = logStream.toString()
        assert(
          log.contains(
            "AccessorClassGeneration.java:6:\tAccessorClassGeneration:\tAvoid instantiation through private constructors from outside of the constructors class."
          )
        )
      }
    }

    test("multi-module") {
      val logStream = ByteArrayOutputStream()
      object module extends MultiModule
      UnitTester(
        module,
        resources / "multi-module",
        outStream = PrintStream(logStream, true)
      ).scoped { eval =>
        val Left(_) = eval("bar.pmd")
        var log = logStream.toString()
        assert(log.contains(
          "ImmutableField.java:2:\tImmutableField:\tField 'x' may be declared final"
        ))

        logStream.reset()
        val Left(_) = eval("foo.pmd")
        log = logStream.toString()
        assert(log.contains(
          "LooseCoupling.java:5:\tLooseCoupling:\tAvoid using implementation types like 'HashSet'; use the interface instead"
        ))
      }
    }

    test("root-module") {
      val logStream = ByteArrayOutputStream()
      object module extends RootModule
      UnitTester(
        module,
        resources / "multi-module",
        outStream = PrintStream(logStream, true)
      ).scoped { eval =>
        val Left(_) = eval("pmd")
        val log = logStream.toString()
        assert(
          log.contains("ImmutableField.java:2:\tImmutableField:\tField 'x' may be declared final"),
          log.contains(
            "LooseCoupling.java:5:\tLooseCoupling:\tAvoid using implementation types like 'HashSet'; use the interface instead"
          )
        )
      }
    }
  }
}

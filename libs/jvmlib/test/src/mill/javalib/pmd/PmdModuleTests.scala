package mill.javalib
package pmd

import mill.api.{Discover, Module, Task}
import mill.scalalib.JavaHomeModule
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.given
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object PmdModuleTests extends TestSuite {

  val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "pmd"

  abstract class SingleModule extends TestRootModule, PmdModule, JavaModule {
    lazy val millDiscover = Discover[this.type]
  }

  abstract class MultiModule extends TestRootModule {
    lazy val millDiscover = Discover[this.type]
    object bar extends PmdModule, JavaModule
    object foo extends PmdModule, JavaModule
  }

  abstract class MultiModule0 extends TestRootModule, PmdModule, JavaModule {
    lazy val millDiscover = Discover[this.type]
    object bar extends Module
    object foo extends Module
  }

  def tests = Tests {
    test("pmd") {
      object module extends SingleModule
      val logStream = ByteArrayOutputStream()
      UnitTester(
        module,
        resources / "pmd",
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

        for fix <- os.walk(module.moduleDir / "fix")
        do os.move.into(fix, module.moduleDir / "src", replaceExisting = true)
        val Right(_) = eval("pmd")
      }
    }

    test("pmdExcludes") {
      object module extends SingleModule {
        def pmdExcludes = Task.Sources("src/ImmutableField.java")
      }
      val logStream = ByteArrayOutputStream()
      UnitTester(
        module,
        resources / "pmd",
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

    test("pmdUseVersion") {
      object module extends SingleModule, JavaHomeModule {
        def jvmId = "11"
      }
      val logStream = ByteArrayOutputStream()
      UnitTester(
        module,
        resources / "pmdUseVersion",
        outStream = PrintStream(logStream, true)
      ).scoped { eval =>
        val Left(_) = eval("pmd")
        val log = logStream.toString()
        assert(
          log.contains(
            "AccessorClassGeneration.java:6:\tAccessorClassGeneration:\tAvoid instantiation through private constructors from outside of the constructors class."
          )
        )

        os.remove(module.moduleDir / ".pmd-use-version")
        val Right(_) = eval("pmd")
      }
    }

    test("multi-module") {
      test("per sub-module setup") {
        object module extends MultiModule
        val logStream = ByteArrayOutputStream()
        UnitTester(
          module,
          resources / "multi-module",
          outStream = PrintStream(logStream, true)
        ).scoped { eval =>
          val Left(_) = eval("bar.pmd")
          var log = logStream.toString()
          assert(
            log.contains(
              "ImmutableField.java:2:\tImmutableField:\tField 'x' may be declared final"
            ),
            !log.contains(
              "LooseCoupling.java:5:\tLooseCoupling:\tAvoid using implementation types like 'HashSet'; use the interface instead"
            )
          )

          logStream.reset()
          val Left(_) = eval("foo.pmd")
          log = logStream.toString()
          assert(
            !log.contains(
              "ImmutableField.java:2:\tImmutableField:\tField 'x' may be declared final"
            ),
            log.contains(
              "LooseCoupling.java:5:\tLooseCoupling:\tAvoid using implementation types like 'HashSet'; use the interface instead"
            )
          )
        }
      }

      test("root module only setup") {
        object module extends MultiModule0
        val logStream = ByteArrayOutputStream()
        UnitTester(
          module,
          resources / "multi-module",
          outStream = PrintStream(logStream, true)
        ).scoped { eval =>
          val Left(_) = eval("pmd")
          val log = logStream.toString()
          assert(
            log.contains(
              "ImmutableField.java:2:\tImmutableField:\tField 'x' may be declared final"
            ),
            log.contains(
              "LooseCoupling.java:5:\tLooseCoupling:\tAvoid using implementation types like 'HashSet'; use the interface instead"
            )
          )
        }
      }
    }
  }
}

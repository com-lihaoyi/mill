package mill.javalib.pmd

import mill.define.{Discover, Module, Task}
import mill.scalalib.JavaHomeModule
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

  abstract class MultiModule0 extends TestRootModule, PmdModule {
    lazy val millDiscover = Discover[this.type]
    object bar extends Module
    object foo extends Module
  }

  def tests = Tests {
    test("pmd") {
      val logStream = ByteArrayOutputStream()
      object module extends SingleModule
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
      val logStream = ByteArrayOutputStream()
      object module extends SingleModule {
        def pmdExcludes = Task.Sources("src/ImmutableField.java")
      }
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
      val logStream = ByteArrayOutputStream()
      object module extends SingleModule, JavaHomeModule {
        def jvmId = "11"
      }
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

      test("root module only setup") {
        val logStream = ByteArrayOutputStream()
        object module extends MultiModule0
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

package mill.contrib.sbom

import mill.{Agg, T}
import mill.javalib.*
import mill.testkit.{TestBaseModule, UnitTester}
import utest.{TestSuite, Tests, test}
object CycloneDXModuleTests extends TestSuite {
  object TestModule extends TestBaseModule {
    object withDeps extends JavaModule with CycloneDXModule {
      override def ivyDeps = Agg(ivy"ch.qos.logback:logback-classic:1.5.12")
    }
    object withModuleDeps extends JavaModule with CycloneDXModule {
      override def moduleDeps = Seq(withDeps)
      override def ivyDeps = Agg(ivy"commons-io:commons-io:2.18.0")

    }
  }

  override def tests = Tests {
    test("Report dependencies of a single module") - UnitTester(TestModule, null).scoped { eval =>
      val Right(result) = eval.apply(TestModule.withDeps.sbom)
      val components = result.value.components
      assert(components.size == 3)
      assert(components.exists(_.name == "logback-classic"))
      assert(components.exists(_.name == "logback-core"))
      assert(components.exists(_.name == "slf4j-api"))
    }
    test("Report transitive module dependenties") - UnitTester(TestModule, null).scoped { eval =>
      val Right(result) = eval.apply(TestModule.withModuleDeps.sbom)
      val components = result.value.components
      assert(components.size == 4)
      assert(components.exists(_.name == "commons-io"))
      assert(components.exists(_.name == "logback-classic"))
      assert(components.exists(_.name == "logback-core"))
      assert(components.exists(_.name == "slf4j-api"))
    }
  }
}

package mill.contrib.sbom

import mill.*
import mill.Agg
import mill.javalib.*
import mill.testkit.{TestBaseModule, UnitTester}
import utest.{TestSuite, Tests, test}
import mill.define.{Discover, Target}

object TestModule extends TestBaseModule {

  object noDeps extends JavaModule with CycloneDXModule {}

  object withDeps extends JavaModule with CycloneDXModule {
    override def ivyDeps = Agg(ivy"ch.qos.logback:logback-classic:1.5.12")
  }

  object withModuleDeps extends JavaModule with CycloneDXModule {
    override def moduleDeps = Seq(withDeps)

    override def ivyDeps = Agg(ivy"commons-io:commons-io:2.18.0")
  }

  lazy val millDiscover = Discover[this.type]
}
object CycloneDXModuleTests extends TestSuite {

  override def tests = Tests {
    test("Report dependencies of an module without dependencies") - UnitTester(
      TestModule,
      null
    ).scoped { eval =>
      val Right(result) = eval.apply(TestModule.noDeps.sbom)
      val components = result.value.components
      assert(components.size == 0)
    }
    test("Report dependencies of a single module") - UnitTester(TestModule, null).scoped { eval =>
      val Right(result) = eval.apply(TestModule.withDeps.sbom)
      val components = result.value.components
      assert(components.size == 3)
      println(components)
      // TODO: Add license. Probably import the Cyclone stuff?
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

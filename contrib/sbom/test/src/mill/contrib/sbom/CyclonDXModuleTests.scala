package mill.contrib.sbom

import mill.Agg
import mill.contrib.sbom.CyclonDXModuleTests.TestModule.withDeps
import mill.javalib.*
import mill.testkit.{TestBaseModule, UnitTester}
import os.Path
import utest.{TestSuite, Tests, test}
object CyclonDXModuleTests extends TestSuite{
  object TestModule extends TestBaseModule {
    case object withDeps extends JavaModule with CycloneDXModule{
      def ivyDeps = Agg(
        ivy"ch.qos.logback:logback-classic:1.2.3"
      )
    }
  }

  override def tests = Tests{
    test("demo") - UnitTester(TestModule, null).scoped { eval =>
      val Right(result) = eval.apply(TestModule.withDeps.sbom)
      println(result.value.components.size)
      println(upickle.default.write(result.value))
      assert(result.value.components.size == 3)
    }
  }
}

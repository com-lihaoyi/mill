package mill.contrib.sbom

import mill.*
import mill.javalib.*
import mill.testkit.{TestRootModule, UnitTester}
import utest.{TestSuite, Tests, test}
import mill.api.{Discover, Task}

import java.util.UUID
import java.time.Instant

object TestModule extends TestRootModule {

  val fixedHeader = CycloneDX.SbomHeader(
    UUID.fromString("a9d6a1c7-18d4-4901-891c-cbcc8f2c5241"),
    Instant.parse("2025-03-17T17:00:56.263933698Z")
  )

  object noDeps extends JavaModule with CycloneDXJavaModule {}

  object withDeps extends JavaModule with CycloneDXJavaModule {
    override def sbomHeader(): CycloneDX.SbomHeader = fixedHeader
    override def mvnDeps = Seq(mvn"ch.qos.logback:logback-classic:1.5.12")
  }

  object withModuleDeps extends JavaModule with CycloneDXJavaModule {
    override def sbomHeader(): CycloneDX.SbomHeader = fixedHeader
    override def moduleDeps = Seq(withDeps)
    override def mvnDeps = Seq(mvn"commons-io:commons-io:2.18.0")
  }

  object noLicenseUrl extends JavaModule with CycloneDXJavaModule {
    override def sbomHeader(): CycloneDX.SbomHeader = fixedHeader
    // Example of library with a 'null' in the licence URL.
    override def mvnDeps = Seq(mvn"org.tukaani:xz:1.9")
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
      val toTest = TestModule.withDeps
      val Right(result) = eval.apply(toTest.sbom)
      val Right(file) = eval.apply(toTest.sbomJsonFile)
      val components = result.value.components
      assert(components.size == 3)
      assert(components.exists(_.name == "logback-classic"))
      assert(components.exists(_.name == "logback-core"))
      assert(components.exists(_.name == "slf4j-api"))

      assertSameAsReference("withDeps.sbom.json", file.value)
    }
    test("Report transitive module dependencies") - UnitTester(TestModule, null).scoped { eval =>
      val toTest = TestModule.withModuleDeps
      val Right(result) = eval.apply(toTest.sbom)
      val Right(file) = eval.apply(toTest.sbomJsonFile)
      val components = result.value.components
      assert(components.size == 4)
      assert(components.exists(_.name == "commons-io"))
      assert(components.exists(_.name == "logback-classic"))
      assert(components.exists(_.name == "logback-core"))
      assert(components.exists(_.name == "slf4j-api"))

      assertSameAsReference("withModuleDeps.sbom.json", file.value)
    }
    test("avoid null in license.url") - UnitTester(TestModule, null).scoped { eval =>
      val toTest = TestModule.noLicenseUrl
      val Right(result) = eval.apply(toTest.sbom)
      val Right(file) = eval.apply(toTest.sbomJsonFile)
      val components = result.value.components
      assert(components.size == 1)
      val component = components.head
      assert(component.name == "xz")
      val license = components.head.licenses.head
      assert(license.license.url.isEmpty)

      // Nulls are not allowed: Leave out the field if not specified
      val asFile = os.read(file.value.path)
      assert(!asFile.contains("null"))
    }
  }

  private def assertSameAsReference(refFile: String, file: PathRef) = {
    val reference = String(getClass.getClassLoader.getResourceAsStream(refFile).readAllBytes())
    val current = os.read(file.path)
    val actualContentPath = os.pwd / refFile
    if (reference != current) {
      os.write(actualContentPath, current)
    }
    assert(
      reference == current,
      s"The reference file and the current generated SBOM file should match. " +
        s"Reference $refFile. Actual file content at: $actualContentPath"
    )
  }
}

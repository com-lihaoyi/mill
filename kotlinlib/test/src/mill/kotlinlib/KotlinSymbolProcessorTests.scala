package mill.kotlinlib

import mill.testkit.{TestBaseModule, UnitTester}
import mill.{Agg, T}
import utest.*

object KotlinSymbolProcessorTests extends TestSuite {

  object SymbolProcessorApp extends TestBaseModule with KotlinModule {

    /**
     * The Kotlin version to be used (for API and Language level settings).
     */
    override def kotlinVersion: T[String] = "2.1.0"

    override def kotlinCompilerPlugins: T[Agg[Dep]] = Agg(
      ivy"com.google.devtools.ksp:symbol-processing-api:${kotlinVersion()}-1.0.29",
      ivy"com.google.devtools.ksp:symbol-processing-cmdline:${kotlinVersion()}-1.0.29"
    )

    override def kotlincOptions: T[Seq[String]] = super.kotlincOptions() ++ Seq(
      "-Xallow-no-source-files"
    )

    override def kotlinSymbolProcessors: T[Agg[Dep]] = Agg(
      ivy"com.google.devtools.ksp:symbol-processing-cmdline:${kotlinVersion()}-1.0.29"
    )
  }

  override def tests: Tests = Tests {
    val outDir = os.temp.dir() / "kotlin-symbol-test"
    os.makeDir.all(outDir)
    test("resolved compiler plugins") - UnitTester(
      SymbolProcessorApp,
      sourceRoot = outDir
    ).scoped { eval =>
      val Right(declaredPlugins) = eval(SymbolProcessorApp.kotlinCompilerPlugins)
      val Right(resolvedPlugins) = eval(SymbolProcessorApp.kotlinCompilerPluginsResolved)
      val Right(kotlinVersion) = eval(SymbolProcessorApp.kotlinVersion)
      resolvedPlugins.value.size ==> declaredPlugins.value.size
      resolvedPlugins.value.map(
        _.path
      ).map(_.baseName).find(_.contains("symbol-processing-api")) ==>
        Some(s"symbol-processing-api-${kotlinVersion.value}-1.0.29")

    }

  }
}

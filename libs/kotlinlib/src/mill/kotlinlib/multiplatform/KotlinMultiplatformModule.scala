package mill.kotlinlib.multiplatform

import mill.*
import mill.kotlinlib.KotlinModule
import mill.kotlinlib.js.KotlinJsModule

/**
 * A Kotlin Multiplatform module, with one [[PlatformModule]] submodule per
 * target platform, e.g.
 *
 * {{{
 * object foo extends KotlinMultiplatformModule {
 *   def kotlinVersion = "2.2.20"
 *
 *   object jvm extends PlatformModule {
 *     object test extends PlatformTests, TestModule.Junit4
 *   }
 *   object js extends PlatformModule, KotlinJsModule {
 *     object test extends PlatformTests, KotlinTestPackageTests
 *   }
 * }
 * }}}
 *
 * Sources are laid out by source set, as in the Kotlin Gradle plugin:
 * `foo/src/commonMain/kotlin` is shared by all platforms,
 * `foo/src/jvmMain/kotlin` and `foo/src/jsMain/kotlin` are platform-specific,
 * and `foo/src/<sourceSet>Test/kotlin` hold the corresponding tests.
 *
 * Requires Kotlin 2.0+ (the K2 compiler).
 */
@mill.api.experimental
trait KotlinMultiplatformModule extends Module { kmp =>

  /** The Kotlin version used by all [[PlatformModule]]s */
  def kotlinVersion: T[String]

  def kmpSourceSetDir(sourceSet: String, language: String = "kotlin"): os.Path =
    moduleDir / "src" / sourceSet / language

  /**
   * A submodule compiling the source sets of one platform, e.g. `jvmMain`
   * and `commonMain`. The compiler depends on the mixed-in traits, e.g.
   * [[KotlinJsModule]] for Kotlin/JS.
   *
   * Name it after its Kotlin target, e.g. `object jvm` reads `jvmMain`,
   * or override [[kmpPlatform]].
   */
  trait PlatformModule extends KotlinModule { platform =>

    def kotlinVersion = Task { kmp.kotlinVersion() }

    /**
     * The platform name, used as a source set prefix, e.g. `jvm` for `jvmMain`.
     */
    def kmpPlatform: String = moduleSegments.value
      .collect { case l: mill.api.Segment.Label => l.value }
      .last

    /** Source set suffix of this compilation, e.g. `Main` or `Test` */
    def kmpCompilation: String = "Main"

    /**
     * Fragment names, from the most specific to `common*`. Not a task, as
     * they define the folders watched by [[sources]].
     */
    def kmpFragments: Seq[String] = Seq(kmpPlatform, "common").map(_ + kmpCompilation)

    override def sources = Task.Sources(
      (kmpFragments.map(kmpSourceSetDir(_)) ++
        // Java sources, only in the source set of a JVM platform, e.g. `src/jvmMain/java`
        Option.when(!this.isInstanceOf[KotlinJsModule])(
          kmpSourceSetDir(kmpFragments.head, "java")
        ))*
    )

    /**
     * The fragment options the Kotlin Gradle plugin passes to the K2 compiler,
     * needed to match `expect` and `actual` declarations across source sets.
     * See
     *  - https://github.com/JetBrains/kotlin/blob/v2.1.20/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/tasks/K2MultiplatformStructure.kt
     *  - https://github.com/JetBrains/kotlin/blob/v2.1.20/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/plugin/mpp/compilationImpl/KotlinCompilationK2MultiplatformConfigurator.kt#L27-L55
     */
    def kmpFragmentOptions: T[Seq[String]] = Task {
      val fragments = kmpFragments
      // Only Kotlin sources belong to fragments; ones outside the fragment
      // folders (e.g. generated sources) belong to the most specific one
      val fragmentSources = allKotlinSourceFiles().map(_.path).map { file =>
        val fragment =
          fragments.find(f => file.startsWith(kmpSourceSetDir(f))).getOrElse(fragments.head)
        s"$fragment:$file"
      }
      val refines = fragments.zip(fragments.drop(1)).map { case (from, to) => s"$from:$to" }

      Seq(s"-Xfragments=${fragments.mkString(",")}") ++
        Option.when(refines.nonEmpty)(s"-Xfragment-refines=${refines.mkString(",")}") ++
        Option.when(fragmentSources.nonEmpty)(
          s"-Xfragment-sources=${fragmentSources.mkString(",")}"
        )
    }

    override protected def mandatoryKotlincOptions = Task {
      super.mandatoryKotlincOptions() ++ kmpFragmentOptions()
    }

    /**
     * Tests of a [[PlatformModule]], e.g. compiling `jvmTest` and `commonTest`.
     */
    trait PlatformTests extends KotlinTests, PlatformModule {
      override def kmpPlatform = platform.kmpPlatform
      override def kmpCompilation = "Test"
    }
  }
}

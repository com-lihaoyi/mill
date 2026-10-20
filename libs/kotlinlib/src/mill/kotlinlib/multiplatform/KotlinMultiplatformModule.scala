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
 */
@mill.api.experimental
trait KotlinMultiplatformModule extends Module { kmp =>

  /** The Kotlin version used by all [[PlatformModule]]s */
  def kotlinVersion: T[String]

  def kmpSourceSetDir(sourceSet: String, language: String = "kotlin"): os.Path =
    moduleDir / "src" / sourceSet / language

  /**
   * A submodule compiling the source sets ("fragments") of one platform,
   * e.g. `jvmMain` and `commonMain`, in one compilation. Every fragment
   * refines the next one in [[kmpFragments]], so `expect` declarations in
   * `commonMain` can be implemented by `actual` declarations in `jvmMain`.
   *
   * The platform used as source set prefix is the name of the module, e.g.
   * `object jvm` reads `src/jvmMain`. Which compiler is used depends on the
   * mixed-in traits, e.g. [[KotlinJsModule]] for Kotlin/JS.
   */
  trait PlatformModule extends KotlinModule { platform =>

    def kotlinVersion = Task { kmp.kotlinVersion() }

    /** The platform name, used as a source set prefix, e.g. `jvm` for `jvmMain` */
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
        // JVM platforms can also contain Java sources, e.g. `src/jvmMain/java`
        Option.when(!this.isInstanceOf[KotlinJsModule])(
          kmpSourceSetDir(kmpFragments.head, "java")
        ))*
    )

    /**
     * The compiler options declaring the fragments of this compilation,
     * how they refine each other, and which sources belong to them.
     */
    def kmpFragmentOptions: T[Seq[String]] = Task {
      val fragments = kmpFragments
      // Sources outside of the fragment folders (e.g. generated sources) belong
      // to the most specific fragment, as in the Kotlin Gradle plugin
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

    /** Tests of a [[PlatformModule]], e.g. compiling `jvmTest` and `commonTest` */
    trait PlatformTests extends KotlinTests, PlatformModule {
      override def kmpPlatform = platform.kmpPlatform
      override def kmpCompilation = "Test"
    }
  }
}

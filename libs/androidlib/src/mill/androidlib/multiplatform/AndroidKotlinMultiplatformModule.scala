package mill.androidlib.multiplatform

import mill.*
import mill.androidlib.AndroidKotlinModule
import mill.kotlinlib.multiplatform.KotlinMultiplatformModule

/**
 * A [[KotlinMultiplatformModule]] that can also target Android, like AGP's
 * `com.android.kotlin.multiplatform.library` plugin.
 */
@mill.api.experimental
trait AndroidKotlinMultiplatformModule extends KotlinMultiplatformModule { outer =>

  /** Compiles `androidMain` and `commonMain`, with an optional `AndroidManifest.xml` */
  trait AndroidPlatformModule extends AndroidKotlinModule, PlatformModule {

    private def androidMainManifest = Task.Source(
      outer.moduleDir / "src" / kmpFragments.head / "AndroidManifest.xml"
    )

    override def androidManifestLocation: T[PathRef] = Task {
      val manifest = androidMainManifest()
      if (os.exists(manifest.path)) manifest
      else {
        val empty = Task.dest / "AndroidManifest.xml"
        os.write(empty, "<manifest/>")
        PathRef(empty)
      }
    }

    /** Compiles `androidHostTest` and `commonTest` */
    trait PlatformHostTests extends AndroidKotlinTestModule, PlatformTests {
      override def kmpCompilation = "HostTest"
    }
  }
}

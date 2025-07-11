package mill.androidlib.manifestmerger

import coursier.Repository
import mill.*
import mill.androidlib.AndroidSdkModule
import mill.api.{Discover, ExternalModule}
import mill.javalib.*
import mill.util.Jvm
import mill.androidlib.Versions

@mill.api.experimental
trait AndroidManifestMerger extends ExternalModule with JvmWorkerModule {

  override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
    super.repositoriesTask() :+ AndroidSdkModule.mavenGoogle
  }

  /**
   * Specifies the version of the Manifest Merger.
   */
  def manifestMergerVersion: T[String] = Task {
    Versions.manifestMergerVersion
  }

  /**
   * Classpath for the manifest merger run.
   */
  def manifestMergerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        mvn"com.android.tools.build:manifest-merger:${manifestMergerVersion()}"
      )
    )
  }

  /**
   * Creates a merged manifest from application and dependencies manifests.
   *
   * See [[https://developer.android.com/build/manage-manifests]] for more details.
   */
  def androidMergedManifest(
      args: Task[Seq[String]]
  ): Task[os.Path] = Task.Anon {

    val outFile = os.temp()
    Jvm.callProcess(
      mainClass = "com.android.manifmerger.Merger",
      mainArgs = args() ++ Seq("--out", outFile.toString()),
      classPath = manifestMergerClasspath().map(_.path)
    )
    outFile
  }

  lazy val millDiscover: Discover = Discover.apply[this.type]
}

object AndroidManifestMerger extends AndroidManifestMerger

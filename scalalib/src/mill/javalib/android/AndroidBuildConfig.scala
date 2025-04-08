package mill.javalib.android

import mill.*
import mill.api.PathRef

trait AndroidBuildConfig extends mill.Module { this: AndroidAppModule =>

  def androidAppVersionCode: Task[Int] = Task { 1 }
  def androidAppVersionName: Task[String] = Task { "1.0" }

  /**
   * Generates a BuildConfig.java in the [[androidApplicationNamespace]] package
   * TODO user configurable version code and version names
   */
  def generatedBuildConfig: T[PathRef] = Task {
    val buildType = if (androidIsDebug()) "debug" else "release"
    val content: String =
      s"""
         |public final class BuildConfig {
         |  public static final boolean DEBUG = ${androidIsDebug()};
         |  public static final String APPLICATION_ID = "${androidApplicationId}";
         |  public static final String BUILD_TYPE = "${buildType}";
         |  public static final int VERSION_CODE = ${androidAppVersionCode()};
         |  public static final String VERSION_NAME = "${androidAppVersionName()}";
         |}
        """.stripMargin

    val destination = Task.dest / "source" / os.SubPath(androidApplicationNamespace.replace(
      ".",
      "/"
    )) / "BuildConfig.java"

    os.write(destination, content, createFolders = true)

    PathRef(destination)
  }

  override def generatedSources: T[Seq[PathRef]] = Task {
    androidLibsRClasses() ++ Seq(generatedBuildConfig())
  }
}

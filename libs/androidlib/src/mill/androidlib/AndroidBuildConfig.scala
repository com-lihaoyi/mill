package mill.androidlib

import mill.*

/**
 * Generates a BuildConfig.java file for Android applications.
 * This is a basic implementation of AGP's build config feature!
 */
@mill.api.experimental
trait AndroidBuildConfig extends AndroidAppModule {

  /**
   * Generates a BuildConfig.java in the [[androidApplicationNamespace]] package
   * TODO add support for custom fields
   */
  def generatedBuildConfig: T[PathRef] = Task {
    val buildType = if (androidIsDebug()) "debug" else "release"
    val content: String =
      s"""
         |public final class BuildConfig {
         |  public static final boolean DEBUG = ${androidIsDebug()};
         |  public static final String APPLICATION_ID = "${androidApplicationId}";
         |  public static final String BUILD_TYPE = "${buildType}";
         |  public static final int VERSION_CODE = ${androidVersionCode()};
         |  public static final String VERSION_NAME = "${androidVersionName()}";
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
    super.generatedSources() ++ Seq(generatedBuildConfig())
  }
}

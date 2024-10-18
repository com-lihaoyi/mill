package mill.kotlinlib.android

import mill.kotlinlib.KotlinModule
import mill.javalib.android.AndroidAppModule
import mill._
import coursier.Type
import upickle.default.ReadWriter

/**
 * Trait for building Android applications using the Mill build tool.
 *
 * This trait defines all the necessary steps for building an Android app from Kotlin sources,
 * integrating both Android-specific tasks and generic Kotlin tasks by extending the
 * [[KotlinModule]] (for standard Kotlin tasks)
 * and [[AndroidAppModule]] (for Android Application Workflow Process).
 *
 * It provides a structured way to handle various steps in the Android app build process,
 * including compiling Kotlin sources, creating DEX files, generating resources, packaging
 * APKs, optimizing, and signing APKs.
 *
 * [[https://developer.android.com/studio Android Studio Documentation]]
 */
@mill.api.experimental
trait AndroidAppKotlinModule extends AndroidAppModule with KotlinModule {

  /**
   * Implicit `ReadWriter` for serializing and deserializing `coursier.Type` values.
   * Converts a `coursier.Type` to a `String` and vice versa.
   */
  implicit val coursierTypeRW: ReadWriter[Type] = upickle.default.readwriter[String].bimap(
    _.value, // Serialize coursier.Type to String
    Type(_) // Deserialize String to coursier.Type
  )

  /**
   * Implicit `ReadWriter` for handling `Set[coursier.Type]`, allowing conversion
   * between a set of `coursier.Type` and a set of `String`.
   */
  implicit val coursierTypeSetRW: ReadWriter[Set[Type]] =
    upickle.default.readwriter[Set[String]].bimap(
      _.map(_.value), // Serialize Set[coursier.Type] to Set[String]
      _.map(Type(_)) // Deserialize Set[String] to Set[coursier.Type]
    )

  /**
   * Adds the "aar" type to the set of artifact types handled by this module.
   *
   * @return A task that yields an updated set of artifact types including "aar".
   */
  override def artifactTypes: T[Set[Type]] =
    T { super.artifactTypes() + coursier.Type("aar") }

  /**
   * Task to extract `classes.jar` files from AAR files in the classpath.
   *
   * @return A sequence of `PathRef` pointing to the extracted JAR files.
   */
  def recompileAARs: T[Seq[PathRef]] = Task {
    val aarFiles = super.compileClasspath().map(_.path).filter(_.ext == "aar").toSeq

    aarFiles.map { aarFile =>
      val extractDir = T.dest / aarFile.baseName
      os.call(Seq("unzip", aarFile.toString, "-d", extractDir.toString))
      PathRef(extractDir / "classes.jar")
    }
  }

  /**
   * Updates the compile classpath by removing `.aar` files and including the
   * extracted `.jar` files from the AARs.
   *
   * @return A task yielding the updated classpath with `.jar` files.
   */
  def updatedCompileClasspath: T[Agg[PathRef]] = Task {
    super.compileClasspath().filter(_.path.ext == "jar") ++ Agg.from(recompileAARs())
  }

  /**
   * Overrides the compile classpath to replace `.aar` files with the extracted
   * `.jar` files.
   *
   * @return The updated classpath with `.jar` files only.
   */
  override def compileClasspath: T[Agg[PathRef]] = updatedCompileClasspath()

}

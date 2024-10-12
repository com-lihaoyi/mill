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
  // Implicit ReadWriter for coursier.Type
  implicit val coursierTypeRW: ReadWriter[Type] = upickle.default.readwriter[String].bimap(
    t => t.value, // Convert coursier.Type to a String
    Type(_) // Convert String back to coursier.Type
  )

  // Implicit ReadWriter for Set[coursier.Type]
  implicit val coursierTypeSetRW: ReadWriter[Set[Type]] =
    upickle.default.readwriter[Set[String]].bimap(
      set => set.map(_.value), // Serialize Set[coursier.Type] to Set[String]
      set => set.map(Type(_)) // Deserialize Set[String] to Set[coursier.Type]
    )

  // Artifact types to add "aar" type to the Set of artifact types
  def artifactTypes: T[Set[Type]] =
    T { super.artifactTypes() + coursier.Type("aar") }

  // Task to recompile AARs into JARs (without circular dependency)
  def recompileAARs: T[Seq[PathRef]] = Task {
    // Get all .aar files from the original compileClasspath
    val aarFiles = super.compileClasspath().map(_.path).filter(_.ext == "aar").toSeq

    // Convert each .aar file into a .jar file
    val newJarFiles = aarFiles.map { aarFile =>
      val extractDir = aarFile / os.up / "ext" // Destination 'ext' folder next to the .aar file
      os.makeDir.all(extractDir) // Ensure the destination directory exists

      // Unzip the .aar file
      os.call(Seq("unzip", aarFile.toString, "-d", extractDir.toString))

      // Create a .jar file from the extracted contents
      val jarFile = aarFile / os.up / s"${aarFile.baseName}.jar" // Target .jar file
      os.call(Seq("jar", "-cf", jarFile.toString, "-C", extractDir.toString, "."))

      // Clean up the extracted folder
      os.remove.all(extractDir)

      // Return the PathRef to the newly created .jar file
      PathRef(jarFile)
    }

    newJarFiles
  }

  // Task to update the compile classpath by including the new .jar files
  def updatedCompileClasspath: T[Agg[PathRef]] = Task {
    // Return the updated classpath with only .jar files
    super.compileClasspath().filter(_.path.ext == "jar") ++ Agg.from(recompileAARs())
  }

  // Override the compile classpath if you want it to include the recompiled AARs by default
  override def compileClasspath: T[Agg[PathRef]] = updatedCompileClasspath()
}

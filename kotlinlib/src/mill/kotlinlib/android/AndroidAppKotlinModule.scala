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

  // artifactTypes to add "aar" type to the Set of artifact types
  def artifactTypes: T[Set[Type]] =
    Task { super.artifactTypes() + coursier.Type("aar") }
}

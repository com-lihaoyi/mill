package mill.bsp.worker.protocol

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.JvmBuildTarget

import java.util.{List => JList}

// based on https://github.com/JetBrains/hirschgarten/blob/da332f97a3ff34a2698b9edec58f66aab55d26e4/protocol/src/main/kotlin/org/jetbrains/bsp/protocol/KotlinBuildTarget.kt

final class KotlinBuildTarget(
    val languageVersion: String,
    val apiVersion: String,
    val kotlincOptions: JList[String],
    val associates: JList[BuildTargetIdentifier],
    val jvmBuildTarget: JvmBuildTarget
)

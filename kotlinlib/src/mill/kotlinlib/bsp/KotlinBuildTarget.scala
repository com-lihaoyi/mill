package mill.kotlinlib.bsp

import mill.scalalib.bsp.JvmBuildTarget

import java.net.URI

final case class KotlinBuildTarget(
    languageVersion: String,
    apiVersion: String,
    kotlincOptions: Seq[String],
    associates: Seq[URI],
    jvmBuildTarget: Option[JvmBuildTarget]
)

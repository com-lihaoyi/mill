package mill.kotlinlib.ksp

import mill.api.PathRef
import upickle.default.ReadWriter

@mill.api.experimental
case class GeneratedKspSources(
    java: PathRef,
    kotlin: PathRef,
    resources: PathRef,
    classes: PathRef
) derives ReadWriter {
  def sources: Seq[PathRef] = Seq(java, kotlin)
}

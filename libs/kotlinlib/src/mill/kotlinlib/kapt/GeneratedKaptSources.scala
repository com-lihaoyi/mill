package mill.kotlinlib.kapt

import mill.api.PathRef
import upickle.ReadWriter

@mill.api.experimental
case class GeneratedKaptSources(
    java: PathRef,
    classes: PathRef,
    stubs: PathRef,
    incrementalData: PathRef
) derives ReadWriter {
  def sources: Seq[PathRef] = Seq(java)
}

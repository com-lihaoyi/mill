package mill.util

import java.io.File

case class ArtifactResolution(
    resolution: coursier.Resolution,
    artifactResult: coursier.Artifacts.Result
) {
  def files: Seq[File] = artifactResult.files
}

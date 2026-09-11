package mill.javalib

import coursier.params.ResolutionParams
import mill.T
import mill.api.Task

@mill.api.daemon.experimental
trait OsDetectorModule extends CoursierModule {

  override def resolutionParams: Task[ResolutionParams] = Task.Anon {
    super.resolutionParams().addProperties(osDetectedMvnProperties().toSeq*)
  }

  /**
   * The `os.detected.*` properties (`os.detected.name`, `os.detected.arch`,
   * `os.detected.classifier`, `os.detected.bitness`) for the platform Mill is currently running
   * on, made available to Coursier so that `${os.detected.*}` placeholders in dependency POMs -
   * normally injected by the `os-maven-plugin`/`osdetector` build extensions - resolve to the
   * current platform instead of being left as literal, unresolvable text.
   *
   * See [[OsDetector]].
   */
  def osDetectedMvnProperties: T[Map[String, String]] = Task {
    OsDetector.detect()
  }
}

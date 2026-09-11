package mill.javalib

import coursier.params.ResolutionParams
import mill.{T, Command}
import mill.api.{DefaultTaskModule, Discover, ExternalModule, Task}

/**
 * Detects the current platform using the same conventions as the `os-maven-plugin`/`osdetector`
 * Maven and Gradle build extensions (`os.detected.name`, `os.detected.arch`,
 * `os.detected.classifier`, `os.detected.bitness`).
 *
 * A number of published artifacts (e.g. `com.google.protobuf:protoc`,
 * `io.grpc:protoc-gen-grpc-java`, `io.netty:netty-tcnative*`) reference a platform-specific
 * classifier via a `${os.detected.classifier}`-style placeholder in their consumers' POMs,
 * expecting one of those extensions to inject the property at build time. Coursier has no
 * equivalent extension mechanism, so without injecting the same properties ourselves, such
 * placeholders are left as literal, unresolvable text and dependency resolution fails.
 */
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
    // TODO using the OsDetectorModule.detect command seems to cause a timeout
    // If the cause is found, this can be switched to reuse the command.
    OsDetector.detect()
  }
}

/**
 * Outputs this Operating System's related properties such as `os.detected.name`, `os.detected.arch`,
 * `os.detected.classifier`, `os.detected.bitness` , of this system. Useful
 * when debugging placeholder issues for POMs.
 */
object OsDetectorModule extends ExternalModule, DefaultTaskModule {
  lazy val millDiscover: Discover = Discover[this.type]

  def defaultTask(): String = "detect"

  /**
   * Outputs the `os.detected.*` properties for the JVM currently running Mill.
   */
  def detect(): Command[Map[String, String]] = Task.Command {
    OsDetector.detect()
  }
}

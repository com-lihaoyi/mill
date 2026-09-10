package mill.javalib

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
object OsDetector {

  /** The `os.detected.*` properties for the JVM currently running Mill. */
  def detect(): Map[String, String] =
    detect(sys.props.getOrElse("os.name", ""), sys.props.getOrElse("os.arch", ""))

  private[javalib] def detect(rawOsName: String, rawOsArch: String): Map[String, String] = {
    val name = normalizeOsName(rawOsName)
    val arch = normalizeArch(rawOsArch)
    val bitness = if (arch.endsWith("_64")) "64" else "32"
    Map(
      "os.detected.name" -> name,
      "os.detected.arch" -> arch,
      "os.detected.bitness" -> bitness,
      "os.detected.classifier" -> s"$name-$arch"
    )
  }

  private def normalizeOsName(raw: String): String = {
    val name = raw.toLowerCase(java.util.Locale.ROOT)
    if (name.contains("windows")) "windows"
    else if (name.contains("mac") || name.contains("darwin")) "osx"
    else if (name.contains("linux")) "linux"
    else if (name.contains("sunos") || name.contains("solaris")) "sunos"
    else if (name.contains("aix")) "aix"
    else if (name.contains("hp-ux") || name.contains("hpux")) "hpux"
    else if (name.contains("os/400") || name.contains("os400")) "os400"
    else if (name.contains("freebsd")) "freebsd"
    else if (name.contains("openbsd")) "openbsd"
    else if (name.contains("netbsd")) "netbsd"
    else if (name.contains("z/os") || name.contains("zos")) "zos"
    else name.replaceAll("[^a-z0-9]+", "")
  }

  private def normalizeArch(raw: String): String =
    raw.toLowerCase(java.util.Locale.ROOT) match {
      case "x86_64" | "amd64" | "ia32e" | "em64t" | "x64" => "x86_64"
      case "x86_32" | "x86" | "i386" | "i486" | "i586" | "i686" | "ia32" | "x32" => "x86_32"
      case "aarch64" | "arm64" => "aarch_64"
      case "arm" | "arm32" => "arm_32"
      case "ppc" | "ppc32" => "ppc_32"
      case "ppcle" | "ppc32le" => "ppcle_32"
      case "ppc64" => "ppc_64"
      case "ppc64le" | "ppcle64" => "ppcle_64"
      case "s390" => "s390_32"
      case "s390x" => "s390_64"
      case "riscv64" => "riscv64"
      case "riscv32" | "riscv" => "riscv32"
      case other => other.replaceAll("[^a-z0-9_]+", "")
    }
}

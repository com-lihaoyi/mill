package mill.pythonlib

import coursier.cache.{ArchiveCache, CachePolicy, FileCache}
import coursier.util.Artifact
import mill.*
import mill.api.PathRef

import java.util.Locale

/**
 * Basic tasks for preparing a Python interpreter in a uv-managed virtual
 * environment with the required dependencies installed.
 */
trait UvModule extends Module {

  private def uvPlatform: T[(String, String)] = Task.Input {
    (
      System.getProperty("os.name", ""),
      System.getProperty("os.arch", "")
    )
  }

  private def uvDistribution: Task[UvModule.Distribution] = Task.Anon {
    val (osName, osArch) = uvPlatform()
    UvModule.distribution(osName, osArch) match {
      case Right(value) => value
      case Left(error) => Task.fail(error)
    }
  }

  /** The uv version Mill downloads and uses. */
  def uvVersion: T[String] = Task { "0.10.4" }

  /** URL of the platform-specific uv archive Mill downloads. */
  def uvDownloadUrl: T[String] = Task {
    s"https://github.com/astral-sh/uv/releases/download/${uvVersion()}/${uvDistribution().archiveName}"
  }

  /** URL of the SHA-256 checksum for [[uvDownloadUrl]]. */
  def uvChecksumUrl: T[String] = Task { s"${uvDownloadUrl()}.sha256" }

  /**
   * The uv executable used by this module. Mill downloads it once into the
   * shared Coursier archive cache, verifies its SHA-256 checksum, and reuses it
   * across builds.
   */
  def uvExe: T[PathRef] = Task {
    UvModule.resolveUv(
      url = uvDownloadUrl(),
      checksumUrl = uvChecksumUrl(),
      distribution = uvDistribution(),
      offline = Task.offline
    ) match {
      case Right(path) => PathRef(path).withRevalidateOnce
      case Left(error) => Task.fail(error)
    }
  }

  /** The direct dependencies of this module. */
  def moduleDeps: Seq[UvModule] = Nil

  /**
   * Python dependencies to install. Each dependency uses the format accepted
   * by uv pip install or a requirements.txt file.
   */
  def pythonDeps: T[Seq[String]] = Task { Seq.empty[String] }

  /** Python dependencies of this module and all upstream modules. */
  def transitivePythonDeps: T[Seq[String]] = Task {
    val upstreamDependencies = Task.traverse(moduleDeps)(_.transitivePythonDeps)().flatten
    pythonDeps() ++ upstreamDependencies
  }

  /** Requirements files to install for this module. */
  def pythonRequirementFiles: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /** Requirements files of this module and all upstream modules. */
  def transitivePythonRequirementFiles: T[Seq[PathRef]] = Task {
    val upstream = Task.traverse(moduleDeps)(_.transitivePythonRequirementFiles)().flatten
    pythonRequirementFiles() ++ upstream
  }

  /**
   * Local, non-Mill Python projects to install as dependencies. Each directory
   * should contain standard Python project metadata such as `pyproject.toml`.
   * Use `Task.Sources` when overriding this so project contents participate in
   * cache invalidation.
   */
  def pythonProjectDeps: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /** Local Python projects of this module and all upstream modules. */
  def transitivePythonProjectDeps: T[Seq[PathRef]] = Task {
    val upstream = Task.traverse(moduleDeps)(_.transitivePythonProjectDeps)().flatten
    pythonProjectDeps() ++ upstream
  }

  /**
   * Python dependencies used only as development tools, such as type checkers,
   * linters, and bundlers.
   */
  def pythonToolDeps: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Python wheels to install directly. Local wheels should be declared here so
   * their signatures participate in Mill cache invalidation.
   */
  def unmanagedWheels: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /** Direct wheels of this module and all upstream modules. */
  def transitiveUnmanagedWheels: T[Seq[PathRef]] = Task {
    val upstream = Task.traverse(moduleDeps)(_.transitiveUnmanagedWheels)().flatten
    unmanagedWheels() ++ upstream
  }

  /**
   * Base URLs of Python package indexes to search, in priority order. The
   * final URL is passed to uv as its lowest-priority default index.
   */
  def indexes: T[Seq[String]] = Task {
    Seq("https://pypi.org/simple")
  }

  /** uv arguments that preserve the priority order declared by [[indexes]]. */
  def uvIndexArgs: T[Seq[String]] = Task {
    indexes().toList match {
      case Nil => Seq("--no-index")
      case head :: Nil => Seq("--default-index", head)
      case multiple =>
        multiple.init.flatMap(index => Seq("--index", index)) ++
          Seq("--default-index", multiple.last)
    }
  }

  /**
   * Arguments passed to uv pip install when preparing the environment.
   *
   * This is an escape hatch. Prefer overriding the higher-level dependency,
   * local-project, wheel, requirements-file, or index tasks.
   */
  def uvInstallArgs: T[UvModule.InstallArgs] = Task {
    UvModule.InstallArgs(
      uvIndexArgs() ++
        transitiveUnmanagedWheels().map(PathRef.toAbsString) ++
        transitivePythonProjectDeps().map(PathRef.toAbsString) ++
        pythonToolDeps() ++
        transitivePythonDeps() ++
        transitivePythonRequirementFiles().flatMap(pr =>
          Seq("-r", PathRef.toAbsString(pr))
        ),
      transitiveUnmanagedWheels() ++ transitivePythonProjectDeps() ++
        transitivePythonRequirementFiles()
    )
  }
}

object UvModule {

  private[pythonlib] case class Distribution(target: String, archiveExtension: String) {
    val directoryName = s"uv-$target"
    val archiveName = s"$directoryName.$archiveExtension"
    val executableName = if (target.endsWith("windows-msvc")) "uv.exe" else "uv"
    val executableRelativeSegments: Seq[String] =
      if (target.endsWith("windows-msvc")) Seq(executableName)
      else Seq(directoryName, executableName)

    def executablePath(directory: os.Path): os.Path =
      executableRelativeSegments.foldLeft(directory)(_ / _)
  }
  private[pythonlib] object Distribution {
    implicit val rw: upickle.ReadWriter[Distribution] = upickle.macroRW
  }

  private[pythonlib] def distribution(
      osName: String,
      osArch: String
  ): Either[String, Distribution] = {
    val normalizedOs = osName.toLowerCase(Locale.ROOT)
    val normalizedArch = osArch.toLowerCase(Locale.ROOT)
    val target =
      if (normalizedOs.contains("mac") || normalizedOs.contains("darwin")) {
        normalizedArch match {
          case "amd64" | "x86_64" | "x64" | "ia32e" | "em64t" =>
            Right("x86_64-apple-darwin")
          case "aarch64" | "arm64" => Right("aarch64-apple-darwin")
          case _ => Left(s"uv does not publish macOS binaries for architecture '$osArch'")
        }
      } else if (normalizedOs.contains("linux")) {
        normalizedArch match {
          // uv's musl builds are fully static, so they also run on glibc distributions.
          case "amd64" | "x86_64" | "x64" | "ia32e" | "em64t" =>
            Right("x86_64-unknown-linux-musl")
          case "aarch64" | "arm64" => Right("aarch64-unknown-linux-musl")
          case "x86" | "x86_32" | "i386" | "i486" | "i586" | "i686" | "ia32" |
              "x32" =>
            Right("i686-unknown-linux-musl")
          case "arm" | "arm32" | "armv6" | "armv6l" =>
            Right("arm-unknown-linux-musleabihf")
          case "armv7" | "armv7l" | "armhf" =>
            Right("armv7-unknown-linux-musleabihf")
          case "riscv64" | "riscv64gc" => Right("riscv64gc-unknown-linux-gnu")
          case "ppc64le" | "ppcle64" | "powerpc64le" =>
            Right("powerpc64le-unknown-linux-gnu")
          case "s390x" => Right("s390x-unknown-linux-gnu")
          case _ => Left(s"uv does not publish Linux binaries for architecture '$osArch'")
        }
      } else if (normalizedOs.contains("windows")) {
        normalizedArch match {
          case "amd64" | "x86_64" | "x64" | "ia32e" | "em64t" =>
            Right("x86_64-pc-windows-msvc")
          case "aarch64" | "arm64" => Right("aarch64-pc-windows-msvc")
          case "x86" | "x86_32" | "i386" | "i486" | "i586" | "i686" | "ia32" |
              "x32" =>
            Right("i686-pc-windows-msvc")
          case _ => Left(s"uv does not publish Windows binaries for architecture '$osArch'")
        }
      } else Left(s"uv does not publish binaries for operating system '$osName'")
    target.map { value =>
      Distribution(value, if (value.endsWith("windows-msvc")) "zip" else "tar.gz")
    }
  }

  private[pythonlib] def resolveUv(
      url: String,
      checksumUrl: String,
      distribution: Distribution,
      offline: Boolean
  ): Either[String, os.Path] = {
    val cache = FileCache()
      .withChecksums(Seq(Some("SHA-256")))
      .withCachePolicies(
        if (offline) Seq(CachePolicy.LocalOnly)
        else coursier.cache.CacheDefaults.cachePolicies
      )
    val artifact = Artifact(url).withChecksumUrls(Map("SHA-256" -> checksumUrl))
    val archiveCache = ArchiveCache().withCache(cache)
    cache.logger.use(archiveCache.get(artifact)).unsafeRun()(using cache.ec) match {
      case Left(error) =>
        val offlineHint = if (offline) " while running in offline mode" else ""
        Left(s"Unable to download uv from $url$offlineHint: ${error.getMessage}")
      case Right(directory) =>
        val executable = distribution.executablePath(os.Path(directory))
        if (os.isFile(executable)) Right(executable)
        else Left(s"Downloaded uv archive did not contain $executable")
    }
  }

  /**
   * String arguments plus a cache-busting signature for arguments that
   * represent files.
   */
  case class InstallArgs(
      args: Seq[String],
      sig: Int
  )
  object InstallArgs {
    implicit val rw: upickle.ReadWriter[InstallArgs] = upickle.macroRW
    def apply(
        args: Seq[String],
        paths: Seq[PathRef]
    ): InstallArgs = {
      val hash = java.security.MessageDigest.getInstance("MD5")
      for (arg <- args) {
        hash.update(arg.getBytes("utf-8"))
      }
      for (path <- paths) {
        hash.update((path.sig >> 24).toByte)
        hash.update((path.sig >> 16).toByte)
        hash.update((path.sig >> 8).toByte)
        hash.update(path.sig.toByte)
      }

      InstallArgs(args.toSeq, java.util.Arrays.hashCode(hash.digest()))
    }
  }
}

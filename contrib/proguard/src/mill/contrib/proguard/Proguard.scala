package mill.contrib.proguard

import mill.{T, Task}
import mill.api.{PathRef}
import mill.constants.Util
import mill.util.Jvm
import mill.scalalib.{DepSyntax, ScalaModule}
import os.{Path, Shellable}

/**
 * Adds proguard capabilities when mixed-in to a module
 *
 * The task name is `proguard`. This runs proguard on the output jar of `assembly`
 * and outputs a shrunk/obfuscated/optimized jar under `out.jar` in the `dest/` folder.
 *
 * Sensible defaults are provided, so no members require overriding.
 */
trait Proguard extends ScalaModule {

  /**
   * The version of proguard to download from Maven.
   * https://mvnrepository.com/artifact/com.guardsquare/proguard-base
   */
  def proguardVersion: T[String]

  /** Run the "shrink" step in the proguard pipeline. Defaults to true. */
  def shrink: T[Boolean] = Task { true }

  /** Run the "optimize" step in the proguard pipeline. Defaults to true. */
  def optimize: T[Boolean] = Task { true }

  /** Run the "obfuscate" step in the proguard pipeline. Defaults to true. */
  def obfuscate: T[Boolean] = Task { true }

  /**
   * Run the "optimize" step in the proguard pipeline. Defaults to true.
   *
   * Note that this is required for Java 7 and above.
   */
  def preverify: T[Boolean] = Task { true }

  /**
   * The path to JAVA_HOME.
   *
   * This is used for both the `java` command binary,
   * and the standard library jars.
   * Defaults to the `java.home` system property.
   * Keep in sync with [[java9RtJar]]-
   */
  def finalJavaHome: T[PathRef] =
    javaHome().getOrElse(PathRef(Path(sys.props("java.home")), quick = true))

  /** Specifies the input jar to proguard. Defaults to the output of the `assembly` task. */
  def inJar: T[PathRef] = Task { assembly() }

  /**
   * This needs to return the Java RT JAR if on Java 9 or above.
   * Keep in sync with [[javaHome]].
   */
  def java9RtJar: T[Seq[PathRef]] = Task {
    if (Util.isJava9OrAbove) Seq(PathRef(os.home / Export.rtJarName))
    else Seq()
  }

  /**
   * The library jars proguard requires
   * Defaults the jars under `javaHome`.
   */
  def libraryJars: T[Seq[PathRef]] = Task {
    val javaJars =
      os.list(
        finalJavaHome().path / "lib",
        sort = false
      ).filter(_.ext == "jar").toSeq.map(PathRef(_))
    javaJars
  }

  /**
   * Run the proguard task.
   *
   *  The full command will be printed when run.
   *  The stdout and stderr of the command are written to the `dest/` folder.
   *  The output jar is written to `dest/our.jar`.
   */
  def proguard: T[PathRef] = Task {
    val outJar = Task.dest / "out.jar"

    val args = Seq[Shellable](
      steps(),
      "-injars",
      inJar().path,
      "-outjars",
      outJar,
      "-libraryjars",
      (
        libraryJars().map(_.path) ++
          Seq("<java.home>/jmods/java.base.jmod(!**.jar;!module-info.class)")
      ).mkString(java.io.File.pathSeparator),
      entryPoint(),
      additionalOptions()
    ).flatMap(_.value)

    Task.log.debug(s"Running: ${args.mkString(" ")}")
//    Task.log.debug(s"stdout: ${Task.dest / "stdout.txt"}")
//    Task.log.debug(s"stderr: ${Task.dest / "stderr.txt"}")

//    val result = os.proc(cmd).call(stdout = Task.dest / "stdout.txt", stderr = Task.dest / "stderr.txt")
//    Task.log.debug(s"result: ${result}")

    Jvm.callProcess(
      mainClass = "proguard.ProGuard",
      classPath = proguardClasspath().map(_.path).toVector,
      mainArgs = args,
      cwd = Task.dest
    )

    // the call above already throws an exception on a non-zero exit code,
    // so if we reached this point we've succeeded!
    PathRef(outJar)
  }

  /**
   * The location of the proguard jar files.
   */
  def proguardClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      mvn"com.guardsquare:proguard-base:${proguardVersion()}"
    ))
  }

  private def steps: T[Seq[String]] = Task {
    (if (optimize()) Seq() else Seq("-dontoptimize")) ++
      (if (obfuscate()) Seq() else Seq("-dontobfuscate")) ++
      (if (shrink()) Seq() else Seq("-dontshrink")) ++
      (if (preverify()) Seq() else Seq("-dontpreverify"))
  }

  /**
   * The default `entrypoint` to proguard.
   *
   * Defaults to the `main` method of `finalMainClass`.
   * Can be overridden to specify a different entrypoint,
   * or additional entrypoints can be specified with `additionalOptions`.
   */
  def entryPoint: T[String] = Task {
    s"""|-keep public class ${finalMainClass()} {
        |    public static void main(java.lang.String[]);
        |}
        |""".stripMargin
  }

  /**
   * Specify any additional options to proguard.
   *
   * These are fed as-is to the proguard command.
   */
  def additionalOptions: T[Seq[String]] = Task {
    Task.log.warn(
      "Proguard is set to not warn about message: can't find referenced method 'void invoke()' in library class java.lang.invoke.MethodHandle"
    )
    Task.log.warn(
      """Proguard is set to not warn about message: "scala.quoted.Type: can't find referenced class scala.AnyKind""""
    )
    Seq[String](
      "-dontwarn java.lang.invoke.MethodHandle",
      "-dontwarn scala.AnyKind"
    )
  }
}

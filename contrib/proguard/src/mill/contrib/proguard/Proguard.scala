package mill.contrib.proguard

import mill.java9rtexport.Export
import mill.{task, T}
import mill.Agg
import mill.api.{Loose, PathRef}
import mill.util.Jvm
import mill.scalalib.{DepSyntax, ScalaModule}
import os.{Path, Shellable}

/**
 * Adds proguard capabilities when mixed-in to a module
 *
 * The target name is `proguard`. This runs proguard on the output jar of `asssembly`
 * and outputs a shrunk/obfuscated/optimized jar under `out.jar` in the `dest/` folder.
 *
 * Sensible defaults are provided, so no members require overriding..
 */
trait Proguard extends ScalaModule {

  /**
   * The version of proguard to download from Maven.
   * https://mvnrepository.com/artifact/com.guardsquare/proguard-base
   */
  def proguardVersion: T[String] = task {
    task.log.error(
      "Using default proguard version is deprecated. Please override target proguardVersion to specify the version."
    )
    "7.2.2"
  }

  /** Run the "shrink" step in the proguard pipeline. Defaults to true. */
  def shrink: T[Boolean] = task { true }

  /** Run the "optimize" step in the proguard pipeline. Defaults to true. */
  def optimize: T[Boolean] = task { true }

  /** Run the "obfuscate" step in the proguard pipeline. Defaults to true. */
  def obfuscate: T[Boolean] = task { true }

  /**
   * Run the "optimize" step in the proguard pipeline. Defaults to true.
   *
   * Note that this is required for Java 7 and above.
   */
  def preverify: T[Boolean] = task { true }

  /**
   * The path to JAVA_HOME.
   *
   * This is used for both the `java` command binary,
   * as well as the standard library jars.
   * Defaults to the `java.home` system property.
   * Keep in sync with [[java9RtJar]]-
   */
  def javaHome: T[PathRef] = task.input {
    PathRef(Path(sys.props("java.home")))
  }

  /** Specifies the input jar to proguard. Defaults to the output of the `assembly` task. */
  def inJar: T[PathRef] = task { assembly() }

  /**
   * This needs to return the Java RT JAR if on Java 9 or above.
   * Keep in sync with [[javaHome]].
   */
  def java9RtJar: T[Seq[PathRef]] = task {
    if (mill.main.client.Util.isJava9OrAbove) {
      val rt = task.dest / Export.rtJarName
      if (!os.exists(rt)) {
        task.log.outputStream.println(
          s"Preparing Java runtime JAR; this may take a minute or two ..."
        )
        Export.rtTo(rt.toIO, false)
      }
      Seq(PathRef(rt))
    } else {
      Seq()
    }
  }

  /**
   * The library jars proguard requires
   * Defaults the jars under `javaHome`.
   */
  def libraryJars: T[Seq[PathRef]] = task {
    val javaJars =
      os.list(javaHome().path / "lib", sort = false).filter(_.ext == "jar").toSeq.map(PathRef(_))
    javaJars ++ java9RtJar()
  }

  /**
   * Run the proguard task.
   *
   *  The full command will be printed when run.
   *  The stdout and stderr of the command are written to the `dest/` folder.
   *  The output jar is written to `dest/our.jar`.
   */
  def proguard: T[PathRef] = task {
    val outJar = task.dest / "out.jar"

    val args = Seq[Shellable](
      steps(),
      "-injars",
      inJar().path,
      "-outjars",
      outJar,
      "-libraryjars",
      libraryJars().map(_.path).mkString(java.io.File.pathSeparator),
      entryPoint(),
      additionalOptions()
    ).flatMap(_.value)

    task.log.debug(s"Running: ${args.mkString(" ")}")
//    task.log.debug(s"stdout: ${task.dest / "stdout.txt"}")
//    task.log.debug(s"stderr: ${task.dest / "stderr.txt"}")

//    val result = os.proc(cmd).call(stdout = task.dest / "stdout.txt", stderr = task.dest / "stderr.txt")
//    task.log.debug(s"result: ${result}")

    Jvm.runSubprocess(
      mainClass = "proguard.ProGuard",
      classPath = proguardClasspath().map(_.path),
      mainArgs = args,
      workingDir = task.dest
    )

    // the call above already throws an exception on a non-zero exit code,
    // so if we reached this point we've succeeded!
    PathRef(outJar)
  }

  /**
   * The location of the proguard jar files.
   * These are downloaded from JCenter and fed to `java -cp`
   */
  def proguardClasspath: T[Loose.Agg[PathRef]] = task {
    defaultResolver().resolveDeps(
      Agg(ivy"com.guardsquare:proguard-base:${proguardVersion()}")
    )
  }

  private def steps: T[Seq[String]] = task {
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
  def entryPoint: T[String] = task {
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
  def additionalOptions: T[Seq[String]] = task {
    task.log.error(
      "Proguard is set to not warn about message: can't find referenced method 'void invoke()' in library class java.lang.invoke.MethodHandle"
    )
    Seq[String]("-dontwarn java.lang.invoke.MethodHandle")
  }
}

package mill.contrib.proguard

import coursier.Repositories
import mill.T
import mill.Agg
import mill.api.{Logger, Loose, PathRef, Result}
import mill.define.{Sources, Target}
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{Dep, DepSyntax, Lib, ScalaModule}
import os.proc
import os.Path
import os.PathChunk

/** Adds proguard capabilities when mixed-in to a module
 *
 * The target name is `proguard`. This runs proguard on the output jar of `asssembly`
 * and outputs a shrunk/obfuscated/optimized jar under `out.jar` in the `dest/` folder.
 *
 * Sensible defaults are provided, so no members require overriding..
 *
 */
trait Proguard extends ScalaModule {

  /** The version of proguard to download from Maven.
   *
   * Note that currently this is the only available version. */
  def proguardVersion: T[String] = T { "7.0.0" }

  /** Run the "shrink" step in the proguard pipeline. Defaults to true. */
  def shrink: T[Boolean] = T { true }

  /** Run the "optimize" step in the proguard pipeline. Defaults to true. */
  def optimize: T[Boolean] = T { true }

  /** Run the "obfuscate" step in the proguard pipeline. Defaults to true. */
  def obfuscate: T[Boolean] = T { true }

  /** Run the "optimize" step in the proguard pipeline. Defaults to true.
   *
   * Note that this is required for Java 7 and above.
   */
  def preverify: T[Boolean] = T { true }

  /** The path to JAVA_HOME.
   *
   * This is used for both the `java` command binary,
   * as well as the standard library jars.
   * Defaults to the `java.home` system property. */
  def javaHome: T[PathRef] = T.input {
    PathRef(Path(System.getProperty("java.home")))
  }

  /** Specifies the input jar to proguard. Defaults to the output of the `assembly` task. */
  def inJar: T[PathRef] = T { assembly() }

  /** The library jars proguard requires
   * Defaults the jars under `javaHome`. */
  def libraryJars: T[Seq[PathRef]] = T {
    val javaJars = os.list(javaHome().path / "lib", sort = false).filter(_.ext == "jar")
    javaJars.toSeq.map(PathRef(_))
  }

  /** Run the proguard task.
   *
   *  The full command will be printed when run.
   *  The stdout and stderr of the command are written to the `dest/` folder.
   *  The output jar is written to `dest/our.jar`. */
  def proguard: T[PathRef] = T {
    val outJar = T.dest / "out.jar"
    val java = javaHome().path / "bin" / "java"

    val cmd = os.proc(
      java,
      "-cp",
      proguardClasspath().map(_.path).mkString(":"),
      "proguard.ProGuard",
      steps(),
      "-injars",
      inJar().path,
      "-outjars",
      outJar,
      "-libraryjars",
      libraryJars().map(_.path).mkString(":"),
      entryPoint(),
      additionalOptions()
    )
    System.out.println(cmd.command.flatMap(_.value).mkString(" "))
    cmd.call(stdout = T.dest / "stdout.txt", stderr = T.dest / "stderr.txt")

    // the call above already throws an exception on a non-zero exit code,
    // so if we reached this point we've succeeded!
    PathRef(outJar)
  }

  /** The location of the proguard jar files.
   * These are downloaded from JCenter and fed to `java -cp`
   */
  def proguardClasspath: T[Loose.Agg[PathRef]] = T {
    resolveDependencies(
      Seq(Repositories.jcenter),
      Lib.depToDependencyJava(_),
      Seq(ivy"com.guardsquare:proguard-base:${proguardVersion()}"))
  }

  private def steps: T[Seq[String]] = T {
    (if (optimize()) Seq() else Seq("-dontoptimize")) ++
      (if (obfuscate()) Seq() else Seq("-dontobfuscate")) ++
      (if (shrink()) Seq() else Seq("-dontshrink")) ++
      (if (preverify()) Seq() else Seq("-dontpreverify"))
  }

  /** The default `entrypoint` to proguard.
   *
   * Defaults to the `main` method of `finalMainClass`.
   * Can be overriden to specify a different entrypoint,
   * or additional entrypoints can be specified with `additionalOptions`. */
  def entryPoint: T[String] = T {
    s"""|-keep public class ${finalMainClass()} {
        |    public static void main(java.lang.String[]);
        |}
        |""".stripMargin
  }

  /** Specify any additional options to proguard.
   *
   * These are fed as-is to the proguard command.
   * */
  def additionalOptions: T[Seq[String]] = T { Seq[String]() }
}

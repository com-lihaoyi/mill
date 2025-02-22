package mill.scalalib.cosmo

import mill.{T, Task}
import mill.api.{PathRef, Result}
import mill.scalalib._

import java.nio.file.attribute.PosixFilePermission

/**
 * Provides a [[CosmoModule.cosmoAssembly task]] to build a cross-platfrom executable assembly jar
 * by prepending an [[https://justine.lol/ape.html Actually Portable Executable (APE)]]
 * launcher binary compiled with the [[https://justine.lol/cosmopolitan/index.html Cosmopolitan Libc]]
 */
trait CosmoModule extends mill.Module with AssemblyModule {
  def finalMainClass: T[String]

  def forkArgs(argv0: String): Task[Seq[String]] = Task.Anon { Seq[String]() }

  def cosmoccVersion: T[String] = Task { "" }

  def cosmocc: T[PathRef] = Task(persistent = true) {
    val version = if (cosmoccVersion().isEmpty) "" else s"-${cosmoccVersion()}"
    val versionedCosmocc = s"cosmocc${version}"
    Task.dest / s"${versionedCosmocc}.zip"
    val dest = Task.dest / versionedCosmocc / "bin" / "cosmocc"

    if (!os.exists(dest)) {
      os.remove.all(Task.dest / versionedCosmocc)
      os.unzip.stream(
        requests.get.stream(s"https://cosmo.zip/pub/cosmocc/${versionedCosmocc}.zip"),
        Task.dest / versionedCosmocc
      )

      if (!scala.util.Properties.isWin) {
        (
          os.walk.stream(Task.dest / versionedCosmocc / "bin") ++
            os.walk.stream(Task.dest / versionedCosmocc / "libexec")
        ).filter(p => os.isFile(p) && p.ext != "c" && p.last != "cosmoranlib")
          .foreach { p =>
            os.perms.set(
              p,
              os.perms(p)
                + PosixFilePermission.GROUP_EXECUTE
                + PosixFilePermission.OWNER_EXECUTE
                + PosixFilePermission.OTHERS_EXECUTE
            )
          }
      }
    }

    PathRef(dest)
  }

  def cosmoLauncherScript: T[String] = Task {
    val start = 1
    val size0 = start + forkArgs().length
    val addForkArgs = forkArgs()
      .zip(start until size0)
      .map { (arg, i) =>
        s"""all_argv[${i}] = (char*)malloc((strlen("${arg}") + 1) * sizeof(char));
           |strcpy(all_argv[${i}], "${arg}");
        """.stripMargin
      }.mkString("\n")

    val args = forkArgs("%s")()
    val size = size0 + args.length
    val addForkArgsArgv0 = args
      .zip(size0 until size)
      .map { (arg, i) =>
        s"""char* s${i};
           |asprintf(&s${i}, "${arg}", argv[0]);
           |all_argv[${i}] = (char*)malloc((strlen(s${i}) + 1) * sizeof(char));
           |strcpy(all_argv[${i}], s${i});
        """.stripMargin
      }.mkString("\n");

    val preArgvSize = size + 3;

    val template =
      scala.io.Source.fromResource("mill/scalalib/cosmo/launcher.c").getLines.mkString("\n")

    template.format(preArgvSize, size, finalMainClass(), addForkArgs, addForkArgsArgv0)
  }

  def cosmoCompiledLauncherScript: T[PathRef] = Task {
    os.write(Task.dest / "launcher.c", cosmoLauncherScript())
    os.call((
      cosmocc().path,
      "-mtiny",
      "-O3",
      "-o",
      Task.dest / "launcher",
      Task.dest / "launcher.c"
    ))

    PathRef(Task.dest / "launcher")
  }

  override def prepend: T[Option[Array[Byte]]] = Task {
    Some(os.read.bytes(cosmoCompiledLauncherScript().path))
  }

  override def destJarName: T[String] = Task { "out.jar.exe" }

  override def assembly: T[PathRef] = Task {
    val created = createAssembly()

    // See https://github.com/com-lihaoyi/mill/pull/2655#issuecomment-1672468284
    val problematicEntryCount = 65535

    if (created.entries > problematicEntryCount) {
      Result.Failure(
        s"""The created assembly jar contains more than ${problematicEntryCount} ZIP entries.
           |Prepended JARs of that size are known to not work correctly.
         """.stripMargin
      )
    } else {
      Result.Success(created.pathRef)
    }
  }
}

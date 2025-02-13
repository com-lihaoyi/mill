package mill.scalalib.cosmopolitan

import mill.api.{PathRef, Result}
import mill.define.{Target => T, _}
import mill.scalalib._

trait CosmopolitanModule extends mill.Module with AssemblyModule {
  def forkArgs(argv0: String): Task[Seq[String]] = Task.Anon { Seq[String]() }

  def cosmoccVersion: T[String] = Task { "" }

  def cosmocc: T[PathRef] = Task(persistent = true) {
    val version = if (cosmoccVersion().isEmpty) "" else s"-${cosmoccVersion()}"
    val versionedCosmocc = s"cosmocc${version}"
    val zip = Task.dest / s"${versionedCosmocc}.zip"
    val dest = Task.dest / versionedCosmocc / "bin" / "cosmocc"

    if (!os.exists(dest)) {
      os.write.over(
        zip,
        requests.get.stream(s"https://cosmo.zip/pub/cosmocc/${versionedCosmocc}.zip")
      )
      os.remove.all(Task.dest / versionedCosmocc)
      os.call(("unzip", zip, "-d", Task.dest / versionedCosmocc))
    }

    PathRef(dest)
  }

  def apeLauncherScript: T[Option[String]] = Task {
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

    finalMainClassOpt().toOption.map { cls =>
      s"""#include <stdlib.h>
         |#include <stdio.h>
         |#include <errno.h>
         |
         |int main(int argc, char* argv[]) {
         |  size_t preargv_size = ${preArgvSize};
         |  size_t total = preargv_size + argc;
         |  char *all_argv[total];
         |  memset(all_argv, 0, sizeof(all_argv));
         |
         |  all_argv[0] = (char*)malloc((strlen(argv[0]) + 1) * sizeof(char));
         |  strcpy(all_argv[0], argv[0]);
         |  ${addForkArgs}
         |  ${addForkArgsArgv0}
         |  all_argv[${size}] = (char*)malloc((strlen("-cp") + 1) * sizeof(char));
         |  strcpy(all_argv[${size}], "-cp");
         |  all_argv[${size + 1}] = (char*)malloc((strlen(argv[0]) + 1) * sizeof(char));
         |  strcpy(all_argv[${size + 1}], argv[0]);
         |  all_argv[${size + 2}] = (char*)malloc((strlen("${cls}") + 1) * sizeof(char));
         |  strcpy(all_argv[${size + 2}], "${cls}");
         |
         |  int i = preargv_size;
         |  for (int count = 1; count < argc; count++) {
         |    all_argv[i] = (char*)malloc((strlen(argv[count]) + 1) * sizeof(char));
         |    strcpy(all_argv[i], argv[count]);
         |    i++;
         |  }
         |
         |  all_argv[total - 1] = NULL;
         |
         |  execvp("java", all_argv);
         |  if (errno == ENOENT) {
         |    execvp("java.exe", all_argv);
         |  }
         |
         |  perror("java");
         |  exit(EXIT_FAILURE);
         |}
      """.stripMargin
    }
  }

  def apeCompiledLauncherScript: T[Option[PathRef]] = Task {
    apeLauncherScript().map { sc =>
      os.write(Task.dest / "launcher.c", sc)
      os.call((cosmocc().path, "-mtiny", "-o", Task.dest / "launcher", Task.dest / "launcher.c"))

      PathRef(Task.dest / "launcher")
    }
  }

  def apeAssembly: T[PathRef] = Task {
    val prepend: Option[os.Source] =
      apeCompiledLauncherScript().map(p => os.read.inputStream(p.path))
    val upstream = upstreamAssembly()

    val created = Assembly.create0(
      destJar = Task.dest / "out.jar.exe",
      Seq.from(localClasspath().map(_.path)),
      manifest(),
      prepend,
      Some(upstream.pathRef.path),
      assemblyRules
    )
    // See https://github.com/com-lihaoyi/mill/pull/2655#issuecomment-1672468284
    val problematicEntryCount = 65535
    if (
      prepend.isDefined &&
      (upstream.addedEntries + created.addedEntries) > problematicEntryCount
    ) {
      Result.Failure(
        s"""The created assembly jar contains more than ${problematicEntryCount} ZIP entries.
           |JARs of that size are known to not work correctly with a prepended shell script.
           |Either reduce the entries count of the assembly or disable the prepended shell script with:
           |
           |  def prependShellScript = ""
           |""".stripMargin
      )
    } else {
      Result.Success(created.pathRef)
    }
  }
}

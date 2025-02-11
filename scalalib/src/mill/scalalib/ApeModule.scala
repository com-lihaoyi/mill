package mill.scalalib

import mill.api.{PathRef, Result}
import mill.define.{Target => T, _}

trait ApeModule extends mill.Module with AssemblyModule {
  def cosmoccVersion: T[String] = Task { "" }

  def cosmoccZip: T[PathRef] = Task(persistent = true) {
    val version = if (cosmoccVersion().isEmpty) "" else s"-${cosmoccVersion()}"
    os.write(
      Task.dest / "cosmocc.zip",
      requests.get(s"https://cosmo.zip/pub/cosmocc/cosmocc${version}.zip")
    )
    PathRef(Task.dest / "cosmocc.zip")
  }

  def cosmocc: T[PathRef] = Task {
    os.call(("unzip", cosmoccZip().path, "-d", Task.dest / "cosmocc"))
    PathRef(Task.dest / "cosmocc" / "bin" / "cosmocc")
  }

  def launcherScript: T[Option[String]] = Task {
    finalMainClassOpt().toOption.map { cls =>
      s"""#include <stdlib.h>
         |#include <stdio.h>
         |#include <errno.h>
         |
         |int main(int argc, char* argv[]) {
         |  size_t preargv_size = 4;
         |  size_t total = preargv_size + argc;
         |  char *all_argv[total];
         |  memset(all_argv, 0, sizeof(all_argv));
         |
         |  all_argv[0] = (char*)malloc((strlen(argv[0]) + 1) * sizeof(char));
         |  strcpy(all_argv[0], argv[0]);
         |  all_argv[1] = (char*)malloc((strlen("-cp") + 1) * sizeof(char));
         |  strcpy(all_argv[1], "-cp");
         |  all_argv[2] = (char*)malloc((strlen(argv[0]) + 1) * sizeof(char));
         |  strcpy(all_argv[2], argv[0]);
         |  all_argv[3] = (char*)malloc((strlen("${cls}") + 1) * sizeof(char));
         |  strcpy(all_argv[3], "${cls}");
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

  def compiledLauncherScript: T[Option[PathRef]] = Task {
    launcherScript().map { launcherScript =>
      os.write(Task.dest / "launcher.c", launcherScript)
      os.call((cosmocc().path, "-mtiny", "-o", Task.dest / "launcher", Task.dest / "launcher.c"))

      PathRef(Task.dest / "launcher")
    }
  }

  def apeAssembly: T[PathRef] = Task {
    val prepend: Option[os.Source] = compiledLauncherScript().map(p => os.read.inputStream(p.path))
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

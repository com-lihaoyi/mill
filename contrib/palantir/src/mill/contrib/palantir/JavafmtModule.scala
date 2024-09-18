package mill
package contrib.palantir

import mill.util.Jvm

trait JavafmtModule extends PalantirModule {

  def javafmt(
      @mainargs.arg check: Boolean = false,
      leftover: mainargs.Leftover[String]
  ): Command[Unit] = T.command {

    val _sources =
      if (leftover.value.isEmpty) sources().iterator.map(_.path)
      else leftover.value.iterator.map(rel => millSourcePath / os.RelPath(rel))

    if (check) {
      T.log.info("checking format in java sources ...")
    } else {
      T.log.info("formatting java sources ...")
    }

    val exitCode = Jvm.callSubprocess(
      mainClass = PalantirModule.mainClass,
      classPath = palantirClasspath().map(_.path),
      jvmArgs = PalantirModule.jvmArgs,
      mainArgs = PalantirModule.mainArgs(_sources, palantirOptions(), check),
      workingDir = T.dest,
      check = false
    ).exitCode

    if (check && exitCode == 1) {
      throw new RuntimeException("format error(s) found in source(s)")
    }
  }

  def javafmtAll: T[Unit] = T {

    T.log.info("formatting java sources ...")

    Jvm.runSubprocess(
      mainClass = PalantirModule.mainClass,
      classPath = palantirClasspath().map(_.path),
      jvmArgs = PalantirModule.jvmArgs,
      mainArgs = PalantirModule.mainArgs(sources().map(_.path), palantirOptions()),
      workingDir = T.dest
    )
  }
}

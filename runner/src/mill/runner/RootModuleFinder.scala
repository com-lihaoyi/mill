package mill.runner

import mill.api.Logger

object RootModuleFinder {
  def findRootModules(projectRoot: os.Path, logger: Logger): Seq[mill.main.RootModule] = {
    val evaluated = new mill.runner.MillBuildBootstrap(
      projectRoot = projectRoot,
      home = os.home,
      keepGoing = false,
      imports = Nil,
      env = Map.empty,
      threadCount = None,
      targetsAndParams = Seq("resolve", "--quiet", "_"),
      prevRunnerState = mill.runner.RunnerState.empty,
      logger = logger
    ).evaluate()

    val rootModules0 = evaluated.result.frames
      .flatMap(_.classLoaderOpt)
      .zipWithIndex
      .map { case (c, i) =>
        MillBuildBootstrap
          .getRootModule(c, i, projectRoot)
          .fold(sys.error(_), identity(_))
      }

    val bootstrapModule = evaluated.result.bootstrapModuleOpt.map(m =>
      MillBuildBootstrap
        .getChildRootModule(
          m,
          evaluated.result.frames.length,
          projectRoot
        )
        .fold(sys.error(_), identity(_))
    )

    rootModules0 ++ bootstrapModule
  }

}

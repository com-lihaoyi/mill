package mill.javalib.testrunner

import mill.api.daemon.internal.internal

@internal object GetTestTasks {

  def apply(args0: mill.javalib.api.internal.ZincOp.GetTestTasks): Seq[String] = {
    import args0.*
    val globFilter = TestRunnerUtils.globFilter(selectors)
    val normalizedRunCp = runCp.map(PathNormalization.normalizePath)
    val normalizedTestCp = testCp.map(PathNormalization.normalizePath)
    val discoveryCp = (normalizedRunCp ++ normalizedTestCp).distinct
    mill.util.Jvm.withClassLoader(
      classPath = discoveryCp,
      sharedPrefixes = Seq("sbt.testing.")
    ) { classLoader =>
      TestRunnerUtils
        .getTestTasks0(
          Framework.framework(framework),
          Seq.from(normalizedTestCp),
          args,
          cls => globFilter(cls.getName),
          classLoader
        )
        .toSeq
    }
  }
}

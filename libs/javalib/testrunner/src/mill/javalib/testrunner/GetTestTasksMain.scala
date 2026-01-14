package mill.javalib.testrunner

import mill.api.daemon.internal.internal

@internal object GetTestTasksMain {

  def apply(args0: mill.javalib.api.internal.ZincOp.GetTestTasks): Seq[String] = {
    import args0.*
    val globFilter = TestRunnerUtils.globFilter(selectors)
    mill.util.Jvm.withClassLoader(
      classPath = runCp,
      sharedPrefixes = Seq("sbt.testing.")
    ) { classLoader =>
      TestRunnerUtils
        .getTestTasks0(
          Framework.framework(framework),
          Seq.from(testCp),
          args,
          cls => globFilter(cls.getName),
          classLoader
        )
        .toSeq
    }
  }
}

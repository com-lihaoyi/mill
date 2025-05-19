package mill.testrunner

import mill.define.TaskCtx
import mill.api.internal.{TestReporter, internal}
import mill.util.Jvm

@internal object TestRunner {

  def runTestFramework(
      frameworkInstances: ClassLoader => sbt.testing.Framework,
      entireClasspath: Seq[os.Path],
      testClassfilePath: Seq[os.Path],
      args: Seq[String],
      testReporter: TestReporter,
      classFilter: Class[?] => Boolean = _ => true
  )(implicit ctx: TaskCtx.Log): (String, Seq[mill.testrunner.TestResult]) = {
    val hasTwoClassLoaders =
      classOf[sbt.testing.Framework].getClassLoader != getClass.getClassLoader
    Jvm.withClassLoader(
      classPath = entireClasspath.toVector,
      parent = if (hasTwoClassLoaders) classOf[sbt.testing.Framework].getClassLoader else null,
      sharedPrefixes = if (hasTwoClassLoaders) Nil else Seq("sbt.testing.")
    ) { classLoader =>
      TestRunnerUtils.runTestFramework0(
        frameworkInstances,
        testClassfilePath,
        args,
        classFilter,
        classLoader,
        testReporter
      )
    }
  }
}

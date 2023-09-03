package mill.testrunner

import mill.api.internal

@internal object Framework {
  def framework(frameworkName: String)(
      cl: ClassLoader
  ): sbt.testing.Framework = {
    cl.loadClass(frameworkName)
      .getDeclaredConstructor().newInstance()
      .asInstanceOf[sbt.testing.Framework]
  }
}

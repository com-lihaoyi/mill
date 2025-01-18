package mill.javalib.android

import mill.T
import mill.scalalib.TestModule

@mill.api.experimental
trait AndroidTestModule extends TestModule {}

@mill.api.experimental
object AndroidTestModule {

  trait AndroidJUnit extends AndroidTestModule {

    /** TODO this probably does not sit well with the idea of a test framework */
    override def testFramework: T[String] = "androidx.test.runner.AndroidJUnitRunner"
  }
}

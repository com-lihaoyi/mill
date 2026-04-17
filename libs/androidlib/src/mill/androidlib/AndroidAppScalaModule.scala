package mill.androidlib

import mill.api.Task.Simple as T
import mill.api.{PathRef, Task}

trait AndroidAppScalaModule extends AndroidAppModule with AndroidScalaModule { outer =>

  trait AndroidAppScalaTests extends AndroidScalaTestModule {}

  trait AndroidAppScalaInstrumentedTests extends AndroidAppInstrumentedTests,
        AndroidScalaTestModule {

    private def scalaSources = Task.Sources("src/androidTest/scala")

    override def sources: T[Seq[PathRef]] =
      super[AndroidAppInstrumentedTests].sources() ++ scalaSources()

  }

}

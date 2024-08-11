//// SNIPPET:BUILD
import mill._, javalib._

object foo extends JavaModule {
  object test extends JavaTests with TestModule.Junit4 {
    def otherFiles = T.source(millSourcePath / "other-files")

    def forkEnv = super.forkEnv() ++ Map(
      "OTHER_FILES_FOLDER" -> otherFiles().path.toString
    )
  }
}


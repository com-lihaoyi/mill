package mill.androidlib

import mill.api.daemon.internal.internal

private[androidlib] object ComposeRenderer {

  /*
   * Model as in
   * [[https://android.googlesource.com/platform/tools/base/+/61923408e5f7dc20f0840844597f9dde17453a0f/preview/screenshot/screenshot-test-gradle-plugin/src/main/java/com/android/compose/screenshot/PreviewFinder.kt#58]]
   */
  @internal case class Args(
      fontsPath: String,
      layoutlibPath: String,
      outputFolder: String,
      metaDataFolder: String,
      classPath: Seq[String],
      projectClassPath: Seq[String],
      namespace: String,
      screenshots: Seq[Screenshot],
      resourceApkPath: String,
      resultsFilePath: String
  )

  object Args {
    implicit def resultRW: upickle.default.ReadWriter[Args] = upickle.default.macroRW
  }

  case class Screenshot(
      methodFQN: String,
      methodParams: Seq[String],
      previewParams: PreviewParams,
      previewId: String
  )

  object Screenshot {
    implicit def resultRW: upickle.default.ReadWriter[Screenshot] = upickle.default.macroRW
  }

  case class PreviewParams(
      device: String,
      name: String,
      showSystemUi: String
  )

  object PreviewParams {
    implicit def resultRW: upickle.default.ReadWriter[PreviewParams] = upickle.default.macroRW
  }

  object Tests {
    case class PreviewParams(
        device: String,
        uiMode: String
    )

    /*
     * Model as in
     * [[https://android.googlesource.com/platform/tools/base/+/61923408e5f7dc20f0840844597f9dde17453a0f/preview/screenshot/screenshot-test-gradle-plugin/src/main/java/com/android/compose/screenshot/PreviewFinder.kt#92]]
     */
    case class ComposeScreenshot(
        methodFQN: String,
        methodParams: Seq[Map[String, String]],
        previewParams: Seq[PreviewParams],
        previewId: String
    )
  }
}

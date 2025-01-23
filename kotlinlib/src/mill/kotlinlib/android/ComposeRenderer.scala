package mill.kotlinlib.android

import mill.api.internal

private[android] object ComposeRenderer {

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
}

package mill.androidlib.databinding

case class ProcessResourcesArgs(
      applicationPackageName: String,
      resInputDir: String,
      resOutputDir: String,
      layoutInfoOutputDir: String,
      useAndroidX: Boolean = true
)

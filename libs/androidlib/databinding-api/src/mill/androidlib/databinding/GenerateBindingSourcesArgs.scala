package mill.androidlib.databinding

case class GenerateBindingSourcesArgs(
    applicationPackageName: String,
    layoutInfoDir: String,
    classInfoDir: String,
    outputDir: String,
    logFolder: String,
    enableViewBinding: Boolean,
    enableDataBinding: Boolean,
    useAndroidX: Boolean = true,
    dependencyClassInfoDirs: Seq[String] = Seq.empty[String]
)

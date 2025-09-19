package mill.androidlib.databinding

case class GenerateBaseClassesArgs(
    applicationPackageName: String,
    layoutInfoDir: String,
    dependencyClassInfoDirs: Seq[String] = Seq.empty[String],
    classInfoDir: String,
    outputDir: String,
    logFolder: String,
    enableViewBinding: Boolean,
    enableDataBinding: Boolean,
    useAndroidX: Boolean = true
)

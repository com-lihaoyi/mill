package mill.androidlib

import mill.androidlib.databinding.{
  AndroidDataBinding,
  ProcessResourcesArgs,
  GenerateBindingSourcesArgs
}
import mill.api.Task
import mill.api.Task.Worker
import mill.javalib.Dep
import mill.kotlinlib.*
import mill.*
import mill.util.Jvm

trait AndroidDataBindingModule extends AndroidKotlinModule {

  def enableViewBinding: T[Boolean] = Task {
    false
  }

  def enableDataBinding: T[Boolean] = Task {
    false
  }

  override def generatedSources: T[Seq[PathRef]] = Task {
    super.generatedSources() :+ generatedBindingSources()
  }

  override def androidCompiledModuleResources: T[Seq[PathRef]] = Task {

    val moduleResources = Seq(processedLayoutXmls().path / "resources")

    val aapt2Compile = Seq(androidSdkModule().aapt2Exe().path.toString(), "compile")

    for (libResDir <- moduleResources) {
      val segmentsSeq = libResDir.segments.toSeq
      val libraryName = segmentsSeq.dropRight(1).last
      val dirDest = Task.dest / libraryName
      os.makeDir(dirDest)
      val aapt2Args = Seq(
        "--dir",
        libResDir.toString,
        "-o",
        dirDest.toString
      )

      os.call(aapt2Compile ++ aapt2Args)
    }
    androidTransitiveCompiledResources() ++ Seq(PathRef(Task.dest))
  }

  def androidDataBindingCompilerDeps: T[Seq[Dep]] = Task {
    Seq(
      mvn"androidx.databinding:databinding-compiler:8.13.0",
      mvn"androidx.databinding:databinding-compiler-common:8.13.0"
    )
  }

  def androidDatabindingWorkerClassloader: Worker[ClassLoader] = Task.Worker {
    Jvm.createClassLoader(
      classPath = defaultResolver().classpath(
        androidDataBindingCompilerDeps() ++ Seq(
          Dep.millProjectModule("mill-libs-androidlib-databinding")
        )
      ).map(_.path),
      parent = getClass.getClassLoader
    )
  }

  def androidDatabindingModule: Worker[AndroidDataBinding] = Task.Worker {
    androidDatabindingWorkerClassloader().loadClass(
      "mill.androidlib.databinding.AndroidDataBindingImpl"
    ).getConstructor().newInstance().asInstanceOf[AndroidDataBinding]
  }

  def processedLayoutXmls: T[PathRef] = Task {
    if (!enableViewBinding() && !enableDataBinding()) {
      throw new Exception(
        "Module extends AndroidDataBindingModule but neither viewBinding nor dataBinding is enabled"
      )
    }
    val resOutputDir = Task.dest / "resources"
    val layoutInfoOutputDir = Task.dest / "layout_info"

    os.makeDir.all(resOutputDir)
    os.makeDir.all(layoutInfoOutputDir)
    val args = ProcessResourcesArgs(
      applicationPackageName = androidNamespace,
      resInputDir = androidResources().head.path.toString,
      resOutputDir = resOutputDir.toString,
      layoutInfoOutputDir = layoutInfoOutputDir.toString,
      enableViewBinding = enableViewBinding(),
      enableDataBinding = enableDataBinding()
    )

    androidDatabindingModule().processResources(args)

    PathRef(Task.dest)
  }

  def generatedBindingSources: T[PathRef] = Task {
    val logDir = Task.dest / "logs"
    val outputDir = Task.dest / "generated"
    val classInfoDir = Task.dest / "class_info"
    os.makeDir.all(logDir)
    os.makeDir.all(outputDir)
    os.makeDir.all(classInfoDir)
    val args = GenerateBindingSourcesArgs(
      applicationPackageName = androidNamespace,
      layoutInfoDir = (processedLayoutXmls().path / "layout_info").toString,
      classInfoDir = classInfoDir.toString,
      outputDir = outputDir.toString,
      logFolder = logDir.toString,
      enableViewBinding = enableViewBinding(),
      enableDataBinding = enableDataBinding()
    )

    androidDatabindingModule().generateBindingSources(args)

    PathRef(Task.dest)
  }

}

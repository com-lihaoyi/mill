package mill.androidlib

import mill.androidlib.databinding.{
  AndroidDataBinding,
  ProcessResourcesArgs,
  GenerateBaseClassesArgs
}
import mill.api.Task
import mill.api.Task.Worker
import mill.javalib.Dep
import mill.kotlinlib.*
import mill.*
import mill.util.Jvm

trait AndroidDataBindingModule extends AndroidKotlinModule {

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
    val resOutputDir = Task.dest / "resources"
    val layoutInfoOutputDir = Task.dest / "layout_info"

    os.makeDir.all(resOutputDir)
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

  def generatedBindingClasses: T[PathRef] = Task {
    val args = GenerateBaseClassesArgs(
      applicationPackageName = androidNamespace,
      layoutInfoDir = (processedLayoutXmls().path / "layout_info").toString,
      classInfoDir = (Task.dest / "class_info").toString,
      outputDir = (Task.dest / "generated").toString,
      logFolder = (Task.dest / "logs").toString,
      enableViewBinding = enableViewBinding(),
      enableDataBinding = enableDataBinding()
    )

    androidDatabindingModule().generateBaseClasses(args)

    PathRef(Task.dest)
  }

}

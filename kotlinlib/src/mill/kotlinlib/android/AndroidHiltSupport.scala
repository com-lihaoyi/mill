package mill.kotlinlib.android

import mill.api.PathRef
import mill.kotlinlib.DepSyntax
import mill.kotlinlib.ksp.KspModule
import mill.{T, Task}

import java.io.File

@mill.api.experimental
trait AndroidHiltSupport extends KspModule with AndroidAppKotlinModule {

  override def kspClasspath: T[Seq[PathRef]] =
   Seq(androidProcessResources()) ++ super.kspClasspath()

  def processorPath: T[Seq[PathRef]] = Task {
    defaultResolver().resolveDeps(
      kotlinSymbolProcessors().flatMap {
        dep =>
          if (dep.dep.module.name.value == "hilt-android-compiler" && 
            dep.dep.module.organization.value == "com.google.dagger"
          )
            Seq(
              dep,
              ivy"com.google.dagger:hilt-compiler:${dep.version}"
            )
          else
            Seq(dep)
      }
    )
  }
  
  def androidHiltGeneratedSources: T[PathRef] = Task {
    val directory = Task.dest / "generated" / "hilt"
    os.makeDir.all(directory)
    PathRef(directory)
  }

  override def generatedSources: T[Seq[PathRef]] = super.generatedSources() ++ 
    super.generateSourcesWithKSP() ++
    Seq(androidHiltGeneratedSources())

  def kotlincOptions: T[Seq[String]] = Task {
    super.kotlincOptions() ++
      Seq(
        "-P", "plugin:com.google.devtools.ksp.symbol-processing:apoption=dagger.fastInit=enabled",
        "-P", "plugin:com.google.devtools.ksp.symbol-processing:apoption=dagger.hilt.android.internal.disableAndroidSuperclassValidation=true",
        "-P", "plugin:com.google.devtools.ksp.symbol-processing:apoption=dagger.hilt.android.internal.projectType=APP",
        "-P", "plugin:com.google.devtools.ksp.symbol-processing:apoption=dagger.hilt.internal.useAggregatingRootProcessor=false",
        "-opt-in=kotlin.RequiresOptIn",
        "-opt-in=kotlin.Experimental",
      )
  }

  def hiltJavacOptions: T[Seq[String]] = Seq(
    "-processorpath", processorPath().map(_.path.toString).mkString(File.pathSeparator),
    "-XDstringConcat=inline",
    "-parameters",
    "-Adagger.fastInit=enabled",
    "-Adagger.hilt.internal.useAggregatingRootProcessor=false",
    "-Adagger.hilt.android.internal.disableAndroidSuperclassValidation=true",
    "-s", androidHiltGeneratedSources().path.toString
  )

  override def javacOptions: T[Seq[String]] = super.javacOptions() ++ hiltJavacOptions()

  def hiltProcessorClasspath: T[Seq[PathRef]] = compileClasspath
}

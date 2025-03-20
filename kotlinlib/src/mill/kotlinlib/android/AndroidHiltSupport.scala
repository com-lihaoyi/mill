package mill.kotlinlib.android

import mill.api.{PathRef, Result}
import mill.kotlinlib.DepSyntax
import mill.kotlinlib.android.AndroidHiltSupport.HiltGeneratedSources
import mill.kotlinlib.ksp.KspModule
import mill.kotlinlib.worker.api.KotlinWorkerTarget
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

  override def kspPluginsResolved: T[Seq[PathRef]] = Task {
    super.kspPluginsResolved()
  }

  override def kotlinSymbolProcessorsResolved: T[Seq[PathRef]] = Task {
    super.kotlinSymbolProcessorsResolved()
  }

//  private def jetify(ref: PathRef, jetifierStandalonePath: PathRef)(implicit ctx: mill.api.Ctx): PathRef = {
//    val baseName = ref.path.baseName
//    val jetifiedBaseName = s"jetified-${baseName}"
//    val destination = ref.path / os.up / s"${jetifiedBaseName}.${ref.path.ext}"
//    val cliArgs = Seq(
//      jetifierStandalonePath.path.toString,
//      "-i",
//      ref.path.toString,
//      "-o",
//      destination.toString
//    )
//    ctx.log.debug(s"Running ${cliArgs.mkString(" ")}")
//    val standaloneJetifierOut = os.call(
//      cliArgs,
//      check = false
//    )
//
//    if (standaloneJetifierOut.exitCode != 0) {
//      ctx.log.debug(s"Ignoring jetification of ${ref.path}")
//      ctx.log.debug(standaloneJetifierOut.out.text())
//      ctx.log.debug(standaloneJetifierOut.err.text())
//      ref
//    } else {
//      PathRef(destination)
//    }
//
//  }

  def jetifierStandalonePath: T[PathRef] = Task {
//    val zipFile = Task.dest / "jetifier-standalone.zip"
//    val zipFileExtracted = Task.dest / "extraxted"
//    os.makeDir.all(zipFileExtracted)
//    os.write(zipFile, requests.get(androidSdkModule().jetifierStandaloneUrl()).bytes)
//    val isWin = scala.util.Properties.isWin
//
//
//    os.unzip(zipFile, zipFileExtracted)
//
//    val executableName = if (isWin)
//      "jetifier-standalone.bat"
//    else
//      "jetifier-standalone"
//
//    val executablePath = zipFileExtracted / "jetifier-standalone" / "bin" / executableName
//
//    if (!isWin) os.perms.set(executablePath, "rwxrwxrwx")

    PathRef(os.home / "jetifier-standalone" / "bin" / "jetifier-standalone")
  }


  override def kspPluginParameters: T[Seq[String]] = Task {
    super.kspPluginParameters() ++
      Seq(
        s"apoption=dagger.fastInit=enabled",
        s"apoption=dagger.hilt.android.internal.disableAndroidSuperclassValidation=true",
        s"apoption=dagger.hilt.android.internal.projectType=APP",
        s"apoption=dagger.hilt.internal.useAggregatingRootProcessor=true"
      )
  }

  def androidJavaCompileKSPGeneratedSources: T[HiltGeneratedSources] = Task {
    val directory = Task.dest / "ap_generated/out"

    os.makeDir.all(directory)
    val generatedKSPSources = generateSourcesWithKSP()

    val javaGeneratedSources =
      generatedKSPSources
        .map(_.path)
        .filter(os.exists)
        .flatMap(os.walk(_)).filter(_.ext == "java")

    val kotlinClasses = Task.dest / "kotlin"

    val kotlinClasspath = compileClasspath() :+ androidProcessResources()

    val compileWithKotlin = Seq(
      "-d", kotlinClasses.toString,
      "-classpath", kotlinClasspath.map(_.path).mkString(File.pathSeparator)
    ) ++ kotlincOptions() ++ javaGeneratedSources.map(_.toString) ++ sources().map(_.path.toString)

    Task.log.info(s"Compiling kotlin classes ${compileWithKotlin.mkString(" ")}")

    kotlinWorkerTask().compile(KotlinWorkerTarget.Jvm, compileWithKotlin)

    val kspJavacOptions = Seq(
      "-XDstringConcat=inline",
      "-Adagger.fastInit=enabled",
      "-Adagger.hilt.internal.useAggregatingRootProcessor=true",
      "-Adagger.hilt.android.internal.disableAndroidSuperclassValidation=true",
      "-Aroom.incremental=true",
      "-g",
      "-XDuseUnsharedTable=true",
      "-proc:none",
      "-parameters",
      "-s", directory.toString,
    )

    val compileCp = compileClasspath().map(_.path) ++ Seq(androidProcessResources().path, kotlinClasses)

    val worker = zincWorkerRef().worker()

    Task.log.info(
      s"Compiling ${javaGeneratedSources.size} Java generated sources to ${directory} ..."
    )

    val compilation = worker.compileJava(
      upstreamCompileOutput = Seq.empty,
      sources = javaGeneratedSources,
      compileClasspath = compileCp,
      javacOptions = kspJavacOptions,
      reporter = T.ctx().reporter(hashCode()),
      reportCachedProblems = zincReportCachedProblems(),
      incrementalCompilation = true
    )

    HiltGeneratedSources(
      apGenerated = PathRef(directory),
      javaSources = javaGeneratedSources.map(PathRef(_)),
      javaCompiled = compilation.get.classes,
      kotlinCompiled = PathRef(kotlinClasses)
    )
  }

  def androidHiltGeneratedSources: T[Seq[PathRef]] = Task {
    val directory = Task.dest / "component_classes" / "out"
    os.makeDir.all(directory)

    val compiledKspSources: HiltGeneratedSources = androidJavaCompileKSPGeneratedSources()

    val hiltJavacOptions = Seq(
      "-processorpath", processorPath().map(_.path.toString).mkString(File.pathSeparator),
      "-XDstringConcat=inline",
      "-Adagger.fastInit=enabled",
      "-proc:none",
      "-Adagger.hilt.internal.useAggregatingRootProcessor=false",
      "-Adagger.hilt.android.internal.disableAndroidSuperclassValidation=true",
      "-Aroom.incremental=true",
      "-XDuseUnsharedTable=true",
      "-parameters",
      "-s", directory.toString,
    )

    val compileCp = hiltProcessorClasspath().map(_.path) ++
      Seq(compiledKspSources.kotlinCompiled.path)

    val worker = zincWorkerRef().worker()

    val classes = Task.dest / "classes"
    os.makeDir.all(classes)

    val sourcesToCompile = compiledKspSources.javaSources.map(_.path)

    T.log.info(s"Compiling ${sourcesToCompile.size} java sources to ${classes}")

    val result = worker.compileJava(
      upstreamCompileOutput = upstreamCompileOutput(),
      sources = sourcesToCompile,
      compileClasspath = compileCp,
      javacOptions = hiltJavacOptions,
      reporter = T.ctx().reporter(hashCode()),
      reportCachedProblems = zincReportCachedProblems(),
      incrementalCompilation = false
    )

    Seq(result.get.classes)

  }

  override def javaCompiledGeneratedSources: T[Seq[PathRef]] =
    androidHiltGeneratedSources

  def hiltProcessorClasspath: T[Seq[PathRef]] = Task {
    kspApClasspath() ++ compileClasspath()
  }
}

object AndroidHiltSupport {
  case class HiltGeneratedSources(apGenerated: PathRef, javaSources: Seq[PathRef], kotlinCompiled: PathRef, javaCompiled: PathRef)

  object HiltGeneratedSources {
    implicit def resultRW: upickle.default.ReadWriter[HiltGeneratedSources] = upickle.default.macroRW
  }
}

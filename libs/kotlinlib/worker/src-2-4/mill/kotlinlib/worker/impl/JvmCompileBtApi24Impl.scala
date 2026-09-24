package mill.kotlinlib.worker.impl

import mill.api.TaskCtx
import org.jetbrains.kotlin.buildtools.api.{BuildOperation, CompilationResult, SourcesChanges}
import org.jetbrains.kotlin.buildtools.api.arguments.{CommonCompilerArguments, JvmCompilerArguments}
import org.jetbrains.kotlin.buildtools.api.arguments.enums.{JvmTarget, KotlinVersion}
import org.jetbrains.kotlin.buildtools.api.jvm.{
  ClasspathEntrySnapshot,
  ClassSnapshotGranularity,
  JvmPlatformToolchain
}
import org.jetbrains.kotlin.buildtools.api.jvm.operations.{
  JvmClasspathSnapshottingOperation,
  JvmCompilationOperation
}

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/**
 * [[BtApiCompiler]] backend for Kotlin 2.4.0+, which replaced the legacy operation factories with
 * the builders introduced in 2.3.20 and added typesafe compiler options. Compiled against the 2.4
 * Build Tools API and classloaded by [[KotlinWorkerImpl]].
 */
class JvmCompileBtApi24Impl(classpathSnapshotCache: os.Path)
    extends BtApiCompiler(classpathSnapshotCache) {

  protected def compilationOperation(
      jvmToolchain: JvmPlatformToolchain,
      sourceFiles: java.util.List[Path],
      destinationDirectory: os.Path,
      args: Seq[String],
      incrementalCachePath: os.Path,
      classpathSnapshotFiles: Seq[Path]
  )(using ctx: TaskCtx): BuildOperation[CompilationResult] = {
    val builder = jvmToolchain.jvmCompilationOperationBuilder(
      sourceFiles,
      destinationDirectory.toNIO
    )

    val compilerArguments = builder.getCompilerArguments()
    compilerArguments.applyArgumentStrings(untypedArguments(args).asJava)
    applyTypedArguments(compilerArguments, args)

    val icBuilder = builder.snapshotBasedIcConfigurationBuilder(
      incrementalCachePath.toNIO,
      SourcesChanges.ToBeCalculated.INSTANCE,
      classpathSnapshotFiles.asJava,
      (incrementalCachePath / "shrunk-classpath-snapshot.bin").toNIO
    )
    import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration as Ic
    icBuilder.set(Ic.ROOT_PROJECT_DIR, ctx.workspace.toNIO)
    icBuilder.set(Ic.MODULE_BUILD_DIR, incrementalCachePath.toNIO)
    icBuilder.set(Ic.PRECISE_JAVA_TRACKING, java.lang.Boolean.TRUE)

    builder.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, icBuilder.build())
    builder.build()
  }

  protected def snapshottingOperation(
      jvmToolchain: JvmPlatformToolchain,
      classpathEntry: Path
  )(using ctx: TaskCtx): BuildOperation[ClasspathEntrySnapshot] = {
    val builder = jvmToolchain.classpathSnapshottingOperationBuilder(classpathEntry)
    builder.set(
      JvmClasspathSnapshottingOperation.GRANULARITY,
      ClassSnapshotGranularity.CLASS_MEMBER_LEVEL
    )
    builder.set(
      JvmClasspathSnapshottingOperation.PARSE_INLINED_LOCAL_CLASSES,
      java.lang.Boolean.TRUE
    )
    builder.build()
  }

  private def untypedArguments(args: Seq[String]): Vector[String] = {
    val kept = Vector.newBuilder[String]
    var i = 0
    while (i < args.length) {
      val flag = args(i)
      if (flag == "-d") i += 2
      else typedWidth(flag) match {
        case 0 => kept += flag; i += 1
        case width => i += width
      }
    }
    kept.result()
  }

  private def applyTypedArguments(a: JvmCompilerArguments.Builder, args: Seq[String]): Unit = {
    var i = 0
    while (i < args.length) {
      val flag = args(i)
      def value = args(i + 1)
      flag match {
        case "-module-name" => a.set(JvmCompilerArguments.MODULE_NAME, value)
        case "-jdk-home" => a.set(JvmCompilerArguments.JDK_HOME, Path.of(value))
        case "-no-stdlib" => a.set(JvmCompilerArguments.NO_STDLIB, java.lang.Boolean.TRUE)
        case "-no-reflect" => a.set(JvmCompilerArguments.NO_REFLECT, java.lang.Boolean.TRUE)
        case "-java-parameters" =>
          a.set(JvmCompilerArguments.JAVA_PARAMETERS, java.lang.Boolean.TRUE)
        case "-language-version" =>
          kotlinVersions.get(value).foreach(a.set(CommonCompilerArguments.LANGUAGE_VERSION, _))
        case "-api-version" =>
          kotlinVersions.get(value).foreach(a.set(CommonCompilerArguments.API_VERSION, _))
        case "-jvm-target" =>
          jvmTargets.get(value).foreach(a.set(JvmCompilerArguments.JVM_TARGET, _))
        case _ =>
      }
      i += (if (flag == "-d") 2 else math.max(typedWidth(flag), 1))
    }
  }

  private def typedWidth(flag: String): Int = flag match {
    case "-module-name" | "-jdk-home" | "-language-version" | "-api-version" | "-jvm-target" => 2
    case "-no-stdlib" | "-no-reflect" | "-java-parameters" => 1
    case _ => 0
  }

  private val kotlinVersions: Map[String, KotlinVersion] =
    KotlinVersion.values().iterator.map(v => v.getStringValue -> v).toMap
  private val jvmTargets: Map[String, JvmTarget] =
    JvmTarget.values().iterator.map(v => v.getStringValue -> v).toMap
}

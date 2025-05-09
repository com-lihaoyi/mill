package mill.spotless

import com.diffplug.spotless.{Formatter, LineEnding, LintState, Provisioner}

import java.io.File
import scala.jdk.CollectionConverters.*
import scala.util.Using

trait SpotlessApi {

  def format(
      workspace: os.Path,
      files: Seq[os.Path],
      check: Boolean,
      onFormat: os.Path => Unit
  ): Int
}
object SpotlessApi {

  def create(
      spotlessConfig: os.Path,
      configRoots: Seq[os.Path],
      provision: Provision
  ): SpotlessApi = {
    val config =
      Using.resource(os.read.inputStream(spotlessConfig))(upickle.default.read[SpotlessConfig](_))
    SpotlessConfigApi(config, provisioner(provision), PathResolver(configRoots))
  }

  def prepareOffline(
      spotlessConfig: os.Path,
      configRoots: Seq[os.Path],
      provision: Provision
  ): Seq[File] = {
    // Spotless defers artifact resolution until format
    // so we have to build all formatters and run them
    val artifacts = Seq.newBuilder[File]
    val provisioner0 = provisioner((withTransitives, mavenCoordinates) =>
      val files = provision(withTransitives, mavenCoordinates)
      artifacts ++= files
      files
    )
    val resolver = PathResolver(configRoots)
    val config =
      Using.resource(os.read.inputStream(spotlessConfig))(upickle.default.read[SpotlessConfig](_))
    val file = os.temp().toIO
    config.formatters.foreach { case FormatterConfig(steps = steps, encoding = encoding) =>
      given FormatterContext = FormatterContext(encoding, provisioner0, resolver)
      val formatter = Formatter.builder()
        .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
        .encoding(encoding)
        .steps(steps.map(_.build).asJava)
        .build()
      LintState.of(formatter, file)
    }
    artifacts.result()
  }

  class SpotlessConfigApi(config: SpotlessConfig, provisioner: Provisioner, resolver: PathResolver)
      extends SpotlessApi {
    import config.{formatters as _, *}
    private val formatters = config.formatters.map(FormatterConfigApi(_, provisioner, resolver))
    def format(
        workspace: os.Path,
        files: Seq[os.Path],
        check: Boolean,
        onFormat: os.Path => Unit
    ) = {
      val filtered = files.filterNot(path => excludes.exists(_.matches(path.toNIO)))
      formatters.foldLeft(0)(_ + _.format(workspace, filtered, check, onFormat))
    }
  }

  class FormatterConfigApi(
      config: FormatterConfig,
      provisioner: Provisioner,
      resolver: PathResolver
  ) extends SpotlessApi {
    import config.{steps as _, *}
    private val steps = {
      given FormatterContext = FormatterContext(config.encoding, provisioner, resolver)
      config.steps.map(_.build)
    }
    def format(
        workspace: os.Path,
        files: Seq[os.Path],
        check: Boolean,
        onFormat: os.Path => Unit
    ) = {
      val filtered = files.filter(p => includes.exists(_.matches(p.toNIO)))
      if (filtered.isEmpty) 0
      else {
        val policy = lineEnding.createPolicy(workspace.toIO, () => filtered.map(_.toIO).asJava)
        val formatter = Formatter.builder()
          .lineEndingsPolicy(policy)
          .encoding(encoding)
          .steps(steps.asJava)
          .build
        val ext = filtered.iterator.map(_.ext).distinct.toSeq.sorted.mkString("|")
        val suppressions = lintSuppressions.map(_.build).asJava
        var divergingFileCount = 0
        var lintsFileCount = 0
        var dirtyFileCount = 0
        var cleanedFileCount = 0
        println(s"checking format in ${filtered.length} $ext files")
        for (path <- filtered) {
          val file = path.toIO
          val rel = path.relativeTo(workspace)
          val lintState = LintState.of(formatter, file)
            .withRemovedSuppressions(formatter, rel.toString(), suppressions)
          if (lintState.getDirtyState.didNotConverge()) {
            Console.err.println(s"failed to converge $rel")
            divergingFileCount += 1
          } else if (lintState.isHasLints) {
            Console.err.println(lintState.asStringDetailed(file, formatter))
            lintsFileCount += 1
          } else if (!lintState.getDirtyState.isClean) {
            if (check) {
              println(s"format errors in $rel")
              dirtyFileCount += 1
            } else {
              println(s"formatting $rel")
              lintState.getDirtyState.writeCanonicalTo(file)
              onFormat(path)
              cleanedFileCount += 1
            }
          }
          if (cleanedFileCount > 0)
            println(s"formatted $cleanedFileCount $ext files")
          if (dirtyFileCount > 0)
            println(s"format errors in $dirtyFileCount $ext files")
          if (lintsFileCount > 0)
            Console.err.println(
              s"lint errors in $lintsFileCount $ext files must be fixed/suppressed"
            )
          if (divergingFileCount > 0)
            // https://github.com/diffplug/spotless/blob/main/PADDEDCELL.md#a-misbehaving-step
            Console.err.println(s"failed to converge $divergingFileCount $ext files with steps: " +
              steps.iterator.map(_.getName).mkString(","))
        }
        dirtyFileCount + lintsFileCount + divergingFileCount
      }
    }
  }

  private type Provision = (Boolean, Seq[String]) => Set[File]
  private def provisioner(provision: Provision): Provisioner =
    (withTransitives, mavenCoordinates) =>
      provision(withTransitives, mavenCoordinates.asScala.toSeq).asJava
}

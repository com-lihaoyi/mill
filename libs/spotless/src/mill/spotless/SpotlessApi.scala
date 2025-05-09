package mill.spotless

import com.diffplug.spotless.{Formatter, LintState, Provisioner}

import java.io.File
import scala.jdk.CollectionConverters.*
import scala.util.Using

trait SpotlessApi {

  def format(files: Seq[os.Path], check: Boolean, workspace: os.Path): Int
}
object SpotlessApi {

  def prepare(
      spotlessConfig: os.Path,
      configRoots: Seq[os.Path],
      provision: (Boolean, Seq[String]) => Seq[File],
      onReformat: os.Path => Unit
  ): SpotlessApi = {
    val provisioner: Provisioner =
      (withTransitives, mavenCoordinates) =>
        provision(withTransitives, mavenCoordinates.asScala.toSeq).toSet.asJava
    given SpotlessContext = SpotlessContext(configRoots, provisioner)
    val config =
      Using(os.read.inputStream(spotlessConfig))(upickle.default.read[SpotlessConfig](_)).get
    SpotlessConfigApi(config, onReformat)
  }

  class SpotlessConfigApi(config: SpotlessConfig, onReformat: os.Path => Unit)(using
      SpotlessContext
  ) extends SpotlessApi {
    import config.*
    private val apis = formatters.map(FormatterConfigApi(_, onReformat))
    def format(files: Seq[os.Path], check: Boolean, workspace: os.Path) = {
      val filtered = files.filterNot(path => excludes.exists(_.matches(path.toNIO)))
      apis.foldLeft(0)(_ + _.format(filtered, check, workspace))
    }
  }

  class FormatterConfigApi(config: FormatterConfig, onReformat: os.Path => Unit)(using
      SpotlessContext
  ) extends SpotlessApi {
    import config.{steps as _, *}
    private val steps = config.steps.map(_.build(encoding))
    def format(files: Seq[os.Path], check: Boolean, workspace: os.Path) = {
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
          val lintState = LintState.of(formatter, file)
            .withRemovedSuppressions(formatter, "*", suppressions)
          if (lintState.getDirtyState.didNotConverge()) {
            println(s"failed to converge $file")
            divergingFileCount += 1
          } else if (lintState.isHasLints) {
            println(lintState.asStringDetailed(file, formatter))
            lintsFileCount += 1
          } else if (!lintState.getDirtyState.isClean) {
            if (check) {
              Console.err.println(s"format errors in $file")
              dirtyFileCount += 1
            } else {
              println(s"formatting $file")
              lintState.getDirtyState.writeCanonicalTo(file)
              onReformat(path)
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
            Console.err.println(s"failed to converge $divergingFileCount $ext files with steps: " +
              steps.iterator.map(_.getName).mkString(","))
        }
        dirtyFileCount + lintsFileCount + divergingFileCount
      }
    }
  }
}

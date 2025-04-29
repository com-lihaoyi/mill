package mill.spotless

import com.diffplug.spotless.LintState
import mainargs.*
import mill.define.WorkspaceRoot
import os.Path

import java.io.File
import scala.jdk.CollectionConverters.*
import scala.util.Using

object SpotlessMain {

  def main(args: Array[String]): Unit = {
    ParserForMethods(this).runOrThrow(args, allowPositional = true)
  }

  @main
  def run(
      check: Flag,
      @arg(positional = true)
      configFile: os.Path,
      @arg(positional = true)
      srcDirs: Seq[os.Path]
  ): Unit = {
    val config =
      Using(os.read.inputStream(configFile))(upickle.default.read[SpotlessConfig](_)).get
    val files = srcDirs.flatMap(src =>
      os.walk.stream(src).collect {
        case src if os.isFile(src) && config.extensions.contains(src.ext) => src.toIO
      }.toSeq
    )
    val extensionsStr = config.extensions.mkString("|")
    if (files.isEmpty) {
      println(s"no $extensionsStr files found in $srcDirs")
    } else {
      val root = WorkspaceRoot.workspaceRoot
      given SpotlessContext = SpotlessContext(
        config.formatter.encoding,
        new CoursierProvisioner(),
        PathResolver(root)
      )
      val formatter = config.formatter.build(config.steps.map(_.build), root.toIO, files)

      println(s"checking format in ${files.length} $extensionsStr file(s)")
      var errFileCount = 0
      var fmtFileCount = 0
      files.foreach { file =>
        val lintState = LintState.of(formatter, file)
        if (lintState.getDirtyState.didNotConverge()) {
          Console.err.println(s"failed to determine a clean state for $file")
          errFileCount += 1
        } else if (lintState.isHasLints) {
          Console.err.println(lintState.asStringDetailed(file, formatter))
          errFileCount += 1
        } else if (!lintState.getDirtyState.isClean) {
          if (check.value) {
            println(s"format errors in $file")
            errFileCount += 1
          } else {
            println(s"formatting $file")
            lintState.getDirtyState.writeCanonicalTo(file)
            fmtFileCount += 1
          }
        }
      }
      if (fmtFileCount > 0) {
        println(s"formatted $fmtFileCount $extensionsStr file(s)")
      }
      if (errFileCount > 0) {
        Console.err.println(s"format/lint errors in $errFileCount $extensionsStr file(s)")
      }
      System.exit(errFileCount)
    }
  }

  private given TokensReader.Simple[os.Path] = new TokensReader.Simple[Path] {
    def read(strs: Seq[String]): Either[String, Path] = Right(os.Path(strs.head))
    def shortName: String = "path"
  }
}

package mill.kotlinlib.worker.impl

import mill.api.TaskCtx
import org.jetbrains.kotlin.cli.common.messages.{
  CompilerMessageSeverity,
  CompilerMessageSourceLocation,
  MessageCollector,
  MessageRenderer,
  OutputMessageUtil,
  PrintingMessageCollector
}
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services

import java.io.{FileInputStream, FileOutputStream}
import java.util.Properties
import scala.collection.mutable
import scala.util.Try

class JvmCompileImpl() extends Compiler {

  private def destinationDirectoryFromArgs(args: Seq[String])(using ctx: TaskCtx): os.Path = {
    args.sliding(2)
      .collectFirst {
        case Seq("-d", dir) => os.Path(dir, ctx.workspace)
      }
      .getOrElse(ctx.dest / "classes")
  }

  private def trackedOutputsFile(using ctx: TaskCtx): os.Path =
    ctx.dest / "kotlin-cli-output-files.properties"

  private def isInDirectory(path: os.Path, dir: os.Path): Boolean =
    path.toNIO.normalize().startsWith(dir.toNIO.normalize())

  private def loadTrackedOutputs(file: os.Path)(using ctx: TaskCtx): Option[Seq[os.Path]] = {
    if (!os.exists(file)) None
    else {
      Try {
        val props = new Properties()
        val input = new FileInputStream(file.toIO)
        try {
          props.load(input)
        } finally {
          input.close()
        }

        val count = Try(props.getProperty("count").toInt).getOrElse(0)
        (0 until count).flatMap { i =>
          Option(props.getProperty(s"output.$i")).flatMap { path =>
            Try(os.Path(path, ctx.workspace)).toOption
          }
        }
      }.toOption
    }
  }

  private def storeTrackedOutputs(file: os.Path, outputs: Seq[os.Path]): Unit = {
    os.makeDir.all(file / os.up)

    val props = new Properties()
    val distinctOutputs = outputs.distinct.sortBy(_.toString)
    props.setProperty("count", distinctOutputs.size.toString)
    distinctOutputs.zipWithIndex.foreach { case (output, index) =>
      props.setProperty(s"output.$index", output.toString)
    }

    val out = new FileOutputStream(file.toIO)
    try {
      props.store(out, "Kotlin CLI output files")
    } finally {
      out.close()
    }
  }

  private def removePreviousOutputs(destinationDirectory: os.Path)(using ctx: TaskCtx): Unit = {
    loadTrackedOutputs(trackedOutputsFile) match {
      case Some(outputs) =>
        outputs.filter(isInDirectory(_, destinationDirectory)).foreach(os.remove.all(_))
      case None =>
        os.remove.all(destinationDirectory)
        os.makeDir.all(destinationDirectory)
    }
  }

  private def trackingMessageCollector(
      delegate: MessageCollector,
      destinationDirectory: os.Path,
      outputFiles: mutable.LinkedHashSet[os.Path]
  ): MessageCollector = new MessageCollector {
    override def clear(): Unit = delegate.clear()

    override def hasErrors(): Boolean = delegate.hasErrors()

    override def report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation
    ): Unit = {
      if (severity == CompilerMessageSeverity.OUTPUT) {
        val outputFile = Option(OutputMessageUtil.parseOutputMessage(message))
          .flatMap(output => Option(output.outputFile))
          .map(file => os.Path(file.toPath))
          .filter(isInDirectory(_, destinationDirectory))

        outputFile.foreach(outputFiles.add)
      } else {
        delegate.report(severity, message, location)
      }
    }
  }

  def compile(
      args: Seq[String],
      sources: Seq[os.Path]
  )(using
      ctx: TaskCtx
  ): (Int, String) = {

    val shouldTrackOutputs = args.contains("-Xreport-output-files")
    val allArgs = args ++ sources.map(_.toString)

    val compiler = K2JVMCompiler()
    val exitCode =
      if (shouldTrackOutputs) {
        val destinationDirectory = destinationDirectoryFromArgs(args)
        removePreviousOutputs(destinationDirectory)

        val arguments = compiler.createArguments()
        compiler.parseArguments(allArgs.toArray, arguments)

        val outputFiles = mutable.LinkedHashSet.empty[os.Path]
        val printer = new PrintingMessageCollector(
          ctx.log.streams.err,
          MessageRenderer.PLAIN_RELATIVE_PATHS,
          false
        )
        val collector = trackingMessageCollector(printer, destinationDirectory, outputFiles)
        val result = compiler.exec(collector, Services.EMPTY, arguments)

        if (result.getCode() == 0) {
          storeTrackedOutputs(trackedOutputsFile, outputFiles.toSeq)
        }

        result
      } else {
        compiler.exec(ctx.log.streams.err, allArgs*)
      }

    (exitCode.getCode(), exitCode.name())
  }

}

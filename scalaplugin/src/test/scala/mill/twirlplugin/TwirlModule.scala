package mill
package twirlplugin

import mill.scalaplugin.ScalaModule
import ammonite.ops._
import mill.define.Target
import play.twirl.compiler.TwirlCompiler
import mill.define.Task
import mill.define.Task.{Module, TaskModule}
import mill.util.Ctx
import TwirlModule._

trait TwirlModule extends ScalaModule {
  override def allSources = T {
    super.allSources() ++ twirlSources(basePath / 'src / 'main / 'twirl)
  }
}


object TwirlModule {
  def twirlSources(inputDir: Path)(implicit ctx: Ctx): Seq[PathRef] = {
    val outputDir = ctx.dest / 'twirl

    val twirlFiles = ls.rec(inputDir).filter(_.name.contains(".scala."))

    val htmlFiles =  twirlFiles.filter(_.name.endsWith(".scala.html"))
    val xmlFiles =  twirlFiles.filter(_.name.endsWith(".scala.xml"))

    val handledHtml = htmlFiles.map { tFile =>
      TwirlCompiler.compile(tFile.toIO, inputDir.toIO, outputDir.toIO, "play.twirl.api.HtmlFormat", additionalImports = TwirlCompiler.DefaultImports)
    }

    val handledXml = xmlFiles.map { tFile =>
      TwirlCompiler.compile(tFile.toIO, inputDir.toIO, outputDir.toIO, "play.twirl.api.XmlFormat", additionalImports = TwirlCompiler.DefaultImports)
    }

    Seq(PathRef(outputDir))
  }
}

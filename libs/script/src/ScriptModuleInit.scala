package mill.script
import mill.*
import mill.api.{Discover, ExternalModule, Result}
import mill.scalalib.ScalaModule
import mill.kotlinlib.KotlinModule
import mill.script.ScriptModule.parseHeaderData

object ScriptModuleInit extends ((String, String => Option[mill.Module]) => Option[Result[mill.api.ExternalModule]]) {
  def instantiate(className: String, args: AnyRef*): ExternalModule = {
    val cls =
      try Class.forName(className)
      catch{case e: Throwable =>
        // Hack to try and pick up classes nested within package objects
        Class.forName(className.reverse.replaceFirst("\\.", "\\$").reverse)
      }

    cls.getDeclaredConstructors.head.newInstance(args *).asInstanceOf[ExternalModule]
  }
  def moduleFor(millFile: os.Path,
                extendsConfig: Option[String],
                moduleDeps: Seq[String],
                resolveModuleDep: String => Option[mill.Module]) = {
    val className = extendsConfig.getOrElse{
      millFile.ext match {
        case "java" => "mill.script.Java"
        case "scala" => "mill.script.Scala"
        case "kt" => "mill.script.Kotlin"
      }
    }

    instantiate(className, millFile, moduleDeps.map(resolveModuleDep(_).get))
  }

  def apply(millFileString: String, resolveModuleDep: String => Option[mill.Module]) = {
    val workspace = mill.api.BuildCtx.workspaceRoot
    val millFile = os.Path(millFileString, workspace)

    Option.when(os.exists(millFile)) {
      Result.create {
        val parsedHeaderData = parseHeaderData(millFile)
        val moduleDeps = parsedHeaderData.get("moduleDeps").map(_.arr.map(_.str)).getOrElse(Nil)
        val extendsConfig = parsedHeaderData.get("extends").map(_.str)
        moduleFor(millFile, extendsConfig, moduleDeps.toSeq, resolveModuleDep)
      }
    }
  }
}

package mill.script
import mill.*
import mill.api.{ExternalModule, Result}
import mill.script.ScriptModule.parseHeaderData

private object ScriptModuleInit
    extends (
        (
            String,
            String => Option[mill.Module],
            Boolean,
            Option[String]
        ) => Seq[Result[mill.api.ExternalModule]]
    ) {
  def instantiate(className: String, args: AnyRef*): ExternalModule = {
    val cls =
      try Class.forName(className)
      catch {
        case _: Throwable =>
          // Hack to try and pick up classes nested within package objects
          Class.forName(className.reverse.replaceFirst("\\.", "\\$").reverse)
      }

    cls.getDeclaredConstructors.head.newInstance(args*).asInstanceOf[ExternalModule]
  }
  def moduleFor(
      millFile: os.Path,
      extendsConfig: Option[String],
      moduleDeps: Seq[String],
      compileModuleDeps: Seq[String],
      runModuleDeps: Seq[String],
      resolveModuleDep: String => Option[mill.Module]
  ) = {
    val className = extendsConfig.getOrElse {
      millFile.ext match {
        case "java" => "mill.script.ScriptModule$JavaModule"
        case "scala" => "mill.script.ScriptModule$ScalaModule"
        case "kt" => "mill.script.ScriptModule$KotlinModule"
      }
    }

    instantiate(
      className,
      ScriptModule.Config(
        millFile,
        moduleDeps.map(resolveModuleDep(_).get),
        compileModuleDeps.map(resolveModuleDep(_).get),
        runModuleDeps.map(resolveModuleDep(_).get)
      )
    )
  }

  def apply(
      millFileString: String,
      resolveModuleDep: String => Option[mill.Module],
      resolveChildren: Boolean,
      nameOpt: Option[String]
  ) = {
    val workspace = mill.api.BuildCtx.workspaceRoot

    def resolve0(millFile: os.Path) = {
      Option.when(os.isFile(millFile) || os.exists(millFile / "mill.yaml")) {
        Result.create {
          val parsedHeaderData = parseHeaderData(millFile)
          moduleFor(
            millFile,
            parsedHeaderData.`extends`.headOption,
            parsedHeaderData.moduleDeps,
            parsedHeaderData.compileModuleDeps,
            parsedHeaderData.runModuleDeps,
            resolveModuleDep
          )
        }
      }
    }
    mill.api.BuildCtx.withFilesystemCheckerDisabled {
      val millFile0 = os.Path(millFileString, workspace)
      if (resolveChildren) {
        nameOpt match {
          case Some(n) => resolve0(millFile0 / n).toSeq
          case None =>
            if (!os.isDir(millFile0)) Nil
            else os.list(millFile0).filter(os.isDir).flatMap(resolve0)
        }
      } else resolve0(millFile0).toSeq
    }
  }
}

package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import mill.api.daemon.internal.EvaluatorApi
import mill.api.daemon.internal.bsp.BspModuleApi
import org.eclipse.jgit.ignore.{FastIgnoreRule, IgnoreNode}

import scala.jdk.CollectionConverters.*

/**
 * Handles discovery and instantiation of script modules for BSP.
 * Encapsulates the gitignore-style pattern matching logic for filtering
 * which scripts should be exposed as BSP build targets.
 */
private[worker] object ScriptModuleDiscovery {

  def discover(
      eval: EvaluatorApi,
      bspScriptIgnore: Seq[String],
      nonScriptSources: Seq[os.SubPath],
      nonScriptResources: Seq[os.SubPath],
      debug: (() => String) => Unit
  ): Seq[(BuildTargetIdentifier, (BspModuleApi, EvaluatorApi))] = {

    // Create IgnoreNode from bspScriptIgnore patterns
    val ignoreRules = bspScriptIgnore
      .filter(l => !l.startsWith("#"))
      .map(pattern => (pattern, new FastIgnoreRule(pattern)))

    val ignoreNode = new IgnoreNode(ignoreRules.map(_._2).asJava)

    // Extract directory prefixes from negation patterns (patterns starting with !)
    // These directories need to be walked even if they're ignored, because they contain
    // negated (un-ignored) files
    val negationPatternDirs: Set[String] = bspScriptIgnore
      .collect { case s"!$withoutNegation" =>
        // Extract all parent directory paths
        val pathParts = withoutNegation.split('/').dropRight(1) // Remove filename
        if (pathParts.nonEmpty) pathParts.inits.map(_.mkString("/"))
        else Nil
      }
      .flatten
      .toSet

    // Helper function to recursively check if a path should be ignored
    def isPathIgnored(relativePath: String, isDirectory: Boolean): Option[String] = {
      val relativePath2 = os.SubPath(relativePath)
      def insideModuleSources = (
        nonScriptSources.find(relativePath2.startsWith(_)) orElse
          nonScriptResources.find(relativePath2.startsWith(_))
      ).map("Inside module source folder " + _)

      ignoreNode.isIgnored(relativePath, isDirectory) match {
        case IgnoreNode.MatchResult.IGNORED => Some("Ignored due to `bspScriptIgnore`")
        case IgnoreNode.MatchResult.NOT_IGNORED => None
        case IgnoreNode.MatchResult.CHECK_PARENT =>
          // No direct match, need to check if parent directory is ignored
          val parentPath = relativePath.split('/').dropRight(1).mkString("/")
          if (parentPath.isEmpty) None // root level, not ignored by default
          // if this is inside a module's sources, ignore it
          else insideModuleSources.orElse {
            isPathIgnored(parentPath, true) // recursively check parent
          }
        case _ => insideModuleSources
      }
    }

    // Create filter function that checks both files and directories
    val skipPath: (String, Boolean) => Boolean = { (relativePath, isDirectory) =>
      // If this is a directory that contained in negation patterns, don't skip it
      if (isDirectory && negationPatternDirs.contains(relativePath)) false
      else {
        isPathIgnored(relativePath, isDirectory) match {
          case None => false
          case Some(msg) =>
            debug(() => s"Skipping script discovery in $relativePath: $msg")
            true
        }
      }
    }

    // Convert Scala function to Function2 for reflection (String, Boolean) => Boolean
    val function2Class = eval.getClass.getClassLoader.loadClass("scala.Function2")

    // Reflectively load and call `ScriptModuleInit.discoverAndInstantiateScriptModules`
    // from the evaluator's classloader
    val result = eval
      .scriptModuleInit
      .getClass
      .getMethod(
        "discoverAndInstantiateScriptModules",
        eval.getClass
          .getClassLoader
          .loadClass("mill.api.Evaluator"),
        function2Class
      )
      .invoke(eval.scriptModuleInit, eval, skipPath)
      .asInstanceOf[Seq[(java.nio.file.Path, mill.api.Result[BspModuleApi])]]

    result.flatMap {
      case (scriptPath: java.nio.file.Path, mill.api.Result.Success(module: BspModuleApi)) =>
        Some((new BuildTargetIdentifier(Utils.sanitizeUri(scriptPath)), (module, eval)))
      case (scriptPath: java.nio.file.Path, f: mill.api.Result.Failure) =>
        debug(() =>
          s"Failed to instantiate script module for BSP: $scriptPath failed with ${f.error}"
        )
        None
    }
  }
}

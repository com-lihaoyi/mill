package mill.api

import scala.annotation.StaticAnnotation

/**
 * Annotation to mark internal API, which is not guaranteed to stay.
 */
class internal extends StaticAnnotation {
  implicit def serializeEnvVariables(a: os.Path): String =
    val stringified = a.toString
    val workspaceRootPath = WorkspaceRoot.workspaceRoot.toString
    return stringified.replace(workspaceRootPath, "*$WorkplaceRoot*")

  implicit def deserializeEnvVariables(a: String): os.Path =
    val workspaceRootPath = WorkspaceRoot.workspaceRoot.toString
    val replaced = a.replace("*$WorkplaceRoot*", workspaceRootPath)
    val pathified = os.Path(replaced)
    return pathified
}

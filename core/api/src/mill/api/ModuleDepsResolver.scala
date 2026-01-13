package mill.api

/**
 * Helper object for resolving module deps from string identifiers at runtime.
 * Used by YAML builds to defer module resolution from codegen time to runtime.
 */
object ModuleDepsResolver {

  /**
   * Resolves module deps from string identifiers at runtime.
   *
   * @param deps The module dep strings to resolve (e.g., "foo.bar", "baz")
   * @param append Whether to append to default or replace it
   * @param default The default module deps (from super.moduleDeps)
   * @return The resolved modules, either replacing or appending to default
   */
  def resolveModuleDeps[T <: Module](
      deps: Seq[String],
      append: Boolean,
      default: => Seq[T]
  ): Seq[T] = {
    if (deps.isEmpty) {
      if (append) default else Seq.empty
    } else {
      val eval = Evaluator.currentEvaluator
      if (eval == null) {
        throw new IllegalStateException(
          "Cannot resolve module deps: no evaluator available. " +
            "moduleDeps should only be accessed during task evaluation."
        )
      }

      val resolved = deps.flatMap { depString =>
        eval.resolveModulesOrTasks(Seq(depString), SelectMode.Multi) match {
          case Result.Success(results) =>
            results.flatMap {
              case Left(module) => Some(module.asInstanceOf[T])
              case Right(_) => None // Tasks are not valid module deps
            }
          case f: Result.Failure =>
            throw new IllegalArgumentException(
              s"Failed to resolve module dep '$depString': ${f.error}"
            )
        }
      }

      if (append) default ++ resolved else resolved
    }
  }
}

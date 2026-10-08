package mill.util.internal

import mill.*
import mill.api.{BuildCtx, Discover, DynamicModule, Module}
import mill.api.internal.{PrecompiledModuleRef, PrecompiledModulesInfo}
import mill.util.MainRootModule

/**
 * Pre-compiled empty root module used when codegen produces no `build_.package_`
 * (e.g. the workspace has no `build.mill`/`build.mill.yaml`, or the root build
 * file is itself precompiled).
 *
 * Adds the [[DynamicModule]] mixin so that depth-1 precompiled YAML siblings
 * marked as orphan by codegen show up as direct children of the root. Mixing
 * `DynamicModule` here (rather than on `MainRootModule` itself) keeps the
 * codegen-emitted `build_.package_` on the reflective resolve path, so
 * `Cross[T]` roots and other resolve-time machinery keep working unchanged.
 */
private[mill] object DummyModule extends MainRootModule with DynamicModule {
  override lazy val millDiscover: Discover = Discover[this.type]

  /**
   * Orphan precompileds, instantiated lazily on first access of
   * [[moduleDirectChildren]]. The list comes from a classpath resource written
   * by `CodeGen` from the bootstrap walker (`BuildFileDiscovery.walkBuildFiles`);
   * runtime never re-walks the workspace.
   *
   * Cross-sibling `moduleDeps` refs are resolved by looking up the first
   * segment in a name → entry map shared with `PrecompiledModuleRef.cache`,
   * then walking remaining segments reflectively.
   */
  private lazy val orphanPrecompiledChildren: Seq[Module] = {
    val entries =
      PrecompiledModulesInfo.fromClasspath(classOf[DummyModule.type].getClassLoader)
    if (entries.isEmpty) Nil
    else {
      val workspaceRoot = BuildCtx.workspaceRoot
      // Map: top-level directory name (e.g. "foo" for "foo/package.mill.yaml")
      // → entry. CodeGen guarantees depth-1 entries only; the explicit filter
      // is defensive so a future widening of the resource format can't silently
      // corrupt the name lookup.
      val byFirstSegment: Map[String, PrecompiledModulesInfo.Entry] =
        entries
          .filter(e => os.SubPath(e.relPath).segments.size == 2)
          .map(e => os.SubPath(e.relPath).segments.head -> e)
          .toMap
      val parent: Module = this
      def instantiate(entry: PrecompiledModulesInfo.Entry): Module = {
        val absPath = workspaceRoot / os.SubPath(entry.relPath)
        def resolveSibling(ref: String, idx: Int): Module = {
          val parts = ref.split('.')
          val first = parts.head
          val siblingEntry = byFirstSegment.getOrElse(
            first, {
              val msg =
                s"Cannot resolve precompiled moduleDep '$ref' in ${entry.relPath}: " +
                  s"no top-level precompiled sibling '$first'"
              throw new mill.api.daemon.Result.Exception(
                msg,
                Some(mill.api.daemon.Result.Failure(msg, absPath.toNIO, idx))
              )
            }
          )
          val constructing = PrecompiledModuleRef.constructing.get()
          if (constructing.contains(siblingEntry.relPath)) {
            val chain = constructing.toArray.map(_.toString).toSeq
            val cycleDesc =
              if (chain.size <= 1) s"precompiled module '$ref' depends on itself"
              else s"circular moduleDeps: ${chain.mkString(" -> ")} -> ${siblingEntry.relPath}"
            val msg = s"Circular moduleDeps detected: $cycleDesc"
            throw new mill.api.daemon.Result.Exception(
              msg,
              Some(mill.api.daemon.Result.Failure(msg, absPath.toNIO, idx))
            )
          }
          val rootSibling = instantiate(siblingEntry)
          var current: Any = rootSibling
          for (seg <- parts.tail) {
            val cls = current.getClass
            val method =
              try cls.getMethod(seg)
              catch {
                case _: NoSuchMethodException =>
                  val msg =
                    s"Cannot resolve precompiled moduleDep '$ref' in ${entry.relPath}: " +
                      s"no method '$seg' on ${cls.getName}"
                  throw new mill.api.daemon.Result.Exception(
                    msg,
                    Some(mill.api.daemon.Result.Failure(msg, absPath.toNIO, idx))
                  )
              }
            current = method.invoke(current)
          }
          current.asInstanceOf[Module]
        }
        def depsMap(byKind: Map[String, Seq[(String, Int)]]): Map[String, Seq[Module]] =
          byKind.collect {
            case (k, refs) if refs.nonEmpty =>
              k -> refs.map { case (ref, idx) => resolveSibling(ref, idx) }
          }
        PrecompiledModuleRef(
          parent,
          entry.relPath,
          entry.extendsClass,
          () => depsMap(entry.moduleDeps),
          () => depsMap(entry.compileModuleDeps),
          () => depsMap(entry.runModuleDeps),
          () => depsMap(entry.bomModuleDeps)
        )
      }
      BuildCtx.withFilesystemCheckerDisabled {
        entries.map(instantiate)
      }
    }
  }

  override def moduleDirectChildren: Seq[Module] =
    super.moduleDirectChildren ++ orphanPrecompiledChildren
}

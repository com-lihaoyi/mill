package mill.javalib.spotless

import mill.api.TaskCtx
import mill.javalib.spotless._
import mill.javalib.CoursierModule

class SpotlessWorkerImpl(cleaners: Cleaners, git: GitRatchet) extends SpotlessWorker {

  def this(
      moduleDir: os.Path,
      globalExcludes: Seq[String],
      formats: Seq[Format],
      resolver: CoursierModule.Resolver,
      ctx: TaskCtx
  ) = this(
    Cleaners(moduleDir, globalExcludes, formats, toProvisioner(resolver)(using ctx)),
    GitRatchet(moduleDir)
  )

  def format(check: Boolean)(using ctx: TaskCtx.Log) = cleaners.format(check)

  def provision(using ctx: TaskCtx.Log) = cleaners.provision

  def ratchet(check: Boolean, staged: Boolean, from: String, to: Option[String])(using
      ctx: TaskCtx.Log & TaskCtx.Workspace
  ) = {
    val files = git.diff(staged, from, to)
    if (files.isEmpty) ctx.log.info("ratchet found no changes")
    else {
      ctx.log.info(s"ratchet found changes in ${files.length} files")
      cleaners.format(check, files)
    }
  }

  def close() = git.close()
}

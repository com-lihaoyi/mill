package mill.androidlib.bsp

import java.nio.file.Path

import mill.Task
import mill.api.daemon.internal.internal
import mill.api.{ModuleCtx, experimental}
import mill.androidlib.AndroidModule
import mill.javalib.bsp.BspModule
import mill.api.JsonFormatters.given

@experimental
trait BspAndroidModule extends mill.javalib.bsp.BspJavaModule {

  def javaModuleRef: mill.api.ModuleRef[AndroidModule & BspModule]

  override private[mill] def bspBuildTargetDependencySources
      : Task.Simple[(
          resolvedDepsSources: Seq[Path],
          unmanagedClasspath: Seq[Path]
      )] = Task {
    (
      resolvedDepsSources =
        javaModuleRef().androidUnpackedAarMvnDeps().flatMap(_.sourcesJar).map(_.path.toNIO),
      unmanagedClasspath = javaModuleRef().unmanagedClasspath().map(_.path.toNIO)
    )
  }

}

object BspAndroidModule {
  trait Wrap(jm0: AndroidModule & BspModule) extends mill.api.Module {
    override def moduleCtx: ModuleCtx = jm0.moduleCtx
    override protected[mill] implicit def moduleNestedCtx: ModuleCtx.Nested = jm0.moduleNestedCtx
    @internal
    object internalBspJavaModule extends BspAndroidModule {
      private[mill] def isScript = jm0.isScript
      def javaModuleRef = mill.api.ModuleRef(jm0)
    }
  }

}

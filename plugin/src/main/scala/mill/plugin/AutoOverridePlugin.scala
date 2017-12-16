package mill.plugin

import scala.reflect.internal.Flags
import scala.tools.nsc.io.VirtualFile
import scala.tools.nsc.util.BatchSourceFile
import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.{Plugin, PluginComponent}

class AutoOverridePlugin(val global: Global) extends Plugin {
  import global._
  override def init(options: List[String],  error: String => Unit): Boolean = true

  val name = "auto-override-plugin"
  val description = "automatically inserts `override` keywords for you"
  val components = List[PluginComponent](
    new PluginComponent {

      val global = AutoOverridePlugin.this.global
      import global._

      override val runsAfter = List("typer")
      override val runsBefore = List("patmat")

      val phaseName = "auto-override"

      override def newPhase(prev: Phase) = new GlobalPhase(prev) {

        def name: String = phaseName

        def isCacher(owner: Symbol) = {
          val baseClasses =
            if (owner.isClass) Some(owner.asClass.baseClasses)
            else if (owner.isModule) Some(owner.asModule.baseClasses)
            else None
          baseClasses.exists(_.exists(_.fullName == "mill.plugin.Cacher"))
        }

        def apply(unit: global.CompilationUnit): Unit = {
          object AutoOverrider extends global.Transformer {
            override def transform(tree: global.Tree) = tree match{
              case d: DefDef
                if d.symbol.overrideChain.count(!_.isAbstract) > 1
                && !d.mods.isOverride
                && isCacher(d.symbol.owner) =>

                  d.symbol.flags = d.symbol.flags | Flags.OVERRIDE
                  copyDefDef(d)(mods = d.mods | Flags.OVERRIDE)
              case _ => super.transform(tree)

            }
          }

          unit.body = AutoOverrider.transform(unit.body)
        }
      }
    }
  )
}
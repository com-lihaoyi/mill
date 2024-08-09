package mill.moduledefs

import scala.collection.mutable.ListBuffer
import scala.reflect.internal.Flags
import scala.tools.nsc.doc.ScaladocSyntaxAnalyzer
import scala.tools.nsc.plugins.{Plugin, PluginComponent}
import scala.tools.nsc.transform.Transform
import scala.tools.nsc.{Global, Phase}

class AutoOverridePlugin(val global: Global) extends Plugin {

  override def init(options: List[String], error: String => Unit): Boolean = true

  val name = "auto-override-plugin"
  val description = "automatically inserts `override` keywords for you"

  val components = List[PluginComponent](EnableScaladocAnnotation, AutoOverride)

  private object EnableScaladocAnnotation extends PluginComponent with Transform {
    type GT = AutoOverridePlugin.this.global.type

    override val global: GT = AutoOverridePlugin.this.global

    override val phaseName: String = "EmbedScaladocAnnotation"

    override val runsAfter: List[String] = List("parser")

    override def newTransformer(unit: global.CompilationUnit): global.Transformer = {
      new ScaladocTransformer
    }
    import global._

    class ScaladocTransformer extends global.Transformer {

      val comments = new Comments()

      override def transformUnit(unit: CompilationUnit) = {
        if (
          unit.source.file.name.endsWith(".scala") ||
          unit.source.file.name.endsWith(".sc")
        ) {
          comments.parseComments(unit)
          super.transformUnit(unit)
        }
      }

      override def transform(tree: global.Tree): global.Tree = {
        super.transform(tree match {
          case x: global.ClassDef =>
            comments.getComment(x.pos) match {
              case Some(comment) =>
                global.treeCopy.ClassDef(
                  tree,
                  newMods(false, x.mods, comment),
                  x.name,
                  x.tparams,
                  x.impl
                )
              case None => x
            }

          case x: global.ModuleDef =>
            comments.getComment(x.pos) match {
              case Some(comment) =>
                global.treeCopy.ModuleDef(tree, newMods(false, x.mods, comment), x.name, x.impl)
              case None => x
            }

          case x: global.DefDef =>
            global.treeCopy.DefDef(
              tree,
              newMods(x.vparamss.length == 0, x.mods, comments.getComment(x.pos).getOrElse("")),
              x.name,
              x.tparams,
              x.vparamss,
              x.tpt,
              x.rhs
            )

          case x => x
        })
      }

      def newMods(nullaryMethod: Boolean, old: global.Modifiers, comment: String) = {
        old.copy(
          annotations = createNullaryMethodAnnotation(nullaryMethod) ::: createAnnotation(comment) ::: old.annotations
        )
      }

      private def createNullaryMethodAnnotation(nullaryMethod: Boolean): List[global.Tree] = {
        if (!nullaryMethod) Nil
        else List{
          global.Apply(
            global.Select(
              global.New(
                AutoOverridePlugin.nullaryMethodAnnotationClassNameTree(global)
              ),
              global.nme.CONSTRUCTOR
            ),
            List()
          )
        }
      }


      private def createAnnotation(comment: String): List[global.Tree] = {
        if (comment == "") Nil
        else List{
          global.Apply(
            global.Select(
              global.New(
                AutoOverridePlugin.scaladocAnnotationClassNameTree(global)
              ),
              global.nme.CONSTRUCTOR
            ),
            List(Literal(Constant(comment)))
          )
        }
      }

    }

    class Comments extends ScaladocSyntaxAnalyzer[global.type](global) {
      val comments = ListBuffer[(Position, String)]()

      def getComment(pos: Position): Option[String] = {
        val tookComments = comments.takeWhile { case (x, _) => x.end < pos.start }
        comments --= (tookComments)
        tookComments.lastOption.map(_._2)
      }

      def parseComments(unit: CompilationUnit): Unit = {
        comments.clear()

        new ScaladocUnitParser(unit, Nil) {
          override def newScanner = new ScaladocUnitScanner(unit, Nil) {
            override def registerDocComment(str: String, pos: Position) = {
              comments += ((pos, str))
            }
          }
        }.parse()
      }

      override val runsAfter: List[String] = Nil
      override val runsRightAfter: Option[String] = None
    }
  }

  private object AutoOverride extends PluginComponent {

    val global = AutoOverridePlugin.this.global
    import global._

    override val runsAfter = List("typer")
    override val runsBefore = List("patmat")

    val phaseName = "auto-override"

    override def newPhase(prev: Phase): Phase = new GlobalPhase(prev) {

      def name: String = phaseName

      def isCacher(owner: Symbol) = {
        val baseClasses =
          if (owner.isClass) Some(owner.asClass.baseClasses)
          else if (owner.isModule) Some(owner.asModule.baseClasses)
          else None
        baseClasses.exists(_.exists(_.fullName == AutoOverridePlugin.cacherClassName))
      }

      def apply(unit: global.CompilationUnit): Unit = {
        object AutoOverrider extends global.Transformer {
          override def transform(tree: global.Tree) = tree match {
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

}

object AutoOverridePlugin {

  private val cacherClassName = "mill.moduledefs.Cacher"

  private def scaladocAnnotationClassNameTree(global: Global) =
    global.Select(
      global.Select(
        global.Ident(
          global.newTermName("mill")
        ),
        global.newTermName("moduledefs")
      ),
      global.newTypeName("Scaladoc")
    )
  private def nullaryMethodAnnotationClassNameTree(global: Global) =
    global.Select(
      global.Select(
        global.Ident(
          global.newTermName("mill")
        ),
        global.newTermName("moduledefs")
      ),
      global.newTypeName("NullaryMethod")
    )
}

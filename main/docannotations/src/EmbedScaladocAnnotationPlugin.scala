
package mill.docannotations


import scala.tools.nsc
import nsc.{Global, Phase}
import nsc.plugins.Plugin
import nsc.plugins.PluginComponent
import scala.collection.mutable.ListBuffer
import scala.tools.nsc.doc.ScaladocSyntaxAnalyzer
import scala.tools.nsc.transform.Transform

class EmbedScaladocAnnotationPlugin(val global: Global) extends Plugin {
  override val name: String = "EmbedScaladocAnnotation"
  override val description: String = ""
  override val components: List[PluginComponent] = List[PluginComponent](MyComponent)

  private object MyComponent extends PluginComponent with Transform {
    type GT = EmbedScaladocAnnotationPlugin.this.global.type
    override val global: GT = EmbedScaladocAnnotationPlugin.this.global
    override val phaseName: String = "EmbedScaladocAnnotation"
    override val runsAfter: List[String] = List("parser")
    override def newTransformer(unit: global.CompilationUnit): global.Transformer = {
      new ScaladocTransformer
    }
    import global._


    class ScaladocTransformer extends global.Transformer {

      val comments = new Comments()

      override def transformUnit(unit: CompilationUnit)= {
        if (unit.source.file.name.endsWith(".scala") ||
            unit.source.file.name.endsWith(".sc")){
          comments.parseComments(unit)
          super.transformUnit(unit)
        }
      }

      override def transform(tree: global.Tree): global.Tree = {
        super.transform(tree match {
          case x: global.ClassDef =>
            comments.getComment(x.pos) match {
              case Some(comment) =>
                global.treeCopy.ClassDef(tree, newMods(x.mods, comment), x.name, x.tparams, x.impl)
              case None => x
            }

          case x: global.ModuleDef =>
            comments.getComment(x.pos) match {
              case Some(comment) =>
                global.treeCopy.ModuleDef(tree, newMods(x.mods, comment), x.name, x.impl)
              case None => x
            }

          case x: global.DefDef =>
            comments.getComment(x.pos) match {
              case Some(comment) =>
                global.treeCopy.DefDef(tree, newMods(x.mods, comment), x.name, x.tparams, x.vparamss, x.tpt, x.rhs)
              case None => x
            }

          case x: global.ValDef =>
            comments.getComment(x.pos) match {
              case Some(comment) =>
                global.treeCopy.ValDef(tree, newMods(x.mods, comment), x.name, x.tpt, x.rhs)
              case None => x
            }

          case x => x
        })
      }

      def newMods(old: global.Modifiers, comment: String) = {
        old.copy(
          annotations = createAnnotation(comment) :: old.annotations
        )
      }

      private def createAnnotation(comment: String): global.Tree =
        global.Apply(
          global.Select(
            global.New(
              global.Select(
                global.Select(
                  global.Ident(
                    global.newTermName("mill")
                  ),
                  global.newTermName("docannotations")
                ),
                global.newTypeName("Scaladoc")
              )
            ),
            global.nme.CONSTRUCTOR
          ),
          List(Literal(Constant(comment)))
        )

    }

    class Comments extends ScaladocSyntaxAnalyzer[global.type](global){
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
}
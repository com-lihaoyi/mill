package mill.define

import scala.quoted.*
import scala.annotation.compileTimeOnly
import java.util.ServiceLoader
import scala.annotation.experimental

trait ShimService[Q <: Quotes] {
  val innerQuotes: Q
  import innerQuotes.reflect.*

  val Symbol: SymbolModule
  trait SymbolModule { self: Symbol.type =>
    def newClass(
        parent: Symbol,
        name: String,
        parents: List[TypeRepr],
        ctor: Symbol => (List[String], List[TypeRepr]),
        decls: Symbol => List[Symbol],
        selfType: Option[TypeRepr]
    ): Symbol
  }

  val ClassDef: ClassDefModule
  trait ClassDefModule { self: ClassDef.type =>
    def apply(
        cls: Symbol,
        parents: List[Tree /* Term | TypeTree */ ],
        body: List[Statement]
    ): ClassDef
  }

}

object ShimService {
  import scala.quoted.runtime.impl.QuotesImpl

  def reflect(using Quotes): ShimService[quotes.type] =
    val cls = Class.forName("mill.define.ShimService$ShimServiceImpl")
    cls.getDeclaredConstructor(classOf[Quotes]).newInstance(summon[Quotes]).asInstanceOf[
      ShimService[quotes.type]
    ]

  private class DottyInternal(val quotes: QuotesImpl) {
    import dotty.tools.dotc
    import dotty.tools.dotc.ast.tpd.Tree
    import dotty.tools.dotc.ast.untpd
    import dotty.tools.dotc.ast.tpd
    import dotty.tools.dotc.core.StdNames.nme
    import dotty.tools.dotc.core.Contexts.Context
    import dotty.tools.dotc.core.Contexts.ctx
    import dotty.tools.dotc.core.Types
    import quotes.reflect.TypeRepr
    import quotes.reflect.Tree
    import quotes.reflect.DefDef
    import quotes.reflect.Statement
    import quotes.reflect.ClassDef
    import quotes.reflect.Symbol
    import quotes.reflect.Flags
    import quotes.reflect.Position
    import dotty.tools.dotc.core.Decorators.*

    given Context = quotes.ctx

    def newClass(
        owner: Symbol,
        name: String,
        parents: List[TypeRepr],
        ctor: Symbol => (List[String], List[TypeRepr]),
        decls: Symbol => List[Symbol],
        selfType: Option[TypeRepr]
    ): Symbol = {
      assert(
        parents.nonEmpty && !parents.head.typeSymbol.is(dotc.core.Flags.Trait),
        "First parent must be a class"
      )
      val cls = dotc.core.Symbols.newNormalizedClassSymbol(
        owner,
        name.toTypeName,
        dotc.core.Flags.EmptyFlags,
        parents,
        selfType.getOrElse(Types.NoType),
        dotc.core.Symbols.NoSymbol
      )
      val (names, argTpes) = ctor(cls)
      cls.enter(dotc.core.Symbols.newConstructor(
        cls,
        dotc.core.Flags.Synthetic,
        names.map(_.toTermName),
        argTpes
      ))
      for sym <- decls(cls) do cls.enter(sym)
      cls
    }

    def ClassDef_apply(cls: Symbol, parents: List[Tree], body: List[Statement]): ClassDef = {
      val ctor = quotes.reflect.DefDef.apply(cls.primaryConstructor, _ => None)
      tpd.ClassDefWithParents(cls.asClass, ctor, parents, body)
    }

    private def withDefaultPos[T <: Tree](fn: Context ?=> T): T =
      fn(using ctx.withSource(Position.ofMacroExpansion.source)).withSpan(
        Position.ofMacroExpansion.span
      )
  }

  @experimental
  private class ShimServiceImpl[Q <: Quotes](override val innerQuotes: Q) extends ShimService[Q] {
    import innerQuotes.reflect.*

    val internal = DottyInternal(innerQuotes.asInstanceOf[QuotesImpl])

    import internal.quotes.reflect as ir

    object Symbol extends SymbolModule {
      override def newClass(
          parent: Symbol,
          name: String,
          parents: List[TypeRepr],
          ctor: Symbol => (List[String], List[TypeRepr]),
          decls: Symbol => List[Symbol],
          selfType: Option[TypeRepr]
      ): Symbol = {
        internal.newClass(
          owner = parent.asInstanceOf[ir.Symbol],
          name = name,
          parents = parents.asInstanceOf[List[ir.TypeRepr]],
          ctor = ctor.asInstanceOf[ir.Symbol => (List[String], List[ir.TypeRepr])],
          decls = decls.asInstanceOf[ir.Symbol => List[ir.Symbol]],
          selfType = selfType.asInstanceOf[Option[ir.TypeRepr]]
        ).asInstanceOf[Symbol]
      }
    }

    object ClassDef extends ClassDefModule {
      override def apply(cls: Symbol, parents: List[Tree], body: List[Statement]): ClassDef =
        internal.ClassDef_apply(
          cls = cls.asInstanceOf[ir.Symbol],
          parents = parents.asInstanceOf[List[ir.Tree]],
          body = body.asInstanceOf[List[ir.Statement]]
        ).asInstanceOf[ClassDef]
    }
  }
}

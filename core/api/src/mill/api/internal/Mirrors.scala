package mill.api.internal

import mill.api.daemon.internal.internal

import scala.deriving.Mirror
import scala.quoted.*

@internal
private[mill] object Mirrors {

  /** A store for one or more mirrors, with Root type `R`. */
  sealed trait Root[R] {
    def mirror[T <: R](key: Path[R, T]): Mirror.Of[T]
  }

  /** Proof that `T` has a synthetic Mirror in `Root[R]` */
  opaque type Path[R, T <: R] <: String =
    String // no constructor, macro will validate and cast string

  /**
   * generate mirrors for a sealed hierarchy, or a single class. Only optimized for what Mill needs.
   * Does not synthesize mirrors for case classes, or values already extending Mirror.Singleton.
   *
   * This means you pay for only what is needed.
   *
   * Limitations (could be lifted later, but not needed for Mill currently):
   * - sealed children must be static, i.e. no inner classes or local classes.
   * - "product" types should not have type parameters.
   *
   * Note: this is not given, so must be explicitly called, which allows to control caching.
   */
  inline def autoRoot[T]: Root[T] = ${ Internal.autoRootImpl[T] }

  /**
   * given a `Root[R]`, and a `Path[R, T]`, retrieve a `Mirror.Of[T]`, and cast it with correct refinements.
   * Note that this must be transparent inline to add path-dependent types,
   * therefore to avoid large bytecode blowup it only generates a single method call.
   */
  transparent inline given autoMirror[R, T <: R](using
      inline h: Root[R],
      inline p: Path[R, T]
  ): Mirror.Of[T] =
    ${ Internal.autoMirrorImpl[R, T]('h, 'p) }

  /** Try to synthesize a proof that `T` has a synthetic Mirror inside of `Root[R]`. */
  transparent inline given autoPath[R, T <: R]: Path[R, T] =
    ${ Internal.autoPathImpl[R, T] }

  def makeRoot[T](ms: Map[String, Mirror]): Root[T] = new Internal.Rooted(ms)

  final class AutoSum[T, L <: String, Ns <: Tuple, Ts <: Tuple](ord: T => Int) extends Mirror.Sum {
    type MirroredType = T
    type MirroredMonoType = T
    type MirroredLabel = L
    type MirroredElemLabels = Ns
    type MirroredElemTypes = Ts

    override def ordinal(p: T): Int = ord(p)
  }

  final class AutoProduct[T, L <: String, Ns <: Tuple, Ts <: Tuple](from: Product => T)
      extends Mirror.Product {
    type MirroredType = T
    type MirroredMonoType = T
    type MirroredLabel = L
    type MirroredElemLabels = Ns
    type MirroredElemTypes = Ts

    override def fromProduct(p: Product): T = from(p)
  }

  private object Internal {

    private[Mirrors] final class Rooted[R](ms: Map[String, Mirror]) extends Root[R] {
      final def mirror[T <: R](path: Path[R, T]): Mirror.Of[T] = {
        ms(path).asInstanceOf[Mirror.Of[T]] // key is "proof" that lookup is safe
      }
    }

    /** Given the root, and proof that `T` should be in `h`, generate expression that performs lookup, then cast. */
    def autoMirrorImpl[R: Type, T <: R: Type](h: Expr[Root[R]], p: Expr[Path[R, T]])(using
        Quotes
    ): Expr[Mirror.Of[T]] = {
      import quotes.reflect.*

      val (_, kind) = symKind[T]
      val rawLookup = '{ $h.mirror($p) }
      kind match
        case MirrorKind.Product =>
          castProductImpl[T](rawLookup)
        case MirrorKind.SingletonProxy =>
          '{ $rawLookup.asInstanceOf[Mirror.SingletonProxy & Mirror.ProductOf[T]] }
        case MirrorKind.Sum =>
          castSumMirror[T](rawLookup)
        case _ =>
          report.errorAndAbort(
            s"Can't cast mirror expression ${rawLookup.show} for ${Type.show[T]}: ${kind}"
          )
    }

    /**
     * Compare the kind of `R` and the kind of `T`. If `T` will be a member of `Root[R]` then return a Path.
     * This helps the compiler to try its own synthesis, e.g. for mixes of case class and class in a sum type.
     */
    def autoPathImpl[R: Type, T <: R: Type](using Quotes): Expr[Path[R, T]] = {
      import quotes.reflect.*

      val (root, rootKind) = symKind[R]
      val (sym, tpeKind) = symKind[T]

      if (rootKind != MirrorKind.Sum || tpeKind == MirrorKind.Sum) && root != sym then {
        // only sum can be looked inside, and sum can not be a member of another sum (could be relaxed later)
        report.errorAndAbort(s"Can't cast ${sym.name} to ${root.name}")
      }

      def static = isStatic(sym)
      def isSingletonProxy = tpeKind == MirrorKind.SingletonProxy
      def isSum = tpeKind == MirrorKind.Sum
      def isProduct =
        tpeKind == MirrorKind.Product && !ignoreCaseClass(sym) && sym.primaryConstructor.paramSymss
          .flatten.forall(
            _.isTerm
          )

      if rootKind == MirrorKind.Sum && !(isSum || static && (isSingletonProxy || isProduct)) then
        report.errorAndAbort(
          s"Don't know how to lookup ${sym.name}: ${tpeKind} from ${root.name}: ${rootKind}"
        )

      '{ ${ Expr(sym.name) }.asInstanceOf[Path[R, T]] } // safe cast
    }

    enum MirrorKind {
      case Sum, Singleton, SingletonProxy, Product
    }

    /** determine the MirrorKind of a type, and return its symbol. */
    def symKind[T: Type](using Quotes): (quotes.reflect.Symbol, MirrorKind) = {
      import quotes.reflect.*

      val tpe = TypeRepr.of[T]
      val sym =
        if tpe.termSymbol.exists then
          tpe.termSymbol
        else
          tpe.classSymbol.get
      sym -> symKindSym(sym)
    }

    /** determine the MirrorKind of a symbol. */
    def symKindSym(using Quotes)(sym: quotes.reflect.Symbol): MirrorKind = {
      import quotes.reflect.*

      if sym.isTerm then
        if sym.termRef <:< TypeRepr.of[Mirror.Singleton] then MirrorKind.Singleton
        else MirrorKind.SingletonProxy
      else
        val cls = sym.ensuring(_.isClassDef)
        if cls.flags.is(Flags.Module) then
          if cls.typeRef <:< TypeRepr.of[Mirror.Singleton] then MirrorKind.Singleton
          else MirrorKind.SingletonProxy
        else if cls.flags.is(Flags.Case) then MirrorKind.Product
        else if cls.flags.is(Flags.Sealed) && (cls.flags.is(Flags.Trait) || cls.flags.is(
            Flags.Abstract
          ) && !cls.primaryConstructor.paramSymss.flatten.exists(_.isTerm))
        then
          MirrorKind.Sum
        else
          MirrorKind.Product
    }

    def ignoreCaseClass(using Quotes)(cls: quotes.reflect.Symbol): Boolean = {
      import quotes.reflect.*

      cls.flags.is(Flags.Case) && !(cls.typeRef <:< TypeRepr.of[AnyVal])
    }

    /**
     * Generate a Mirror hierarchy 1 level deep, i.e. for a Sum type, the root will contain itself,
     *  and any child type Mirrors. Do not synthesizes Mirrors for case classes, or values already extending Mirror.Singleton.
     */
    def autoRootImpl[T: Type](using Quotes): Expr[Root[T]] = {
      import quotes.reflect.*

      val (sym, kind) = symKind[T]

      kind match {
        case MirrorKind.Sum if sym.isType =>
          val sumInners = autoSumImpl[T]
          val inner = Varargs(sumInners.toList.map((k, v) => '{ ${ Expr(k) } -> $v }))
          '{ makeRoot[T](Map($inner*)) }
        case MirrorKind.Product if !sym.isTerm =>
          if ignoreCaseClass(sym) then
            report.errorAndAbort(
              s"Root[${Type.show[T]}] should not be generated for case class ${sym.name}"
            )
          val (key, mirror) = autoProductImpl[T]
          '{ makeRoot[T](Map(${ Expr(key) } -> $mirror)) }
        case _ =>
          report.errorAndAbort(s"Can not generate Root[${Type.show[T]}]")
      }
    }

    trait Structure[Q <: Quotes] {
      val innerQuotes: Q

      def clsName: String
      def paramNames: List[String]
      def paramTypes: List[innerQuotes.reflect.TypeRepr]

      def reflect[T](f: ((Type[?], Type[?], Type[?])) => Q ?=> T): T = {
        given innerQuotes.type = innerQuotes
        val labelType = stringAsType(clsName).asType
        val namesType = typesAsTuple(paramNames.map(stringAsType)).asType
        val elemsType = typesAsTuple(paramTypes).asType
        f((labelType, namesType, elemsType))
      }
    }

    final class ProductStructure[Q <: Quotes](using val innerQuotes: Q)(
        val clsName: String,
        val paramNames: List[String],
        val paramTypes: List[innerQuotes.reflect.TypeRepr],
        val applyMethod: innerQuotes.reflect.Symbol,
        val companion: innerQuotes.reflect.Symbol
    ) extends Structure[Q]

    final class SumStructure[Q <: Quotes](using val innerQuotes: Q)(
        val clsName: String,
        val paramNames: List[String],
        val paramTypes: List[innerQuotes.reflect.TypeRepr],
        val cases: List[(innerQuotes.reflect.Symbol, MirrorKind)]
    ) extends Structure[Q]

    def productClassStructure[T: Type](using Quotes): ProductStructure[quotes.type] = {
      import quotes.reflect.*

      val cls = TypeRepr.of[T].classSymbol.get // already validated in autoRootImpl

      val clsName = cls.name

      val companion = cls.companionModule

      def extractPrefix(tpe: TypeRepr): TypeRepr = tpe match {
        case TermRef(pre, _) => pre
        case TypeRef(pre, _) => pre
        case AppliedType(clsRef, _) => extractPrefix(clsRef)
        case ThisType(clsRef) => extractPrefix(clsRef)
        case AnnotatedType(underlying, _) => extractPrefix(underlying)
        case Refinement(parent, _, _) => extractPrefix(parent)
        case RecursiveType(parent) => extractPrefix(parent)
        case _ => report.errorAndAbort(s"unexpected type ${Type.show[T]}, can't extract prefix")
      }

      val prefix = extractPrefix(TypeRepr.of[T].widen)

      val companionTpe = prefix.memberType(companion)

      val ctor = cls.primaryConstructor
      val paramsTarget = ctor.paramSymss match
        case Seq(params) if params.forall(_.isTerm) => params
        case _ => report.errorAndAbort(
            s"Expected primary constructor of ${clsName} to have a single parameter list"
          )

      val applyMethod = companion.methodMember("apply").filter(sym =>
        sym.paramSymss.sizeIs == 1 && sym.paramSymss.head.corresponds(paramsTarget) { (a, b) =>
          a.name == b.name && a.termRef.widen =:= b.termRef.widen
        }
      ) match
        case apply :: Nil => apply
        case _ =>
          report.errorAndAbort(s"unable to find/disambiguate apply method of ${companionTpe.show}")

      val params = applyMethod.paramSymss match
        case Seq(params) if params.isEmpty || params.head.isTerm => params
        case _ => report.errorAndAbort(
            s"Expected apply method of ${companionTpe.show} to have a single parameter list"
          )

      val applyTpe = companionTpe.memberType(applyMethod)
      val (paramNames, paramTypes) = params.map { param =>
        val name = param.name
        val tpe = applyTpe.memberType(param)
        (name, tpe)
      }.unzip

      ProductStructure(clsName, paramNames, paramTypes, applyMethod, companion)
    }

    def stringAsType(s: String)(using Quotes): quotes.reflect.TypeRepr = {
      import quotes.reflect.*
      ConstantType(StringConstant(s))
    }

    def typesAsTuple(using Quotes)(ts: List[quotes.reflect.TypeRepr]): quotes.reflect.TypeRepr = {
      import quotes.reflect.*
      ts.foldRight(TypeRepr.of[EmptyTuple])((t, acc) =>
        (t.asType, acc.asType) match
          case (
                '[t],
                '[
                type ts <: Tuple; `ts`]
              ) => TypeRepr.of[t *: ts]
          case other => throw Exception(s"unexpected: $other")
      )
    }

    def isStatic(using Quotes)(sym: quotes.reflect.Symbol): Boolean = {
      import quotes.reflect.*

      def hasStaticOwner(sym: Symbol): Boolean =
        !sym.maybeOwner.exists
          || sym.owner.flags.is(Flags.Package)
          || sym.owner.flags.is(Flags.Module) && hasStaticOwner(sym.owner)

      hasStaticOwner(sym)
    }

    def sumStructure[T: Type](using Quotes): SumStructure[quotes.type] = {
      import quotes.reflect.*

      val cls = TypeRepr.of[T].classSymbol.get // already validated in autoRootImpl

      val sumCases = cls.children.map(child => child -> symKindSym(child))
      if !sumCases.forall((sym, _) => isStatic(sym)) then
        report.errorAndAbort(s"Sum type ${cls.name} must have all static children")

      val (caseNames, caseTypes) = sumCases.map((sym, _) =>
        (sym.name, if sym.isTerm then sym.termRef else sym.typeRef)
      ).unzip

      SumStructure(cls.name, caseNames, caseTypes, sumCases)
    }

    def castProductImpl[T: Type](m: Expr[Mirror.Of[T]])(using Quotes): Expr[Mirror.ProductOf[T]] = {
      productClassStructure[T].reflect {
        case (
              '[
              type label <: String; `label`],
              '[
              type names <: Tuple; `names`],
              '[
              type types <: Tuple; `types`]
            ) => '{
            $m.asInstanceOf[
              Mirror.ProductOf[T] {
                type MirroredLabel = label
                type MirroredElemTypes = types
                type MirroredElemLabels = names
              }
            ]
          }

        case other => throw Exception(s"unexpected: $other")
      }
    }

    def castSumMirror[T: Type](m: Expr[Mirror.Of[T]])(using Quotes): Expr[Mirror.SumOf[T]] = {
      sumStructure[T].reflect {
        case (
              '[
              type label <: String; `label`],
              '[
              type names <: Tuple; `names`],
              '[
              type types <: Tuple; `types`]
            ) => '{
            $m.asInstanceOf[Mirror.SumOf[T] {
              type MirroredLabel = label
              type MirroredElemTypes = types
              type MirroredElemLabels = names
            }]
          }

        case other => throw Exception(s"unexpected: $other")
      }
    }

    def autoSumImpl[T: Type](using Quotes): Map[String, Expr[Mirror]] = {
      import quotes.reflect.*

      val structure = sumStructure[T]

      def ordinalBody(arg: Expr[T]): Expr[Int] = {
        // This must consider "all" cases, even if we do not generate mirrors for them.
        val cases = structure.cases.zipWithIndex.map {
          case ((child, MirrorKind.Product | MirrorKind.Sum), i) =>
            if child.paramSymss.flatten.exists(_.isType) then
              report.errorAndAbort(
                s"Unexpected case in sum type ${structure.clsName} with type parameters"
              )
            CaseDef(TypedOrTest(Wildcard(), TypeTree.ref(child)), None, Literal(IntConstant(i)))
          case ((child, MirrorKind.SingletonProxy | MirrorKind.Singleton), i) =>
            CaseDef(Ref(child), None, Literal(IntConstant(i)))
        }
        val defaultCase = CaseDef(
          Wildcard(),
          None,
          '{ throw new IllegalArgumentException(s"Unknown argument ${$arg}") }.asTerm
        )
        val matchExpr = Match(arg.asTerm, cases :+ defaultCase)
        matchExpr.asExprOf[Int]
      }

      // These are the internal mirrors that we will actually generate.
      // Note that there is no support for sum-child of a sum (could be thought about when necessary)
      // ensure that this matches the validation logic in autoPathImpl
      val innerMirrors = structure.cases.collect({
        case (child, MirrorKind.SingletonProxy) =>
          val mirror = '{
            new Mirror.SingletonProxy(${ Ref(child.companionModule).asExprOf[AnyRef] })
          }
          (child.name, mirror)
        case (child, MirrorKind.Product)
            if !ignoreCaseClass(child) && child.paramSymss.flatten.forall(_.isTerm) =>
          val childTpe = child.typeRef
          childTpe.asType match
            case '[t] =>
              autoProductImpl[t]
      }).toMap

      val sumMirror: Expr[Mirror.SumOf[T]] = structure.reflect {
        case (
              '[
              type label <: String; `label`],
              '[
              type names <: Tuple; `names`],
              '[
              type types <: Tuple; `types`]
            ) => '{ AutoSum[T, label, names, types]((arg: T) => ${ ordinalBody('arg) }) }

        case other => throw Exception(s"unexpected: $other")
      }

      innerMirrors + (structure.clsName -> sumMirror)
    }

    def autoProductImpl[T: Type](using Quotes): (String, Expr[Mirror.ProductOf[T]]) = {
      import quotes.reflect.*

      val structure = productClassStructure[T]

      def applyCall(arg: Expr[Product]): Expr[T] = {
        val typedArgs = structure.paramTypes.zipWithIndex.map((tpe, i) =>
          tpe.asType match
            case '[t] =>
              '{ $arg.productElement(${ Expr(i) }).asInstanceOf[t] }.asTerm

            case other => throw Exception(s"unexpected: $other")
        )
        Ref(structure.companion)
          .select(structure.applyMethod)
          .appliedToArgs(typedArgs)
          .asExprOf[T]
      }

      val mirror: Expr[Mirror.ProductOf[T]] = structure.reflect {
        case (
              '[
              type label <: String; `label`],
              '[
              type names <: Tuple; `names`],
              '[
              type types <: Tuple; `types`]
            ) => '{ AutoProduct[T, label, names, types](p => ${ applyCall('p) }) }
        case other => throw Exception(s"unexpected: $other")
      }
      structure.clsName -> mirror
    }
  }
}

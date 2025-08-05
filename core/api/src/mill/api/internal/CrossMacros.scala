package mill.api.internal

import mill.api.Cross.*

import scala.quoted.*

private[mill] object CrossMacros {
  def makeImpl[T: Type](using Quotes)(t: Expr[Any]): Expr[Factory[T]] = {
    import quotes.reflect.*

    val shims = ShimService.reflect

    val tpe = TypeRepr.of[T]

    val cls = tpe.classSymbol.getOrElse(
      report.errorAndAbort(s"Cross type ${tpe.show} must be trait", Position.ofMacroExpansion)
    )

    if (!cls.flags.is(Flags.Trait)) abortOldStyleClass(tpe)

    val wrappedT: Expr[Seq[Any]] = t match
      case '{ $t1: Seq[elems] } => t1
      case '{ $t1: t1 } => '{ Seq.apply($t1) }

    def crossName(n: Int): String = s"crossValue${if n > 0 then (n + 1).toString else ""}"

    val elems0: Type[?] = t match {
      case '{ ${ _ }: Seq[elems] } => TypeRepr.of[elems].widen.asType
      case '{ ${ _ }: elems } => TypeRepr.of[elems].widen.asType
    }
    def tupleToList[T: Type](acc: List[Type[?]]): List[Type[?]] = Type.of[T] match {
      case '[t *: ts] => tupleToList[ts](Type.of[t] :: acc)
      case '[EmptyTuple] => acc.reverse
    }

    lazy val (elemsStr, posStr) = elems0 match {
      case '[
          type elems1 <: NonEmptyTuple; `elems1`] =>
        (
          tupleToList[elems1](Nil).map({ case '[t] => Type.show[t] }).mkString("(", ", ", ")"),
          (n: Int) => s" at index $n"
        )
      case '[elems1] =>
        (
          Type.show[elems1],
          (_: Int) => ""
        )
    }
    val elemTypes: (Expr[Seq[Seq[Any]]], Seq[(Type[?], (Expr[?], Type[?]) => Expr[?])]) = {
      def select[E: Type](n: Int): (Expr[?], Type[?]) => Expr[?] = {
        def check(tpe: Type[?])(expr: => Expr[E]) = tpe match {
          case '[e0] =>
            if TypeRepr.of[E] <:< TypeRepr.of[e0] then
              expr
            else
              '{ ??? : e0 } // We will have already reported an error so we can return a placeholder
        }
        elems0 match {
          case '[
              type elems1 <: NonEmptyTuple; `elems1`] =>
            (arg, tpe) =>
              arg match {
                case '{ $arg: `elems1` } => check(tpe)('{ $arg.apply(${ Expr(n) }) }.asExprOf[E])
              }
          case '[elems1] =>
            require(n == 0, "non-tuple type should only have 1 element")
            (arg, tpe) => check(tpe)(arg.asExprOf[E])
        }
      }
      def asSeq(tpe: Type[?], n: Int): Seq[(Type[?], (Expr[?], Type[?]) => Expr[?])] = tpe match {
        case '[e *: es] => (Type.of[e], select[e](n)) +: asSeq(Type.of[es], n + 1)
        case '[EmptyTuple] => Nil
      }
      elems0 match {
        case '[
            type elems <: Tuple; `elems`] =>
          val wrappedElems = wrappedT.asExprOf[Seq[elems]]
          (
            '{ $wrappedElems.map(_.productIterator.toList) },
            asSeq(elems0, 0)
          )
        case '[t] =>
          (
            '{ $wrappedT.map(List(_)) },
            List((Type.of[t], select[t](0)))
          )
      }
    }

    def exPair(n: Int): (Type[?], (Expr[?], Type[?]) => Expr[?]) = {
      elemTypes(1).lift(n).getOrElse(
        report.errorAndAbort(
          s"expected at least ${n + 1} elements, got ${elemTypes(1).size}",
          Position.ofMacroExpansion
        )
      )
    }

    val typeErrors = Map.newBuilder[Int, TypeRepr]

    def exType[E: Type](n: Int): TypeRepr = {
      val (elemType, _) = exPair(n)
      elemType match
        case '[t] =>
          val tRepr = TypeRepr.of[t]
          if tRepr <:< TypeRepr.of[E] then
            tRepr
          else
            typeErrors += n -> TypeRepr.of[E]
            TypeRepr.of[E]
    }

    def exTerm[E](n: Int)(using Type[E]): Expr[?] => Expr[?] = {
      val f0 = exPair(n)(1)
      arg => f0(arg, Type.of[E])
    }

    def mkSegmentsCall[T: Type](t: Expr[T]): Expr[List[String]] = {
      import quotes.reflect.*

      val summonCall = Expr.summon[ToSegments[T]].getOrElse(
        report.errorAndAbort(
          s"Could not summon ToSegments[${TypeRepr.of[T].widen.show}]",
          Position.ofMacroExpansion
        )
      )
      '{ mill.api.Cross.ToSegments[T]($t)(using $summonCall) }
    }

    def mkSegmentsCallN[E: Type](n: Int)(arg: Expr[?]): Expr[List[String]] = {
      exTerm[E](n)(arg) match {
        case '{ $v1: t1 } => mkSegmentsCall[t1](v1)
      }
    }

    def newGetter(name: String, res: TypeRepr, flags: Flags = Flags.Override): Symbol => Symbol =
      cls =>
        Symbol.newMethod(
          parent = cls,
          name = name,
          tpe = ByNameType(res),
          flags = flags,
          privateWithin = Symbol.noSymbol
        )
    def newField(name: String, res: TypeRepr, flags: Flags): Symbol => Symbol =
      cls =>
        Symbol.newVal(
          parent = cls,
          name = name,
          tpe = res,
          flags = flags,
          privateWithin = Symbol.noSymbol
        )

    def newGetterTree(name: String, rhs: Expr[?] => Expr[?]): (Symbol, Expr[?]) => Statement = {
      (cls, arg) =>
        val sym = cls.declaredMethod(name)
          .headOption
          .getOrElse(report.errorAndAbort(
            s"could not find method $name in $cls",
            Position.ofMacroExpansion
          ))
        DefDef(sym, _ => Some(rhs(arg).asTerm))
    }

    def newValTree(name: String, rhs: Option[Term]): (Symbol, Expr[?]) => Statement = {
      (cls, _) =>
        val sym = {
          val sym0 = cls.declaredField(name)
          if sym0 != Symbol.noSymbol then sym0
          else
            report.errorAndAbort(s"could not find field $name in $cls", Position.ofMacroExpansion)
        }
        ValDef(sym, rhs)
    }

    extension (sym: Symbol) {
      def mkRef(debug: => String): Ref = {
        if sym.isTerm then
          Ref(sym)
        else
          report.errorAndAbort(s"could not ref ${debug}, it was not a term")
      }
    }

    val newSyms = List.newBuilder[Symbol => Symbol]
    val newTrees = collection.mutable.Buffer.empty[(Symbol, Expr[?]) => Statement]
    val valuesTree: Expr[Seq[Seq[Any]]] = elemTypes(0)
    val pathSegmentsTrees = List.newBuilder[Expr[?] => Expr[List[String]]]

    def pushElemTrees[E: Type](n: Int): Unit = {
      val name = crossName(n)
      newSyms += newGetter(name, res = exType[E](n))
      newTrees += newGetterTree(name, rhs = exTerm[E](n))
      pathSegmentsTrees += mkSegmentsCallN[E](n)
    }

    newSyms += newField(
      "local_ctx",
      res = TypeRepr.of[mill.api.ModuleCtx],
      flags = Flags.PrivateLocal | Flags.ParamAccessor
    )

    newTrees += newValTree("local_ctx", rhs = None)

    def inspect[T: Type](pf: PartialFunction[Type[T], Unit]): Unit = {
      pf.applyOrElse(Type.of[T], _ => ())
    }

    inspect[T] {
      case '[Module[e0]] =>
        pushElemTrees[e0](0)
      case _ =>
        report.errorAndAbort(
          s"Cross type ${tpe.show} must implement Cross.Module[T]",
          Position.ofMacroExpansion
        )
    }

    inspect[T] {
      case '[Module2[?, e1]] => pushElemTrees[e1](1)
    }

    inspect[T] {
      case '[Module3[?, ?, e2]] => pushElemTrees[e2](2)
    }

    inspect[T] {
      case '[Module4[?, ?, ?, e3]] => pushElemTrees[e3](3)
    }

    inspect[T] {
      case '[Module5[?, ?, ?, ?, e4]] => pushElemTrees[e4](4)
    }

    val pathSegmentsTree: Expr[?] => Expr[List[String]] =
      pathSegmentsTrees.result().reduceLeft((a, b) => arg => '{ ${ a(arg) } ++ ${ b(arg) } })

    def newCtor: (List[String], List[TypeRepr]) =
      (List("local_ctx"), List(TypeRepr.of[mill.api.ModuleCtx]))

    def newClassDecls(cls: Symbol): List[Symbol] = {
      newSyms.result().map(_(cls))
    }

    def clsFactory()(using Quotes): Symbol = {
      shims.Symbol.newClass(
        parent = cls,
        name = s"${cls.name}_impl",
        parents = List(TypeRepr.of[mill.api.Module.BaseClass], tpe),
        ctor = _ => newCtor,
        decls = newClassDecls,
        selfType = None
      )
    }

    // We need to create a `class $concreteCls` here, rather than just
    // creating an anonymous sub-type of $tpe, because our task resolution
    // logic needs to use java reflection to identify sub-modules and java
    // reflect can only properly identify nested `object`s inside Scala
    // `object` and `class`es.
    elems0 match {
      case '[elems] =>
        val wrappedElems = wrappedT.asExprOf[Seq[elems]]
        val ref = '{
          new mill.api.Cross.Factory[T](
            makeList = $wrappedElems.map((v2: elems) =>
              ${
                val concreteCls = clsFactory()
                val typeErrors0 = typeErrors.result()
                if typeErrors0.nonEmpty then
                  val errs = typeErrors0.map((n, t) =>
                    s"""- ${crossName(n)} requires ${t.show}
                    |  but inner element of type $elemsStr did not match${posStr(n)}."""
                  ).mkString("\n")
                  report.errorAndAbort(
                    s"""Cannot convert value to Cross.Factory[${cls.name}]:
                       |$errs""".stripMargin,
                    t.asTerm.pos
                  )
                end if
                val concreteClsDef = shims.ClassDef(
                  cls = concreteCls,
                  parents = {
                    val parentCtor =
                      New(TypeTree.of[mill.api.Module.BaseClass]).select(
                        TypeRepr.of[mill.api.Module.BaseClass].typeSymbol.primaryConstructor
                      )
                    val parentApp =
                      parentCtor.appliedToNone.appliedTo(
                        concreteCls.declaredField("local_ctx").mkRef(
                          s"${concreteCls} field local_ctx"
                        )
                      )
                    List(parentApp, TypeTree.of[T])
                  },
                  body = newTrees.toList.map(_(concreteCls, 'v2))
                )
                val clsOf = Ref(defn.Predef_classOf).appliedToType(concreteCls.typeRef)
                def newCls(ctx0: Expr[mill.api.ModuleCtx]): Expr[T] = {
                  New(TypeTree.ref(concreteCls))
                    .select(concreteCls.primaryConstructor)
                    .appliedTo(ctx0.asTerm)
                    .asExprOf[T]
                }
                Block(
                  List(concreteClsDef),
                  '{
                    (
                      ${ clsOf.asExprOf[Class[?]] },
                      (ctx0: mill.api.ModuleCtx) => ${ newCls('ctx0) }
                    )
                  }.asTerm
                ).asExprOf[(Class[?], mill.api.ModuleCtx => T)]
              }
            ),
            crossSegmentsList =
              $wrappedElems.map((segArg: elems) => ${ pathSegmentsTree('segArg) }),
            crossValuesListLists = $valuesTree,
            crossValuesRaw = $wrappedT
          )
        }
        // report.errorAndAbort(s"made factory ${ref.show}")
        ref
    }
  }

  def abortOldStyleClass(using Quotes)(tpe: quotes.reflect.TypeRepr): Nothing = {
    import quotes.reflect.*

    val primaryConstructorArgs =
      tpe.classSymbol.get.primaryConstructor.paramSymss.head

    val oldArgStr = primaryConstructorArgs
      .map { s => s"${s.name}: ${s.termRef.widen.show}" }
      .mkString(", ")

    def parenWrap(s: String) =
      if (primaryConstructorArgs.size == 1) s
      else s"($s)"

    val newTypeStr = primaryConstructorArgs.map(_.termRef.widen.show).mkString(", ")
    val newForwarderStr = primaryConstructorArgs.map(_.name).mkString(", ")

    report.errorAndAbort(
      s"""
         |Cross type ${tpe.typeSymbol.name} must be trait, not a class. Please change:
         |
         |  class ${tpe.typeSymbol.name}($oldArgStr)
         |
         |To:
         |
         |  trait ${tpe.typeSymbol.name} extends Cross.Module[${parenWrap(newTypeStr)}]{
         |    val ${parenWrap(newForwarderStr)} = crossValue
         |  }
         |
         |You also no longer use `: _*` when instantiating a cross-module:
         |
         |  Cross[${tpe.typeSymbol.name}](values:_*)
         |
         |Instead, you can pass the sequence directly:
         |
         |  Cross[${tpe.typeSymbol.name}](values)
         |
         |Note that the `millSourcePath` of cross modules has changed in
         |Mill 0.11.0, and no longer includes the cross values by default.
         |If you have `def millSourcePath = super.millSourcePath / os.up`,
         |you may remove it. If you do not have this definition, you can
         |preserve the old behavior via `def millSourcePath = super.millSourcePath / crossValue`
         |
         |""".stripMargin,
      Position.ofMacroExpansion
    )
  }
}

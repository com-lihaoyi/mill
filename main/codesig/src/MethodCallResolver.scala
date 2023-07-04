package mill.codesig

import JType.{Cls => JCls}
import upickle.default.{ReadWriter, macroRW}

/**
 * Traverses the call graph and inheritance hierarchy summaries produced by
 * [[LocalSummarizer]] and [[ExternalSummarizer]] to resolve method calls to
 * their potential destinations and compute transitive properties of the
 * call graph
 */
object MethodCallResolver {
  case class MethodCallInfo(localDests: Set[MethodDef], externalDests: Set[JCls])
  object MethodCallInfo {
    implicit def rw: ReadWriter[MethodCallInfo] = macroRW
  }

  case class Result(
      localCalls: Map[MethodCall, MethodCallInfo],
      externalClassLocalDests: Map[JCls, (Set[JCls], Set[MethodSig])]
  )
  object Result {
    implicit def rw: ReadWriter[Result] = macroRW
  }

  def resolveAllMethodCalls(
      localSummary: LocalSummarizer.Result,
      externalSummary: ExternalSummarizer.Result,
  ): Result = {

    val allDirectAncestors = {
      localSummary.mapValues(_.directAncestors) ++
        externalSummary.directAncestors
    }

    val directDescendents = {
      allDirectAncestors
        .toVector
        .flatMap { case (k, vs) => vs.map((_, k)) }
        .groupMap(_._1)(_._2)
    }

    // Given an external class, what are the local classes that inherit from it,
    // and what local methods may end up being called by the external class code
    val externalClsToLocalClsMethodsDirect = {
      localSummary
        .items
        .keySet
        .flatMap { cls =>
          transitiveAncestors(cls, allDirectAncestors)
            .filter(!localSummary.items.contains(_))
            .map { externalCls =>
              (externalCls, Set(cls))
            }
            .toMap
        }
        .groupMapReduce(_._1)(_._2)(_ ++ _)
        .map { case (externalCls, localClasses) =>
          // <init> methods are final and cannot be overriden
          val methods = externalSummary
            .directMethods
            .getOrElse(externalCls, Set())
            .filter(m => !m.static && m.name != "<init>")

          (externalCls, (localClasses, methods))
        }
    }

    val resolvedCalls = resolveAllMethodCalls0(
      localSummary,
      externalClsToLocalClsMethodsDirect,
      allDirectAncestors,
      localSummary.mapValues(_.superClass) ++ externalSummary.directSuperclasses,
      directDescendents,
      externalSummary.directMethods,
    )

    resolvedCalls
  }

  def resolveAllMethodCalls0(
      localSummary: LocalSummarizer.Result,
      externalClsToLocalClsMethods: Map[JCls, (Set[JCls], Set[MethodSig])],
      allDirectAncestors: Map[JCls, Set[JCls]],
      directSuperclasses: Map[JCls, JCls],
      directDescendents: Map[JCls, Vector[JCls]],
      externalDirectMethods: Map[JCls, Set[MethodSig]],
  ): Result = {

    def methodExists(cls: JCls, call: MethodCall): Boolean = {
      localSummary.items.get(cls).exists(_.methods.keysIterator.exists(sigMatchesCall(_, call))) ||
      externalDirectMethods.get(cls).exists(_.exists(sigMatchesCall(_, call)))
    }

    def resolveLocalReceivers(call: MethodCall): Set[JCls] = call.invokeType match {
      case InvokeType.Static =>
        clsAndSupers(call.cls, methodExists(_, call), directSuperclasses)
          .find(methodExists(_, call))
          .toSet

      case InvokeType.Special => Set(call.cls)

      case InvokeType.Virtual =>
        val directDef = call.toMethodSig
        if (localSummary.get(call.cls, directDef).exists(_.isPrivate)) Set(call.cls)
        else {
          val descendents = Util.breadthFirst(Seq(call.cls))(directDescendents.getOrElse(_, Nil)).toSet

          clsAndAncestors(descendents, methodExists(_, call), allDirectAncestors)
            .filter(methodExists(_, call))
        }
    }

    val allCalls = localSummary
      .mapValuesOnly(_.methods)
      .iterator
      .flatMap(_.values)
      .flatMap(_.calls)
      .toSet

    val callToResolved = {
      allCalls
        .iterator
        .map { call =>
          val (localReceivers, externalReceivers) =
            resolveLocalReceivers(call).partition(localSummary.contains)

          val localMethodDefs = localReceivers.map(MethodDef(_, call.toMethodSig))

          // When a call to an external method call is made, we don't know what the
          // implementation will do. We thus have to conservatively assume it can call
          // any method on any of the argument types that get passed to it, including
          // the `this` type if the method call is not static.
          val methodParamClasses =
            if (externalReceivers.isEmpty) Set.empty[JCls]
            else {
              val argTypes = call.desc.args.collect { case c: JCls => c }
              val thisTypes =
                if (call.invokeType == InvokeType.Static) Set.empty[JCls] else externalReceivers

              (argTypes ++ thisTypes).toSet
            }

          (call, (localMethodDefs, methodParamClasses))
        }
        .toMap
    }

    Result(
      localCalls = callToResolved
        .map { case (call, (local, external)) => (call, MethodCallInfo(local, external)) },
      externalClassLocalDests = externalClsToLocalClsMethods
    )
  }

  def transitiveAncestors(cls: JCls, allDirectAncestors: Map[JCls, Set[JCls]]): Set[JCls] = {
    Set(cls) ++
      allDirectAncestors
        .getOrElse(cls, Set.empty[JCls])
        .flatMap(transitiveAncestors(_, allDirectAncestors))
  }

  def sigMatchesCall(sig: MethodSig, call: MethodCall) = {
    sig.name == call.name &&
    sig.desc == call.desc &&
    (sig.static == (call.invokeType == InvokeType.Static))
  }

  def clsAndSupers(
      cls: JCls,
      skipEarly: JCls => Boolean,
      directSuperclasses: Map[JCls, JCls]
  ): Seq[JCls] = {
    Util.breadthFirst(Seq(cls))(cls =>
      if (skipEarly(cls)) Nil else directSuperclasses.get(cls)
    )
  }

  def clsAndAncestors(
      classes: IterableOnce[JCls],
      skipEarly: JCls => Boolean,
      allDirectAncestors: Map[JCls, Set[JCls]]
  ): Set[JCls] = {
    Util.breadthFirst(classes)(cls =>
      if (skipEarly(cls)) Nil else allDirectAncestors.getOrElse(cls, Nil)
    ).toSet
  }


}

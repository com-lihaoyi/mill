package mill.codesig

import mill.codesig.JvmModel.*
import mill.codesig.JvmModel.JType.Cls as JCls
import mill.internal.SpanningForest
import mill.internal.SpanningForest.breadthFirst
import upickle.default.{ReadWriter, macroRW}

case class ResolvedCalls(
    localCalls: Map[MethodCall, ResolvedCalls.MethodCallInfo],
    externalClassLocalDests: Map[JCls, (Set[JCls], Set[MethodSig])],
    classSingleAbstractMethods: Map[JCls, Set[MethodSig]]
)

/**
 * Traverses the call graph and inheritance hierarchy summaries produced by
 * [[LocalSummary]] and [[ExternalSummary]] to resolve method calls to
 * their potential destinations and compute transitive properties of the
 * call graph
 */
object ResolvedCalls {
  implicit def rw(implicit st: SymbolTable): ReadWriter[ResolvedCalls] = macroRW
  case class MethodCallInfo(localDests: Set[MethodDef], externalDests: Set[JCls])
  object MethodCallInfo {
    implicit def rw(implicit st: SymbolTable): ReadWriter[MethodCallInfo] = macroRW
  }

  def apply(
      localSummary: LocalSummary,
      externalSummary: ExternalSummary
  )(implicit st: SymbolTable): ResolvedCalls = {

    val allDirectAncestors =
      localSummary.mapValues(_.directAncestors) ++ externalSummary.directAncestors

    val directDescendents = SpanningForest.reverseEdges(allDirectAncestors)

    // Given an external class, what are the local classes that inherit from it,
    // and what local methods may end up being called by the external class code
    val externalClsToLocalClsMethods = {
      localSummary
        .items
        .keySet
        .flatMap { cls =>
          breadthFirst(Seq(cls))(allDirectAncestors.getOrElse(_, Nil))
            .filter(!localSummary.items.contains(_))
            .map((_, Set(cls)))
            .toMap
        }
        .groupMapReduce(_._1)(_._2)(_ ++ _)
        .map { case (externalCls, localClasses) =>
          // <init> methods are final and cannot be overridden
          val methods = externalSummary
            .directMethods
            .getOrElse(externalCls, Map())
            .keySet
            .filter(m => !m.static && m.name != "<init>")

          (externalCls, (localClasses, methods))
        }
    }

    val directSuperclasses =
      localSummary.mapValues(_.superClass) ++ externalSummary.directSuperclasses

    val allCalls = localSummary
      .mapValuesOnly(_.methods)
      .iterator
      .flatMap(_.values)
      .flatMap(_.calls)
      .toSet

    val staticCallSupers = allCalls
      .collect {
        case call: MethodCall if call.invokeType == InvokeType.Static =>
          (call.cls, breadthFirst(Seq(call.cls))(directSuperclasses.get))
      }
      .toMap

    val virtualCallDescendents = allCalls
      .collect {
        case call: MethodCall if call.invokeType == InvokeType.Virtual =>
          (call.cls, breadthFirst(Seq(call.cls))(directDescendents.getOrElse(_, Nil)).toSet)
      }
      .toMap

    // We identify all method defs that "look like" some kind of Single-Abstract-Method
    // for special casing. These kinds of methods tend to have very different usage
    // patterns than non-SAM methods, and so we use different conservative approximations
    // for analyzing their calls: we assume they are "live" when they are *instantiated*,
    // rather than when they are *invoked* (ideally we'd check both, but that's
    // computationally expensive)
    //
    // This special casing is similar to how we treat InvokeDynamic lambdas, and helps
    // because SAM methods are like lambdas in that they are *instantiated* in many
    // different places throughout the codebase, but are almost always *invoked* very
    // close to the point of instantiation. That means that checking that the SAM is
    // instantiated is pretty precise, whereas checking that it is called is very
    // im-precise since SAMs have so many implementations across the codebase it's
    // almost guaranteed that one of them would be called.
    val classSingleAbstractMethods: Map[JCls, Set[MethodSig]] = {
      val localSamDefiners = localSummary
        .items
        .flatMap { case (cls, clsInfo) =>
          clsInfo.methods.iterator.filter(_._2.isAbstract).toSeq match {
            case Seq((k, _)) => Some((cls, k))
            case _ => None
          }
        }

      val externalSamDefiners = externalSummary
        .directMethods
        .map { case (k, v) => (k, v.collect { case (sig, true) => sig }) }
        .collect { case (k, Seq[MethodSig](v)) =>
          (k, v)
        } // Scala 3.5.0-RC6 - can not infer MethodSig here

      val allSamDefiners = localSamDefiners ++ externalSamDefiners

      val allSamImplementors0 = allSamDefiners
        .toSeq
        .map { case (cls, sig) =>
          sig -> breadthFirst(Seq(cls))(cls => directDescendents.getOrElse(cls, Nil))
        }

      val allSamImplementors = SpanningForest.reverseEdges(allSamImplementors0)

      allSamImplementors.view.mapValues(_.toSet).toMap
    }

    val localCalls = {
      allCalls
        .iterator
        .map { call =>
          def methodExists(cls: JCls, call: MethodCall): Boolean = {
            localSummary.items.get(cls).exists(_.methods.contains(call.toMethodSig)) ||
            externalSummary.directMethods.get(cls).exists(_.contains(call.toMethodSig))
          }

          val allReceivers = call.invokeType match {
            case InvokeType.Static => staticCallSupers(call.cls).find(methodExists(_, call)).toSet
            case InvokeType.Special => Set(call.cls)

            case InvokeType.Virtual =>
              val directDef = call.toMethodSig
              if (localSummary.get(call.cls, directDef).exists(_.isPrivate)) Set(call.cls)
              else {
                val descendents = virtualCallDescendents(call.cls)

                breadthFirst(descendents)(cls =>
                  if (methodExists(cls, call)) Nil else allDirectAncestors.getOrElse(cls, Nil)
                ).toSet.filter(methodExists(_, call))
              }
          }

          val (localReceivers, externalReceivers) = allReceivers.partition(localSummary.contains)

          val localMethodDefs = localReceivers.map(st.MethodDef(_, call.toMethodSig))

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

          (call, MethodCallInfo(localMethodDefs, methodParamClasses))
        }
        .toMap
    }

    ResolvedCalls(
      localCalls = localCalls,
      externalClassLocalDests = externalClsToLocalClsMethods,
      classSingleAbstractMethods = classSingleAbstractMethods
    )
  }

}

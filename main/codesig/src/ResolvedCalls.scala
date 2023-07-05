package mill.codesig
import JvmModel._
import JType.{Cls => JCls}
import upickle.default.{ReadWriter, macroRW}

case class ResolvedCalls(
    localCalls: Map[MethodCall, ResolvedCalls.MethodCallInfo],
    externalClassLocalDests: Map[JCls, (Set[JCls], Set[MethodSig])]
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
          Util
            .breadthFirst(Seq(cls))(allDirectAncestors.getOrElse(_, Nil))
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

    val externalClsToLocalClsMethods = externalClsToLocalClsMethodsDirect
    val directSuperclasses =
      localSummary.mapValues(_.superClass) ++ externalSummary.directSuperclasses
    val externalDirectMethods = externalSummary.directMethods

    val allCalls = localSummary
      .mapValuesOnly(_.methods)
      .iterator
      .flatMap(_.values)
      .flatMap(_.calls)
      .toSet

    val staticCallSupers = allCalls
      .collect {
        case call: MethodCall if call.invokeType == InvokeType.Static =>
          (call.cls, Util.breadthFirst(Seq(call.cls))(directSuperclasses.get))
      }
      .toMap

    val virtualCallDescendents = allCalls
      .collect {
        case call: MethodCall if call.invokeType == InvokeType.Virtual =>
          (call.cls, Util.breadthFirst(Seq(call.cls))(directDescendents.getOrElse(_, Nil)).toSet)
      }
      .toMap

    val localCalls = {
      allCalls
        .iterator
        .map { call =>
          def methodExists(cls: JCls, call: MethodCall): Boolean = {
            localSummary.items.get(cls).exists(_.methods.contains(call.toMethodSig)) ||
            externalDirectMethods.get(cls).exists(_.contains(call.toMethodSig))
          }

          val allReceivers = call.invokeType match {
            case InvokeType.Static => staticCallSupers(call.cls).find(methodExists(_, call)).toSet
            case InvokeType.Special => Set(call.cls)

            case InvokeType.Virtual =>
              val directDef = call.toMethodSig
              if (localSummary.get(call.cls, directDef).exists(_.isPrivate)) Set(call.cls)
              else {
                val descendents = virtualCallDescendents(call.cls)

                Util
                  .breadthFirst(descendents)(cls =>
                    if (methodExists(cls, call)) Nil else allDirectAncestors.getOrElse(cls, Nil)
                  )
                  .toSet
                  .filter(methodExists(_, call))
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
      externalClassLocalDests = externalClsToLocalClsMethods
    )
  }
}

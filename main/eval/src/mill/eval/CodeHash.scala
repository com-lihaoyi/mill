package mill.eval

import mill.api.Strict
import mill.define.{NamedTask, Segments, Task}

import scala.reflect.NameTransformer.decode

trait EvaluatorCodeHashing {
  def scriptImportGraph: Map[os.Path, (Int, Seq[os.Path])]
  def methodCodeHashSignatures: Map[String, Int]

  def disableCallgraphInvalidation: Boolean

  lazy val constructorHashSignatures = methodCodeHashSignatures
    .toSeq
    .collect { case (method@s"$prefix#<init>($args)void", hash) => (prefix, method, hash) }
    .groupMap(_._1)(t => (t._2, t._3))

  def computeScriptHash(group: Strict.Agg[Task[_]]) = {
    if (disableCallgraphInvalidation) scriptImportInvalidation(group)
    else codeSigInvalidation(group)
  }

  private def scriptImportInvalidation(group: Strict.Agg[Task[_]]) = {
    val possibleScripts = scriptImportGraph.keySet.map(_.toString)
    val scripts = new Strict.Agg.Mutable[os.Path]()
    group.iterator.flatMap(t => Iterator(t) ++ t.inputs).foreach {
      // Filter out the `fileName` as a string before we call `os.Path` on it, because
      // otherwise linux paths on earlier-compiled artifacts can cause this to crash
      // when running on Windows with a different file path format
      case namedTask: NamedTask[_] if possibleScripts.contains(namedTask.ctx.fileName) =>
        scripts.append(os.Path(namedTask.ctx.fileName))
      case _ =>
    }

    val transitiveScripts = Graph.transitiveNodes(scripts)(t =>
      scriptImportGraph.get(t).map(_._2).getOrElse(Nil)
    )

    transitiveScripts
      .iterator
      // Sometimes tasks are defined in external/upstreadm dependencies,
      // (e.g. a lot of tasks come from JavaModule.scala) and won't be
      // present in the scriptImportGraph
      .map(p => scriptImportGraph.get(p).fold(0)(_._1))
      .sum

  }

  private def codeSigInvalidation(group: Strict.Agg[Task[_]]) = {
    group
      .iterator
      .collect {
        case namedTask: NamedTask[_] =>
          def resolveParents(c: Class[_]): Seq[Class[_]] = {
            Seq(c) ++
              Option(c.getSuperclass).toSeq.flatMap(resolveParents) ++
              c.getInterfaces.flatMap(resolveParents)
          }

          val transitiveParents = resolveParents(namedTask.ctx.enclosingCls)
          val methods = for {
            c <- transitiveParents
            m <- c.getDeclaredMethods
            if decode(m.getName) == namedTask.ctx.segment.pathSegments.head
          } yield m

          val methodClass = methods.head.getDeclaringClass.getName
          val name = namedTask.ctx.segment.pathSegments.last
          val expectedName = methodClass + "#" + name + "()mill.define.Target"

          // We not only need to look up the code hash of the Target method being called,
          // but also the code hash of the constructors required to instantiate the Module
          // that the Target is being called on. This can be done by walking up the nested
          // modules and looking at their constructors (they're `object`s and should each
          // have only one)
          val allEnclosingModules = Vector.unfold(namedTask.ctx) {
            case null => None
            case ctx =>
              ctx.enclosingModule match {
                case null => None
                case m: mill.define.Module => Some((m, m.millOuterCtx))
                case unknown => sys.error(s"Unknown ctx: $unknown")
              }
          }

          val constructorHashes = allEnclosingModules
            .map(m =>
              constructorHashSignatures.get(m.getClass.getName) match {
                case Some(Seq((singleMethod, hash))) => hash
                case Some(multiple) => sys.error(
                  s"Multiple constructors found for module $m: ${multiple.mkString(",")}"
                )
                case None => 0
              }
            )

          methodCodeHashSignatures.get(expectedName) ++ constructorHashes
      }
      .flatten
      .sum

  }
}

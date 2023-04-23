package mill.main

import mainargs.{MainData, TokenGrouping}
import mill.define._
import mill.util.EitherOps

import scala.collection.immutable

object ResolveTasks{
  def resolve(
    remainingSelector: List[Segment],
    current: BaseModule,
    discover: Discover[_],
    args: Seq[String]
  ): Either[String, Seq[NamedTask[Any]]] = {
    val raw = Resolve.resolve(remainingSelector, Resolve.Resolved.Module(current), discover, args)
      .map(_.collect {
        case t: Resolve.Resolved.Target => Right(t.value)
        case t: Resolve.Resolved.Command => t.value()
      })

    val cooked = raw.map(EitherOps.sequence(_))

    cooked.flatten
  }
}
/**
 * Takes a single list of segments, without braces but including wildcards, and
 * resolves all possible modules, targets or commands that the segments could
 * resolve to.
 *
 * Does not report errors if no possible resolved values are found; such
 * reporting is left to the caller.
 */
object Resolve {
  sealed trait Resolved
  object Resolved {
    case class Module(value: mill.define.Module) extends Resolved
    case class Target(value: mill.define.Target[_]) extends Resolved
    case class Command(name: String, value: () => Either[String, mill.define.Command[_]])
        extends Resolved
  }

  def resolve(
      remainingSelector: List[Segment],
      current: Resolved,
      discover: Discover[_],
      args: Seq[String]
  ): Either[String, Seq[Resolved]] = remainingSelector match {
    case Nil => Right(Seq(current))

    case head :: tail =>
      def recurse(searchModules: Seq[Either[String, Resolved]]): Either[String, Seq[Resolved]] = {
        val (errors, successes) = searchModules
          .map(_.flatMap(resolve(tail, _, discover, args)))
          .partitionMap(identity)

        if (errors.nonEmpty) Left(errors.mkString("\n"))
        else Right(successes.flatten)
      }

      (head, current) match {
        case (Segment.Label(singleLabel), Resolved.Module(obj)) =>
          recurse(
            singleLabel match {
              case "__" =>
                obj
                  .millInternal
                  .modules
                  .flatMap(m =>
                    Seq(Right(Resolved.Module(m))) ++
                      resolveDirectChildren(m, None, discover, args)
                  )
              case "_" => resolveDirectChildren(obj, None, discover, args)
              case _ => resolveDirectChildren(obj, Some(singleLabel), discover, args)
            }
          ).map(
            _.distinctBy {
              case t: NamedTask[_] => t.ctx.segments
              case t => t
            }
          )

        case (Segment.Cross(cross), Resolved.Module(c: Cross[_])) =>
          val searchModules: Seq[Module] =
            if (cross == Seq("__")) for ((_, v) <- c.valuesToModules.toSeq) yield v
            else if (cross.contains("_")) {
              for {
                (segments, v) <- c.segmentsToModules.toList
                if segments.length == cross.length
                if segments.zip(cross).forall { case (l, r) => l == r || r == "_" }
              } yield v
            } else c.segmentsToModules.get(cross.toList).toSeq

          recurse(searchModules.map(m => Right(Resolved.Module(m))))

        case _ => Right(Nil)
      }
  }

  def resolveDirectChildren(
      obj: Module,
      nameOpt: Option[String] = None,
      discover: Discover[_],
      args: Seq[String]
  ): Seq[Either[String, Resolved]] = {

    def namePred(n: String) = nameOpt.isEmpty || nameOpt.contains(n)

    val modules = obj
      .millInternal
      .reflectNestedObjects[Module](namePred)
      .map(t => Right(Resolved.Module(t)))

    val targets = obj
      .millInternal
      .reflect[Target[_]](namePred)
      .map(t => Right(Resolved.Target(t)))

    val commands = Module
      .reflect(obj.getClass, classOf[Command[_]], namePred)
      .map(_.getName)
      .map(name =>
        Right(Resolved.Command(
          name,
          () =>
            invokeCommand(
              obj,
              name,
              discover.asInstanceOf[Discover[Module]],
              args
            ).head
        ))
      )

    modules ++ targets ++ commands
  }

  def invokeCommand(
      target: Module,
      name: String,
      discover: Discover[Module],
      rest: Seq[String]
  ): immutable.Iterable[Either[String, Command[_]]] =
    for {
      (cls, entryPoints) <- discover.value
      if cls.isAssignableFrom(target.getClass)
      ep <- entryPoints
      if ep._2.name == name
    } yield {
      mainargs.TokenGrouping.groupArgs(
        rest,
        ep._2.argSigs0,
        allowPositional = true,
        allowRepeats = false,
        allowLeftover = ep._2.leftoverArgSig.nonEmpty
      ).flatMap { grouped =>
        mainargs.Invoker.invoke(
          target,
          ep._2.asInstanceOf[MainData[_, Any]],
          grouped.asInstanceOf[TokenGrouping[Any]]
        )
      } match {
        case mainargs.Result.Success(v: Command[_]) => Right(v)
        case f: mainargs.Result.Failure =>
          Left(
            mainargs.Renderer.renderResult(
              ep._2,
              f,
              totalWidth = 100,
              printHelpOnError = true,
              docsOnNewLine = false,
              customName = None,
              customDoc = None
            )
          )
      }
    }

}

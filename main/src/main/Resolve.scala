package mill.main

import mill.define._
import mill.define.TaskModule
import ammonite.util.Res
import mill.main.ResolveMetadata.singleModuleMeta
import mill.util.Router.EntryPoint
import mill.util.Scripts

import scala.reflect.ClassTag

object ResolveMetadata extends Resolve[String]{
  def singleModuleMeta(obj: Module, discover: Discover[_], isRootModule: Boolean): Seq[String] = {
    val modules = obj.millModuleDirectChildren.map(_.toString)
    val targets =
      obj
        .millInternal
        .reflectAll[Target[_]]
        .map(_.toString)
    val commands = for {
      (cls, entryPoints) <- discover.value
      if cls.isAssignableFrom(obj.getClass)
      ep <- entryPoints
    } yield
      if (isRootModule) ep._2.name
      else s"$obj.${ep._2.name}"

    modules ++ targets ++ commands
  }

  def endResolveLabel(obj: Module,
                      last: String,
                      discover: Discover[_],
                      rest: Seq[String]): Either[String, Seq[String]] = {
    def direct = singleModuleMeta(obj, discover, obj.millModuleSegments.value.isEmpty)
    last match{
      case "__" =>
        Right(
          // Filter out our own module in
          obj.millInternal.modules.flatMap(m => singleModuleMeta(m, discover, m == obj))
        )
      case "_" => Right(direct)
      case _ =>
        direct.find(_.split('.').last == last) match {
          case None => Resolve.errorMsgLabel(direct, Seq(Segment.Label(last)), obj.millModuleSegments.value)
          case Some(s) => Right(Seq(s))
        }
    }
  }

  def endResolveCross(obj: Module,
                      last: List[String],
                      discover: Discover[_],
                      rest: Seq[String]): Either[String, List[String]] = {
    obj match{
      case c: Cross[Module] =>
        last match{
          case List("__") => Right(c.items.map(_._2.toString))
          case items =>
            c.items
              .filter(_._1.length == items.length)
              .filter(_._1.zip(last).forall{case (a, b) => b == "_" || a.toString == b})
              .map(_._2.toString) match{
              case Nil =>
                Resolve.errorMsgCross(
                  c.items.map(_._1.map(_.toString)),
                  last,
                  obj.millModuleSegments.value
                )
              case res => Right(res)
            }

        }
      case _ =>
        Left(
          Resolve.unableToResolve(Segment.Cross(last), obj.millModuleSegments.value) +
          Resolve.hintListLabel(obj.millModuleSegments.value)
        )
    }
  }
}

object ResolveSegments extends Resolve[Segments] {

  override def endResolveCross(obj: Module,
                               last: List[String],
                               discover: Discover[_],
                               rest: Seq[String]): Either[String, Seq[Segments]] = {
    obj match{
      case c: Cross[Module] =>
        last match{
          case List("__") => Right(c.items.map(_._2.millModuleSegments))
          case items =>
            c.items
              .filter(_._1.length == items.length)
              .filter(_._1.zip(last).forall{case (a, b) => b == "_" || a.toString == b})
              .map(_._2.millModuleSegments) match {
              case Nil =>
                Resolve.errorMsgCross(
                  c.items.map(_._1.map(_.toString)),
                  last,
                  obj.millModuleSegments.value
                )
              case res => Right(res)
            }
        }
      case _ =>
        Left(
          Resolve.unableToResolve(Segment.Cross(last), obj.millModuleSegments.value) +
            Resolve.hintListLabel(obj.millModuleSegments.value)
        )
    }
  }

  def endResolveLabel(obj: Module,
                      last: String,
                      discover: Discover[_],
                      rest: Seq[String]): Either[String, Seq[Segments]] = {
    val target =
      obj
        .millInternal
        .reflectSingle[Target[_]](last)
        .map(t => Right(t.ctx.segments))

    val command =
      Resolve
        .invokeCommand(obj, last, discover, rest)
        .headOption
        .map(_.map(_.ctx.segments))

    val module =
      obj.millInternal
        .reflectNestedObjects[Module]
        .find(_.millOuterCtx.segment == Segment.Label(last))
        .map(m => Right(m.millModuleSegments))

    command orElse target orElse module match {
      case None =>
        Resolve.errorMsgLabel(
          singleModuleMeta(obj, discover, obj.millModuleSegments.value.isEmpty),
          Seq(Segment.Label(last)),
          obj.millModuleSegments.value
        )

      case Some(either) => either.right.map(Seq(_))
    }
  }
}

object ResolveTasks extends Resolve[NamedTask[Any]]{


  def endResolveCross(obj: Module,
                      last: List[String],
                      discover: Discover[_],
                      rest: Seq[String]): Either[String, Seq[NamedTask[Any]]] = {
    obj match{
      case c: Cross[Module] =>
        Resolve.runDefault(obj, Segment.Cross(last), discover, rest).flatten.headOption match{
          case None =>
            Left(
              "Cannot find default task to evaluate for module " +
              Segments((Segment.Cross(last) +: obj.millModuleSegments.value).reverse:_*).render
            )
          case Some(v) => v.map(Seq(_))
        }
      case _ =>
        Left(
          Resolve.unableToResolve(Segment.Cross(last), obj.millModuleSegments.value) +
          Resolve.hintListLabel(obj.millModuleSegments.value)
        )
    }
  }

  def endResolveLabel(obj: Module,
                      last: String,
                      discover: Discover[_],
                      rest: Seq[String]): Either[String, Seq[NamedTask[Any]]] = last match{
    case "__" =>
      Right(
        obj.millInternal.modules
          .filter(_ != obj)
          .flatMap(m => m.millInternal.reflectAll[Target[_]])
      )
    case "_" => Right(obj.millInternal.reflectAll[Target[_]])

    case _ =>
      val target =
        obj
          .millInternal
          .reflectSingle[Target[_]](last)
          .map(Right(_))

      val command = Resolve.invokeCommand(obj, last, discover, rest).headOption

      command orElse target orElse Resolve.runDefault(obj, Segment.Label(last), discover, rest).flatten.headOption match {
        case None =>
          Resolve.errorMsgLabel(
            singleModuleMeta(obj, discover, obj.millModuleSegments.value.isEmpty),
            Seq(Segment.Label(last)),
            obj.millModuleSegments.value
          )

        // Contents of `either` *must* be a `Task`, because we only select
        // methods returning `Task` in the discovery process
        case Some(either) => either.right.map(Seq(_))
      }
  }
}

object Resolve{
  def minimum(i1: Int, i2: Int, i3: Int)= math.min(math.min(i1, i2), i3)

  /**
    * Short Levenshtein distance algorithm, based on
    *
    * https://rosettacode.org/wiki/Levenshtein_distance#Scala
    */
  def editDistance(s1: String, s2: String) = {
    val dist = Array.tabulate(s2.length+1, s1.length+1){(j, i) => if(j==0) i else if (i==0) j else 0}

    for(j <- 1 to s2.length; i <- 1 to s1.length)
      dist(j)(i) = if(s2(j - 1) == s1(i-1)) dist(j - 1)(i-1)
      else minimum(dist(j - 1)(i) + 1, dist(j)(i - 1) + 1, dist(j - 1)(i - 1) + 1)

    dist(s2.length)(s1.length)
  }

  def unableToResolve(last: Segment, revSelectorsSoFar: Seq[Segment]): String = {
    unableToResolve(Segments((last +: revSelectorsSoFar).reverse: _*).render)
  }

  def unableToResolve(segments: String): String = "Cannot resolve " + segments + "."

  def hintList(revSelectorsSoFar: Seq[Segment]) = {
    val search = Segments(revSelectorsSoFar:_*).render
    s" Try `mill resolve $search` to see what's available."
  }

  def hintListLabel(revSelectorsSoFar: Seq[Segment]) = {
    hintList(revSelectorsSoFar :+ Segment.Label("_"))
  }

  def hintListCross(revSelectorsSoFar: Seq[Segment]) = {
    hintList(revSelectorsSoFar :+ Segment.Cross(Seq("__")))
  }

  def errorMsgBase[T](direct: Seq[T],
                      last0: T,
                      editSplit: String => String,
                      defaultErrorMsg: String)
                     (strings: T => Seq[String],
                      render: T => String): Left[String, Nothing] = {
    val last = strings(last0)
    val similar =
      direct
        .map(x => (x, strings(x)))
        .filter(_._2.length == last.length)
        .map{ case (d, s) => (d, s.zip(last).map{case (a, b) => Resolve.editDistance(editSplit(a), b)}.sum)}
        .filter(_._2 < 3)
        .sortBy(_._2)

    if (similar.headOption.exists(_._1 == last0)){
      // Special case: if the most similar segment is the desired segment itself,
      // this means we are trying to resolve a module where a task is present.
      // Special case the error message to make it something meaningful
      Left("Task " + last0 + " is not a module and has no children.")
    }else{

      val hint = similar match{
        case Nil => defaultErrorMsg
        case items => " Did you mean " + render(items.head._1) + "?"
      }
      Left(unableToResolve(render(last0)) + hint)
    }
  }

  def errorMsgLabel(direct: Seq[String], remaining: Seq[Segment], revSelectorsSoFar: Seq[Segment]) = {
    errorMsgBase(
      direct,
      Segments(revSelectorsSoFar ++ remaining:_*).render,
      _.split('.').last,
      hintListLabel(revSelectorsSoFar)
    )(
      rendered => Seq(rendered.split('.').last),
      x => x
    )
  }

  def errorMsgCross(crossKeys: Seq[Seq[String]],
                    last: Seq[String],
                    revSelectorsSoFar: Seq[Segment]) = {
    errorMsgBase(
      crossKeys,
      last,
      x => x,
      hintListCross(revSelectorsSoFar)
    )(
      crossKeys => crossKeys,
      crossKeys => Segments((Segment.Cross(crossKeys) +: revSelectorsSoFar).reverse:_*).render
    )
  }

  def invokeCommand(target: Module,
                    name: String,
                    discover: Discover[_],
                    rest: Seq[String]) = for {
    (cls, entryPoints) <- discover.value
    if cls.isAssignableFrom(target.getClass)
    ep <- entryPoints
    if ep._2.name == name
  } yield Scripts.runMainMethod(
    target,
    ep._2.asInstanceOf[EntryPoint[Module]],
    ammonite.main.Scripts.groupArgs(rest.toList)
  ) match {
    case Res.Success(v: Command[_]) => Right(v)
    case Res.Failure(msg) => Left(msg)
    case Res.Exception(ex, msg) =>
      val sw = new java.io.StringWriter()
      ex.printStackTrace(new java.io.PrintWriter(sw))
      val prefix = if (msg.nonEmpty) msg + "\n" else msg
      Left(prefix + sw.toString)

  }

  def runDefault(obj: Module, last: Segment, discover: Discover[_], rest: Seq[String]) = for {
    child <- obj.millInternal.reflectNestedObjects[Module]
    if child.millOuterCtx.segment == last
    res <- child match {
      case taskMod: TaskModule =>
        Some(invokeCommand(child, taskMod.defaultCommandName(), discover, rest).headOption)
      case _ => None
    }
  } yield res

}
abstract class Resolve[R: ClassTag] {
  def endResolveCross(obj: Module,
                      last: List[String],
                      discover: Discover[_],
                      rest: Seq[String]): Either[String, Seq[R]]
  def endResolveLabel(obj: Module,
                      last: String,
                      discover: Discover[_],
                      rest: Seq[String]): Either[String, Seq[R]]

  def resolve(remainingSelector: List[Segment],
              obj: mill.Module,
              discover: Discover[_],
              rest: Seq[String],
              remainingCrossSelectors: List[List[String]]): Either[String, Seq[R]] = {

    remainingSelector match{
      case Segment.Cross(last) :: Nil =>
        endResolveCross(obj, last.map(_.toString).toList, discover, rest)
      case Segment.Label(last) :: Nil =>
        endResolveLabel(obj, last, discover, rest)

      case head :: tail =>
        def recurse(searchModules: Seq[Module], resolveFailureMsg: => Left[String, Nothing]) = {
          val matching = searchModules
            .map(resolve(tail, _, discover, rest, remainingCrossSelectors))

          matching match{
            case Seq(Left(err)) => Left(err)
            case items =>
              items.collect{case Right(v) => v} match{
                case Nil => resolveFailureMsg
                case values => Right(values.flatten)
              }
          }
        }
        head match{
          case Segment.Label(singleLabel) =>
            recurse(
              singleLabel match{
                case "__" => obj.millInternal.modules
                case "_" => obj.millModuleDirectChildren
                case _ =>
                  obj.millInternal.reflectNestedObjects[mill.Module]
                    .find(_.millOuterCtx.segment == Segment.Label(singleLabel))
                    .toSeq
              },
              singleLabel match{
                case "_" =>
                  Left(
                    "Cannot resolve " + Segments((remainingSelector.reverse ++ obj.millModuleSegments.value).reverse:_*).render +
                      ". Try `mill resolve " + Segments((Segment.Label("_") +: obj.millModuleSegments.value).reverse:_*).render + "` to see what's available."
                  )
                case "__" =>
                  Resolve.errorMsgLabel(
                    singleModuleMeta(obj, discover, obj.millModuleSegments.value.isEmpty),
                    remainingSelector,
                    obj.millModuleSegments.value
                  )
                case _ =>
                  Resolve.errorMsgLabel(
                    singleModuleMeta(obj, discover, obj.millModuleSegments.value.isEmpty),
                    Seq(Segment.Label(singleLabel)),
                    obj.millModuleSegments.value
                  )
              }
            ).map(
              _.distinctBy {
                case t: NamedTask[_] => t.ctx.segments
                case t => t
              }
            )
          case Segment.Cross(cross) =>
            obj match{
              case c: Cross[Module] =>
                recurse(
                  if(cross == Seq("__")) for ((_, v) <- c.items) yield v
                  else if (cross.contains("_")){
                    for {
                      (k, v) <- c.items
                      if k.length == cross.length
                      if k.zip(cross).forall { case (l, r) => l == r || r == "_" }
                    } yield v
                  }else c.itemMap.get(cross.toList).toSeq,
                  Resolve.errorMsgCross(
                    c.items.map(_._1.map(_.toString)),
                    cross.map(_.toString),
                    obj.millModuleSegments.value
                  )
                )
              case _ =>
                Left(
                  Resolve.unableToResolve(Segment.Cross(cross.map(_.toString)), tail) +
                  Resolve.hintListLabel(tail)
                )
            }
        }

      case Nil => Left("Selector cannot be empty")
    }
  }
}

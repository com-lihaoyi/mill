/*
 * This file contains code originally published under the following license:
 *
 * Copyright (c) 2012, Roman Timushev
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * The name of the author may not be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package mill.javalib.dependency.versions

import scala.util.matching.Regex
import scala.util.matching.Regex.Groups

sealed trait Version {
  def major: Long

  def minor: Long

  def patch: Long
}

case class ValidVersion(
    text: String,
    releasePart: List[Long],
    preReleasePart: List[String],
    buildPart: List[String]
) extends Version {
  def major: Long = releasePart.headOption getOrElse 0

  def minor: Long = releasePart.drop(1).headOption getOrElse 0

  def patch: Long = releasePart.drop(2).headOption getOrElse 0

  override def toString: String = text
}
object ValidVersion {
  implicit val rw: upickle.ReadWriter[ValidVersion] =
    upickle.macroRW
}

case class InvalidVersion(text: String) extends Version {
  def major: Long = -1

  def minor: Long = -1

  def patch: Long = -1
}
object InvalidVersion {
  implicit val rw: upickle.ReadWriter[InvalidVersion] =
    upickle.macroRW
}

private[dependency] object ReleaseVersion {
  private val releaseKeyword: Regex = "(?i)final|release".r

  def unapply(v: Version): Option[List[Long]] = v match {
    case ValidVersion(_, releasePart, Nil, Nil) => Some(releasePart)
    case ValidVersion(_, releasePart, releaseKeyword() :: Nil, Nil) =>
      Some(releasePart)
    case _ => None
  }
}

private[dependency] object PreReleaseVersion {
  def unapply(v: Version): Option[(List[Long], List[String])] = v match {
    case ValidVersion(_, releasePart, preReleasePart, Nil) if preReleasePart.nonEmpty =>
      Some((releasePart, preReleasePart))
    case _ => None
  }
}

private[dependency] object PreReleaseBuildVersion {
  def unapply(v: Version): Option[(List[Long], List[String], List[String])] =
    v match {
      case ValidVersion(_, releasePart, preReleasePart, buildPart)
          if preReleasePart.nonEmpty && buildPart.nonEmpty =>
        Some((releasePart, preReleasePart, buildPart))
      case _ => None
    }
}

private[dependency] object SnapshotVersion {
  def unapply(v: Version): Option[(List[Long], List[String], List[String])] =
    v match {
      case ValidVersion(_, releasePart, preReleasePart, buildPart)
          if preReleasePart.lastOption.contains("SNAPSHOT") =>
        Some((releasePart, preReleasePart, buildPart))
      case _ => None
    }
}

private[dependency] object BuildVersion {
  def unapply(v: Version): Option[(List[Long], List[String])] = v match {
    case ValidVersion(_, releasePart, Nil, buildPart) if buildPart.nonEmpty =>
      Some((releasePart, buildPart))
    case _ => None
  }
}

object Version {
  def apply(text: String): Version = synchronized {
    VersionParser
      .parse(text)
      .fold(
        (_, _, _) => InvalidVersion(text),
        { case ((a, b, c), _) => ValidVersion(text, a.toList, b.toList, c.toList) }
      )
  }

  implicit def versionOrdering: Ordering[Version] = VersionOrdering
  implicit val rw: upickle.ReadWriter[Version] =
    upickle.macroRW
}

private[dependency] object VersionOrdering extends Ordering[Version] {

  private val subParts = "(\\d+)?(\\D+)?".r

  private def parsePart(s: String): Seq[Either[Int, String]] =
    try {
      subParts
        .findAllIn(s)
        .matchData
        .flatMap {
          case Groups(num, str) =>
            Seq(Option(num).map(_.toInt).map(Left.apply), Option(str).map(Right.apply))
        }
        .flatten
        .toList
    } catch {
      case _: NumberFormatException => List(Right(s))
    }

  private def toOpt(x: Int): Option[Int] = if (x == 0) None else Some(x)

  private def comparePart(a: String, b: String) = {
    if (a == b) None
    else
      (parsePart(a) zip parsePart(b)) map {
        case (Left(x), Left(y)) => x `compareTo` y
        case (Left(_), Right(_)) => -1
        case (Right(_), Left(_)) => 1
        case (Right(x), Right(y)) => x `compareTo` y
      } find (_ != 0) orElse Some(a `compareTo` b)
  }

  private def compareNumericParts(a: List[Long], b: List[Long]): Option[Int] =
    (a, b) match {
      case (ah :: at, bh :: bt) =>
        toOpt(ah `compareTo` bh) orElse compareNumericParts(at, bt)
      case (ah :: at, Nil) =>
        toOpt(ah `compareTo` 0L) orElse compareNumericParts(at, Nil)
      case (Nil, bh :: bt) =>
        toOpt(0L `compareTo` bh) orElse compareNumericParts(Nil, bt)
      case (Nil, Nil) =>
        None
    }

  private def compareParts(a: List[String], b: List[String]): Option[Int] =
    (a, b) match {
      case (ah :: at, bh :: bt) =>
        comparePart(ah, bh) orElse compareParts(at, bt)
      case (_ :: _, Nil) =>
        Some(1)
      case (Nil, _ :: _) =>
        Some(-1)
      case (Nil, Nil) =>
        None
    }

  def compare(x: Version, y: Version): Int = (x, y) match {
    case (InvalidVersion(a), InvalidVersion(b)) =>
      a `compareTo` b
    case (InvalidVersion(_), _) =>
      -1
    case (_, InvalidVersion(_)) =>
      1
    case (ReleaseVersion(r1), ReleaseVersion(r2)) =>
      compareNumericParts(r1, r2) getOrElse 0
    case (ReleaseVersion(r1), PreReleaseVersion(r2, _)) =>
      compareNumericParts(r1, r2) getOrElse 1
    case (ReleaseVersion(r1), PreReleaseBuildVersion(r2, _, _)) =>
      compareNumericParts(r1, r2) getOrElse 1
    case (ReleaseVersion(r1), BuildVersion(r2, _)) =>
      compareNumericParts(r1, r2) getOrElse -1
    case (PreReleaseVersion(r1, _), ReleaseVersion(r2)) =>
      compareNumericParts(r1, r2) getOrElse -1
    case (PreReleaseVersion(r1, p1), PreReleaseVersion(r2, p2)) =>
      compareNumericParts(r1, r2) orElse compareParts(p1, p2) getOrElse 0
    case (PreReleaseVersion(r1, p1), PreReleaseBuildVersion(r2, p2, _)) =>
      compareNumericParts(r1, r2) orElse compareParts(p1, p2) getOrElse -1
    case (PreReleaseVersion(r1, _), BuildVersion(r2, _)) =>
      compareNumericParts(r1, r2) getOrElse -1
    case (PreReleaseBuildVersion(r1, _, _), ReleaseVersion(r2)) =>
      compareNumericParts(r1, r2) getOrElse -1
    case (PreReleaseBuildVersion(r1, p1, _), PreReleaseVersion(r2, p2)) =>
      compareNumericParts(r1, r2) orElse compareParts(p1, p2) getOrElse 1
    case (PreReleaseBuildVersion(r1, p1, b1), PreReleaseBuildVersion(r2, p2, b2)) =>
      compareNumericParts(r1, r2) orElse
        compareParts(p1, p2) orElse
        compareParts(b1, b2) getOrElse
        0
    case (PreReleaseBuildVersion(r1, _, _), BuildVersion(r2, _)) =>
      compareNumericParts(r1, r2) getOrElse -1
    case (BuildVersion(r1, _), ReleaseVersion(r2)) =>
      compareNumericParts(r1, r2) getOrElse 1
    case (BuildVersion(r1, _), PreReleaseVersion(r2, _)) =>
      compareNumericParts(r1, r2) getOrElse 1
    case (BuildVersion(r1, _), PreReleaseBuildVersion(r2, _, _)) =>
      compareNumericParts(r1, r2) getOrElse 1
    case (BuildVersion(r1, b1), BuildVersion(r2, b2)) =>
      compareNumericParts(r1, r2) orElse compareParts(b1, b2) getOrElse 0
    case (_: ValidVersion, _: ValidVersion) => ???
  }

}

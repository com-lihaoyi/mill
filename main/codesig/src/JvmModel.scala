package mill.codesig

import upickle.default.{ReadWriter, readwriter}

// This file contains typed data structures representing the types and values
// found in the JVM bytecode: various kinds of types, method signatures, method
// calls, etc. These are generally parsed from stringly-typed fields given to
// us by ASM library

case class ResolvedMethodDef(cls: JType.Cls, method: MethodDef){
  override def toString = cls.pretty + method.toString
}

object ResolvedMethodDef{
  implicit val ordering: Ordering[ResolvedMethodDef] = Ordering.by(m => (m.cls, m.method))
}

case class MethodDef(static: Boolean, name: String, desc: Desc){
  override def toString = (if(static) "." else "#") + name + desc.pretty
}

object MethodDef{
  implicit val ordering: Ordering[MethodDef] = Ordering.by(m => (m.static, m.name, m.desc))
}

case class MethodCall(cls: JType.Cls, invokeType: InvokeType, name: String, desc: Desc){
  override def toString = {
    val sep = invokeType match{
      case InvokeType.Static => '.'
      case InvokeType.Virtual => '#'
      case InvokeType.Special => '!'
    }
    cls.name + sep + name + desc
  }
}

sealed trait InvokeType
object InvokeType{
  case object Static extends InvokeType
  case object Virtual extends InvokeType
  case object Special extends InvokeType
}

sealed trait JType{
  override def toString = pretty

  /**
   * A pretty Java-esque dot-delimited syntax for serializing JTypes. Much more
   * readable and familiar than the slash-based JVM bytecode syntax
   */
  def pretty: String
}
object JType {
  class Prim(val pretty: String) extends JType

  object Prim extends {
    def read(s: String) = all(s(0))

    val all: Map[Char, Prim] = Map(
      'V' -> (V: Prim),
      'Z' -> (Z: Prim),
      'B' -> (B: Prim),
      'C' -> (C: Prim),
      'S' -> (S: Prim),
      'I' -> (I: Prim),
      'F' -> (F: Prim),
      'J' -> (J: Prim),
      'D' -> (D: Prim)
    )

    case object V extends Prim("void")
    case object Z extends Prim("boolean")
    case object B extends Prim("byte")
    case object C extends Prim("char")
    case object S extends Prim("short")
    case object I extends Prim("int")
    case object F extends Prim("float")
    case object J extends Prim("long")
    case object D extends Prim("double")
  }

  case class Arr(innerType: JType) extends JType{
    def pretty = innerType.pretty + "[]"
  }
  object Arr{
    def read(s: String) = Arr(JType.read(s.drop(1)))
  }
  case class Cls(name: String) extends JType {
    assert(!name.contains('/'), s"JType $name contains invalid '/' characters")
    assert(!name.contains('['), s"JType $name contains invalid '[' characters")
    def pretty = name
  }

  object Cls {
    def fromSlashed(s: String) = Cls(s.replace('/', '.'))

    implicit val rw: ReadWriter[Cls] = readwriter[String].bimap(_.name, JType.Cls(_))
    implicit val ordering: Ordering[Cls] = Ordering.by(_.name)

    def read(s: String) = fromSlashed(s)
  }

  def read(s: String): JType = s match {
    case x if Prim.all.contains(x(0)) => Prim.all(x(0))
    case s if s.startsWith("L") && s.endsWith(";") => Cls.read(s.drop(1).dropRight(1))
    case s if s.startsWith("[") => Arr.read(s)
    case s => Cls.read(s)
  }

  implicit val ordering: Ordering[JType] = Ordering.by(_.pretty)
}

object Desc{
  def read(s: String) = {
    val scala.Array(argString, ret) = s.drop(1).split(')')
    val args = collection.mutable.Buffer.empty[String]
    var index = 0
    while(index < argString.length){
      val firstChar = argString.indexWhere(x => "BCDFIJSZL".contains(x), index)
      val split = argString(firstChar) match{
        case 'L' => argString.indexWhere(x => ";".contains(x), index)
        case _ => argString.indexWhere(x => "BCDFIJSZ".contains(x), index)
      }

      args.append(argString.substring(index, split+1))
      index = split +1
    }
    Desc(args.map(JType.read).toSeq, JType.read(ret))
  }

  implicit val ordering: Ordering[Desc] = Ordering.by(_.pretty)
}

/**
 * Represents the signature of a method.
 */
case class Desc(args: Seq[JType], ret: JType){
  def pretty = "(" + args.map(_.pretty).mkString(",") + ")" + ret.pretty
  override def toString = pretty
}

package mill.codesig

import upickle.default.{ReadWriter, readwriter}

// This file contains typed data structures representing the types and values
// found in the JVM bytecode: various kinds of types, method signatures, method
// calls, etc. These are generally parsed from stringly-typed fields given to
// us by ASM library

case class MethodSig(cls: JType.Cls, static: Boolean, name: String, desc: Desc){
  override def toString = cls.name + (if(static) "." else "#") + name + desc
}

object MethodSig{
  implicit val ordering: Ordering[MethodSig] = Ordering.by(m => (m.cls, m.static, m.name, m.desc))
}

case class LocalMethodSig(static: Boolean, name: String, desc: String){
  override def toString = (if(static) "." else "#") + name + desc
}

object LocalMethodSig{
  implicit val ordering: Ordering[LocalMethodSig] = Ordering.by(m => (m.static, m.name, m.desc))
}

case class MethodCall(cls: JType.Cls, invokeType: InvokeType, name: String, desc: Desc)

sealed trait InvokeType
object InvokeType{
  case object Static extends InvokeType
  case object Virtual extends InvokeType
  case object Special extends InvokeType
}

sealed trait JType
object JType {
  class Prim(val shortJavaName: String, val longJavaName: String) extends JType

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

    val allJava: Map[String, Prim] = Map(
      "void" -> (V: Prim),
      "boolean" -> (Z: Prim),
      "byte" -> (B: Prim),
      "char" -> (C: Prim),
      "short" -> (S: Prim),
      "int" -> (I: Prim),
      "float" -> (F: Prim),
      "long" -> (J: Prim),
      "double" -> (D: Prim)
    )
    case object V extends Prim("V", "void")
    case object Z extends Prim("Z", "boolean")
    case object B extends Prim("B", "byte")
    case object C extends Prim("C", "char")
    case object S extends Prim("S", "short")
    case object I extends Prim("I", "int")
    case object F extends Prim("F", "float")
    case object J extends Prim("J", "long")
    case object D extends Prim("D", "double")
  }

  case class Arr(innerType: JType) extends JType
  object Arr{
    def read(s: String) = Arr(JType.read(s.drop(1)))

    def readJava(s: String) = Arr(s.drop(1) match {
      case x if Prim.all.contains(x(0)) => Prim.all(x(0))
      case x if x.startsWith("L") => Cls.read(x.drop(1).dropRight(1).replace('.', '/'))
      case x => JType.readJava(x)
    })
  }
  case class Cls(name: String) extends JType {
    assert(!name.contains('/'), s"JType $name contains invalid '/' characters")
  }

  object Cls {
    def fromSlashed(s: String) = Cls(s.replace('/', '.'))

    implicit val rw: ReadWriter[Cls] = readwriter[String].bimap(_.name, JType.Cls(_))
    implicit val ordering: Ordering[Cls] = Ordering.by(_.name)

    def read(s: String) = fromSlashed(s)
    def readJava(s: String) = Cls(s.replace('.', '/'))
  }

  def read(s: String): JType = s match {
    case x if Prim.all.contains(x(0)) => Prim.all(x(0))
    case s if s.startsWith("L") && s.endsWith(";") => Cls.read(s.drop(1).dropRight(1))
    case s if s.startsWith("[") => Arr.read(s)
    case s => Cls.read(s)
  }

  def readJava(s: String): JType = s match {
    case x if Prim.allJava.contains(x) => Prim.allJava(x)
    case s if s.startsWith("[") => Arr.readJava(s)
    case s => Cls.readJava(s)
  }

  implicit val ordering: Ordering[JType] = Ordering.by(orderingString)

  def orderingString(t: JType): String = t match{
    case t: JType.Cls => t.name
    case t: JType.Arr => "[" + orderingString(t.innerType)
    case t: JType.Prim => t.shortJavaName
  }
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
  def unparse(t: JType): String = {
    t match{
      case t: JType.Cls => t.name
      case t: JType.Arr => "[" + unparse(t.innerType)
      case x: JType.Prim => x.shortJavaName
    }
  }

  implicit val ordering: Ordering[Desc] = Ordering.by(_.unparse)
}

/**
 * Represents the signature of a method.
 */
case class Desc(args: Seq[JType], ret: JType){
  def unparse = "(" + args.map(Desc.unparse).foldLeft("")(_+_) + ")" + Desc.unparse(ret)
  override def toString = unparse

  def shorten(name: String) = {
    val some :+ last = name.split("/").toSeq
    (some.map(_(0)) :+ last).mkString("/")
  }


}

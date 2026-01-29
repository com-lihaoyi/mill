package mill.codesig

import upickle.{ReadWriter, readwriter, stringKeyRW}

import scala.annotation.switch
import scala.collection.immutable.ArraySeq
import scala.collection.mutable.LinkedHashMap

// This file contains typed data structures representing the types and values
// found in the JVM bytecode: various kinds of types, method signatures, method
// calls, etc. These are generally parsed from stringly-typed fields given to
// us by ASM library

object JvmModel {

  /**
   * Manages an interning cache for common [[JvmModel]] data types. This ensures that
   * once a data type is constructed, the same instance is re-used going forward for
   * any constructions with identical arguments. This reduces total memory usage and
   * lets us replace structural hashing/equality with instance-identity hashing/equality,
   * improving performance.
   */
  class SymbolTable {
    abstract class Table[K, V] {
      def create: K => V
      val lookup: LinkedHashMap[K, V] = LinkedHashMap.empty[K, V]
      def get(k: K): V = lookup.getOrElseUpdate(k, create(k))
    }

    object MethodDef extends Table[(JType.Cls, MethodSig), MethodDef] {
      def create: ((JType.Cls, MethodSig)) => MethodDef = (new MethodDef(_, _)).tupled
      def apply(cls: JType.Cls, method: MethodSig): MethodDef = get((cls, method))
    }

    object MethodSig extends Table[(Boolean, String, Desc), MethodSig] {
      def create: ((Boolean, String, Desc)) => MethodSig = (new MethodSig(_, _, _)).tupled
      def apply(static: Boolean, name: String, desc: Desc): MethodSig = get((static, name, desc))
    }

    object MethodCall extends Table[(JType.Cls, InvokeType, String, Desc), MethodCall] {
      def create: ((JType.Cls, InvokeType, String, Desc)) => MethodCall =
        (new MethodCall(_, _, _, _)).tupled
      def apply(cls: JType.Cls, invokeType: InvokeType, name: String, desc: Desc): MethodCall =
        get((cls, invokeType, name, desc))
    }

    object JCls extends Table[String, JType.Cls] {
      def create: String => JType.Cls = new JType.Cls(_)
      def apply(name: String): JType.Cls = get(name)
    }

    object Desc extends Table[String, Desc] {
      def create: String => Desc = s => JvmModel.this.Desc.read(s)(using SymbolTable.this)
      def read(name: String): Desc = get(name)
    }
  }
  class MethodDef private[JvmModel] (val cls: JType.Cls, val sig: MethodSig) {
    override def toString: String = cls.pretty + sig.toString

    val stableHashCode: Int = (cls, sig).hashCode
    override def hashCode() = stableHashCode
  }

  object MethodDef {
    implicit val ordering: Ordering[MethodDef] = Ordering.by(m => (m.cls, m.sig))
    implicit val rw: ReadWriter[MethodDef] =
      stringKeyRW(readwriter[String].bimap(_.toString, _ => ???))
  }

  class MethodSig private[JvmModel] (val static: Boolean, val name: String, val desc: Desc) {
    override def toString: String = (if (static) "." else "#") + name + desc.pretty

    val stableHashCode: Int = (static, name, desc).hashCode
    override def hashCode() = stableHashCode
  }

  object MethodSig {
    implicit val ordering: Ordering[MethodSig] = Ordering.by(m => (m.static, m.name, m.desc))
    implicit val rw: ReadWriter[MethodSig] =
      stringKeyRW(readwriter[String].bimap(_.toString, _ => ???))
  }

  class MethodCall private[JvmModel] (
      val cls: JType.Cls,
      val invokeType: InvokeType,
      val name: String,
      val desc: Desc
  ) {
    override def toString: String = {
      val sep = invokeType match {
        case InvokeType.Static => '.'
        case InvokeType.Virtual => '#'
        case InvokeType.Special => '!'
      }
      cls.name + sep + name + desc
    }

    val stableHashCode: Int = (cls, invokeType, name, desc).hashCode
    override def hashCode() = stableHashCode

    def toMethodSig(using st: SymbolTable): MethodSig =
      st.MethodSig(invokeType == InvokeType.Static, name, desc)
  }

  object MethodCall {
    implicit val rw: ReadWriter[MethodCall] =
      stringKeyRW(readwriter[String].bimap(_.toString, _ => ???))

    implicit val ordering: Ordering[MethodCall] =
      Ordering.by(c => (c.cls, c.name, c.desc, c.invokeType))
  }

  sealed trait InvokeType

  object InvokeType {
    case object Static extends InvokeType
    case object Virtual extends InvokeType
    case object Special extends InvokeType

    implicit val ordering: Ordering[InvokeType] = Ordering.by {
      case Static => 0
      case Virtual => 1
      case Special => 2
    }
  }

  sealed trait JType {
    override def toString = pretty

    /**
     * A pretty Java-esque dot-delimited syntax for serializing JTypes. Much more
     * readable and familiar than the slash-based JVM bytecode syntax
     */
    def pretty: String
  }

  object JType {
    implicit val rw: ReadWriter[MethodSig] =
      stringKeyRW(readwriter[String].bimap(_.toString, _ => ???))

    sealed class Prim(val pretty: String) extends JType

    object Prim {
      def read(s: String): Prim = all(s(0))

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

    case class Arr(val innerType: JType) extends JType {
      def pretty: String = innerType.pretty + "[]"
    }

    object Arr {
      def read(s: String)(using st: SymbolTable): Arr = Arr(JType.read(s.drop(1)))
    }

    class Cls private[JvmModel] (val name: String) extends JType {
      assert(!name.contains('/'), s"JType $name contains invalid '/' characters")
      assert(!name.contains('['), s"JType $name contains invalid '[' characters")

      def pretty = name
      val stableHashCode = name.hashCode
      override def hashCode() = stableHashCode
    }

    object Cls {
      def fromSlashed(s: String)(using st: SymbolTable): Cls = st.JCls(s.replace('/', '.'))

      implicit def rw(using st: SymbolTable): ReadWriter[Cls] =
        stringKeyRW(readwriter[String].bimap(_.name, st.JCls(_)))

      implicit val ordering: Ordering[Cls] = Ordering.by(_.name)

      def read(s: String)(using st: SymbolTable): Cls = fromSlashed(s)
    }

    def read(s: String)(using st: SymbolTable): JType = s match {
      case x if Prim.all.contains(x(0)) => Prim.all(x(0))
      case s if s.charAt(0) == 'L' && s.last == ';' => Cls.read(s.slice(1, s.length - 1))
      case s if s.charAt(0) == '[' => Arr.read(s)
      case s => Cls.read(s)
    }

    implicit val ordering: Ordering[JType] = Ordering.by(_.pretty)
  }

  object Desc {

    private def isStartChar(c: Char) = (c: @switch) match {
      case 'B' | 'C' | 'D' | 'F' | 'I' | 'J' | 'S' | 'Z' | 'L' => true
      case _ => false
    }
    private[JvmModel] def read(s: String)(using st: SymbolTable): Desc = {
      val closeParenIndex = s.indexOf(')'.toInt)
      val args = Array.newBuilder[JType]
      var index = 1 // Skip index 0 which is the open paren '('
      while (index < closeParenIndex) {
        var firstChar = index
        while (!isStartChar(s.charAt(firstChar))) firstChar += 1
        var split = firstChar
        if (s.charAt(firstChar) == 'L') {
          while (s.charAt(split) != ';') split += 1
        }

        args.addOne(JType.read(s.substring(index, split + 1)))
        index = split + 1
      }
      new Desc(
        ArraySeq.unsafeWrapArray(args.result()),
        JType.read(s.substring(closeParenIndex + 1))
      )
    }

    implicit val ordering: Ordering[Desc] = Ordering.by(_.pretty)
  }

  /**
   * Represents the signature of a method.
   */
  class Desc private[JvmModel] (val args: Seq[JType], val ret: JType) {
    def pretty: String = "(" + args.map(_.pretty).mkString(",") + ")" + ret.pretty

    override def toString = pretty

    val stableHashCode: Int = (args, ret).hashCode
    override def hashCode() = stableHashCode
  }
}

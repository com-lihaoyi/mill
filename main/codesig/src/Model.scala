package mill.codesig

import mill.util.MultiBiMap
import upickle.default.{ReadWriter, readwriter}

case class MethodSig(cls: JType, static: Boolean, name: String, desc: String){
  override def toString = cls.name + (if(static) "." else "#") + name + desc
}
object MethodSig{
  implicit val ordering: Ordering[MethodSig] = Ordering.by(m => (m.cls, m.static, m.name, m.desc))
}
case class MethodCall(cls: JType, invokeType: InvokeType, name: String, desc: String)

sealed trait InvokeType
object InvokeType{
  case object Static extends InvokeType
  case object Virtual extends InvokeType
  case object Special extends InvokeType
}

case class Summary(callGraph: collection.mutable.Map[MethodSig, (Int, Set[MethodCall])],
                   directSubclasses: MultiBiMap[JType, JType],
                   directAncestors: Map[JType, Set[JType]])

case class JType(name: String){
  assert(!name.contains('/'), s"JType $name contains invalid '/' characters")
}

object JType{
  def fromSlashed(s: String) = JType(s.replace('/', '.'))
  implicit val rw: ReadWriter[JType] = readwriter[String].bimap(_.name, JType(_))
  implicit val ordering: Ordering[JType] = Ordering.by(_.name)
}

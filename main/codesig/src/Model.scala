package mill.codesig

import mill.util.MultiBiMap


case class MethodSig(cls: String, static: Boolean, name: String, desc: String){
  assert(!cls.contains('/'), "cls must be . delimited")
  override def toString = cls + (if(static) "." else "#") + name + desc
}
object MethodSig{
  implicit val ordering: Ordering[MethodSig] = Ordering.by(m => (m.cls, m.static, m.name, m.desc))
}
case class MethodCall(cls: String, invokeType: InvokeType, name: String, desc: String){
  assert(!cls.contains('/'), "cls must be . delimited")
}

sealed trait InvokeType
object InvokeType{
  case object Static extends InvokeType
  case object Virtual extends InvokeType
  case object Special extends InvokeType
}

case class Summary(callGraph: collection.mutable.Map[MethodSig, (Int, Set[MethodCall])],
                   directSubclasses: MultiBiMap[String, String],
                   directAncestors: Map[String, Set[String]])

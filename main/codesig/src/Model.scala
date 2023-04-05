package mill.codesig


case class MethodSig(cls: String, static: Boolean, name: String, desc: String){
  assert(!cls.contains('/'), "cls must be . delimited")
  override def toString = cls + (if(static) "." else "#") + name + desc
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

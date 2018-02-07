//package mill.main
//
//import mill.define.{BaseModule, Discover, ExternalModule}
//
////class ModuleScopt[T <: mill.Module, M <: BaseModule](rootModule: M, d: => Discover[M])
////  extends scopt.Read[Seq[T]]{
////  def arity = 1
////  def reads = s => {
////    val (expanded, Nil) = ParseArgs(Seq(s)).fold(e => throw new Exception(e), identity)
////    expanded.map{
////      case (Some(scoping), segments) =>
////        val moduleCls = rootModule.getClass.getClassLoader.loadClass(scoping.render + "$")
////        val externalRootModule = moduleCls.getField("MODULE$").get(moduleCls).asInstanceOf[ExternalModule]
////        externalRootModule.millInternal.segmentsToModules(segments).asInstanceOf[T]
////      case (None, segments) =>
////        rootModule.millInternal.segmentsToModules(segments).asInstanceOf[T]
////    }
////  }
////}

package mill.codesig

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import JType.{Cls => JCls}
import collection.JavaConverters._

/**
 * Walks the inheritance hierarchy of all classes that we extend in user code
 * but are defined externally, in order to discover all methods defined on
 * those external classes that have the potential to be over-ridden by
 * user-defined classes
 */
object ExternalSummarizer{

  case class Result(directMethods: Map[JCls, Set[MethodDef]],
                    directAncestors: Map[JCls, Set[JCls]],
                    directSuperclasses: Map[JCls, JCls])

  def loadAll(externalTypes: Set[JCls], loadClassNode: JCls => ClassNode): Result = {
    val ext = new ExternalSummarizer(loadClassNode)
    ext.loadAll(externalTypes)
    Result(ext.methodsPerCls.toMap, ext.ancestorsPerCls.toMap, ext.directSuperclasses.toMap)
  }
}

class ExternalSummarizer private(loadClassNode: JCls => ClassNode){
  val methodsPerCls = collection.mutable.Map.empty[JCls, Set[MethodDef]]
  val ancestorsPerCls = collection.mutable.Map.empty[JCls, Set[JCls]]
  val directSuperclasses = collection.mutable.Map.empty[JCls, JCls]

  def loadAll(externalTypes: Set[JCls]): Unit = {
    externalTypes.foreach(load)
  }

  def load(cls: JCls): Unit = methodsPerCls.getOrElse(cls, load0(cls))

  def load0(cls: JCls): Unit = {
    val cn = loadClassNode(cls)
    Option(cn.superName).foreach(sup =>
      directSuperclasses(cls) = JCls.fromSlashed(sup)
    )

    methodsPerCls(cls) = cn
      .methods
      .asScala
      .map{m => MethodDef((m.access & Opcodes.ACC_STATIC) != 0, m.name, Desc.read(m.desc))}
      .toSet

    ancestorsPerCls(cls) =
      (Option(cn.superName) ++ Option(cn.interfaces).toSeq.flatMap(_.asScala))
        .map(JCls.fromSlashed)
        .toSet

    ancestorsPerCls(cls).foreach(load)
  }
}

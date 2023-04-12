package mill.codesig

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

import collection.JavaConverters._

/**
 * Walks the inheritance hierarchy of all classes that we extend in user code
 * but are defined externally, in order to discover all methods defined on
 * those external classes that have the potential to be over-ridden by
 * user-defined classes
 */
object ExternalSummarizer{
  def loadAll(externalTypes: Set[JType.Cls], loadClassNode: JType.Cls => ClassNode): Result = {
    val ext = new ExternalSummarizer(loadClassNode)
    ext.loadAll(externalTypes)
    Result(ext.methodsPerCls.toMap, ext.ancestorsPerCls.toMap)
  }

  case class Result(directMethods: Map[JType.Cls, Set[LocalMethodSig]],
                    directAncestors: Map[JType.Cls, Set[JType.Cls]])
}

class ExternalSummarizer private(loadClassNode: JType.Cls => ClassNode){
  val methodsPerCls = collection.mutable.Map.empty[JType.Cls, Set[LocalMethodSig]]
  val ancestorsPerCls = collection.mutable.Map.empty[JType.Cls, Set[JType.Cls]]

  def loadAll(externalTypes: Set[JType.Cls]): Unit = {
    externalTypes.foreach(load)
  }

  def load(cls: JType.Cls): Unit = methodsPerCls.getOrElse(cls, load0(cls))

  def load0(cls: JType.Cls): Unit = {
    val cn = loadClassNode(cls)

    methodsPerCls(cls) = cn
      .methods
      .asScala
      .map{m => LocalMethodSig((m.access & Opcodes.ACC_STATIC) != 0, m.name, Desc.read(m.desc))}
      .toSet

    ancestorsPerCls(cls) =
      (Option(cn.superName) ++ Option(cn.interfaces).toSeq.flatMap(_.asScala))
        .map(JType.Cls.fromSlashed)
        .toSet

    ancestorsPerCls(cls).foreach(load)
  }
}

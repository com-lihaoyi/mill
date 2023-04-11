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
                    directAncestors: Map[JType.Cls, Set[JType.Cls]]){
    def transitiveMethods(tpe: JType.Cls): Set[LocalMethodSig] = {
      pprint.log(tpe)
      pprint.log(directMethods.getOrElse(tpe, Set.empty[LocalMethodSig]))
      pprint.log(directMethods)
      directMethods.getOrElse(tpe, Set.empty[LocalMethodSig]) ++
      directAncestors.getOrElse(tpe, Set.empty[JType.Cls]).flatMap(transitiveMethods)
    }
  }
}

class ExternalSummarizer private(loadClassNode: JType.Cls => ClassNode){
  val methodsPerCls = collection.mutable.Map.empty[JType.Cls, Set[LocalMethodSig]]
  val ancestorsPerCls = collection.mutable.Map.empty[JType.Cls, Set[JType.Cls]]

  def loadAll(externalTypes: Set[JType.Cls]) = {
    externalTypes.foreach(load)
  }

  def load(tpe: JType.Cls) = methodsPerCls.getOrElse(tpe, load0(tpe))

  def load0(tpe: JType.Cls) = {
    val cn = loadClassNode(tpe)

    methodsPerCls(tpe) = cn
      .methods
      .asScala
      .map{m => LocalMethodSig((m.access & Opcodes.ACC_STATIC) != 0, m.name, m.desc)}
      .toSet

    ancestorsPerCls(tpe) =
      (Option(cn.superName) ++ Option(cn.interfaces).toSeq.flatMap(_.asScala))
        .map(JType.Cls.fromSlashed)
        .toSet
  }
}

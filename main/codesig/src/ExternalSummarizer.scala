package mill.codesig

import org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}
import JType.{Cls => JCls}

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

  object Result {
    implicit def rw: upickle.default.ReadWriter[Result] = upickle.default.macroRW
  }

  def loadAll(externalTypes: Set[JCls], loadClassStream: JCls => java.io.InputStream): Result = {
    val ext = new ExternalSummarizer(loadClassStream)
    ext.loadAll(externalTypes)
    Result(ext.methodsPerCls.toMap, ext.ancestorsPerCls.toMap, ext.directSuperclasses.toMap)
  }
}

class ExternalSummarizer private(loadClassStream: JCls => java.io.InputStream){
  val methodsPerCls = collection.mutable.Map.empty[JCls, Set[MethodDef]]
  val ancestorsPerCls = collection.mutable.Map.empty[JCls, Set[JCls]]
  val directSuperclasses = collection.mutable.Map.empty[JCls, JCls]

  def loadAll(externalTypes: Set[JCls]): Unit = {
    externalTypes.foreach(load)
  }

  def load(cls: JCls): Unit = methodsPerCls.getOrElse(cls, load0(cls))

  def load0(cls: JCls): Unit = {
    new ClassReader(loadClassStream(cls)).accept(
      new ClassVisitor(Opcodes.ASM9) {
        override def visit(version: Int,
                           access: Int,
                           name: String,
                           signature: String,
                           superName: String,
                           interfaces: Array[String]): Unit = {

          Option(superName).foreach(sup =>
            directSuperclasses(cls) = JCls.fromSlashed(sup)
          )
          ancestorsPerCls(cls) =
            (Option(superName) ++ Option(interfaces).toSeq.flatten)
              .map(JCls.fromSlashed)
              .toSet
        }

        override def visitMethod(access: Int,
                                 name: String,
                                 descriptor: String,
                                 signature: String,
                                 exceptions: Array[String]): MethodVisitor = {

          methodsPerCls(cls) =
            methodsPerCls.getOrElse(cls, Set()) +
            MethodDef((access & Opcodes.ACC_STATIC) != 0, name, Desc.read(descriptor))

          new MethodVisitor(Opcodes.ASM9){}
        }
      },
      0)

    ancestorsPerCls(cls).foreach(load)

  }
}

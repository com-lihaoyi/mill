package mill.codesig

import org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}
import JvmModel._
import JType.{Cls => JCls}
/**
 * Walks the inheritance hierarchy of all classes that we extend in user code
 * but are defined externally, in order to discover all methods defined on
 * those external classes that have the potential to be over-ridden by
 * user-defined classes
 */
object ExternalSummarizer {

  case class Result(
      directMethods: Map[JCls, Set[MethodSig]],
      directAncestors: Map[JCls, Set[JCls]],
      directSuperclasses: Map[JCls, JCls]
  )

  object Result {
    implicit def rw(implicit st: SymbolTable): upickle.default.ReadWriter[Result] =
      upickle.default.macroRW
  }

  def loadAll(externalTypes: Set[JCls], loadClassStream: JCls => java.io.InputStream)(implicit
      st: SymbolTable
  ): Result = {
    val ext = new ExternalSummarizer(loadClassStream)
    ext.loadAll(externalTypes)
    Result(ext.methodsPerCls.toMap, ext.ancestorsPerCls.toMap, ext.directSuperclasses.toMap)
  }
}

class ExternalSummarizer private (loadClassStream: JCls => java.io.InputStream)(implicit
    st: SymbolTable
) {
  val methodsPerCls = collection.mutable.Map.empty[JCls, Set[MethodSig]]
  val ancestorsPerCls = collection.mutable.Map.empty[JCls, Set[JCls]]
  val directSuperclasses = collection.mutable.Map.empty[JCls, JCls]

  def loadAll(externalTypes: Set[JCls]): Unit = externalTypes.foreach(load)
  def load(cls: JCls): Unit = methodsPerCls.getOrElse(cls, load0(cls))

  def load0(cls: JCls): Unit = {
    val visitor = new MyClassVisitor()
    new ClassReader(loadClassStream(cls)).accept(
      visitor,
      ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG
    )
    directSuperclasses(cls) = visitor.superclass
    methodsPerCls(cls) = visitor.methods
    ancestorsPerCls(cls) = visitor.ancestors
    ancestorsPerCls(cls).foreach(load)
  }

  class MyClassVisitor extends ClassVisitor(Opcodes.ASM9) {
    var methods: Set[MethodSig] = Set()
    var ancestors: Set[JCls] = null
    var superclass: JCls = null
    override def visit(
        version: Int,
        access: Int,
        name: String,
        signature: String,
        superName: String,
        interfaces: Array[String]
    ): Unit = {

      Option(superName).foreach(sup => superclass = JCls.fromSlashed(sup))
      ancestors =
        (Option(superName) ++ Option(interfaces).toSeq.flatten)
          .map(JCls.fromSlashed)
          .toSet
    }

    override def visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String,
        exceptions: Array[String]
    ): MethodVisitor = {

      methods += st.MethodSig((access & Opcodes.ACC_STATIC) != 0, name, st.Desc.read(descriptor))

      new MethodVisitor(Opcodes.ASM9) {}
    }
  }
}

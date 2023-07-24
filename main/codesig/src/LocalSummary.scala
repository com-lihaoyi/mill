package mill.codesig

import org.objectweb.asm.{ClassReader, ClassVisitor, Handle, Label, MethodVisitor, Opcodes}
import JvmModel._
import JType.{Cls => JCls}
import mill.codesig.LocalSummary.ClassInfo
import upickle.default.{ReadWriter, macroRW}

case class LocalSummary(items: Map[JCls, ClassInfo]) {
  def get(cls: JCls, m: MethodSig): Option[LocalSummary.MethodInfo] =
    items.get(cls).flatMap(_.methods.get(m))

  def mapValues[T](f: ClassInfo => T): Map[JCls, T] = items.map { case (k, v) => (k, f(v)) }

  def mapValuesOnly[T](f: ClassInfo => T): Seq[T] = items.map { case (k, v) => f(v) }.toSeq

  def contains(cls: JCls) = items.contains(cls)
}

/**
 * Parses over the Java bytecode and creates a [[Summary]] object, which
 * contains the key information needed for call-graph analysis and method hash
 * computation.
 */
object LocalSummary {

  case class ClassInfo(
      superClass: JCls,
      directAncestors: Set[JCls],
      methods: Map[MethodSig, MethodInfo]
  )
  object ClassInfo {
    implicit def rw(implicit st: SymbolTable): ReadWriter[ClassInfo] = macroRW
  }
  case class MethodInfo(
      calls: Set[MethodCall],
      isPrivate: Boolean,
      codeHash: Int,
      isAbstract: Boolean
  )
  object MethodInfo {
    implicit def rw(implicit st: SymbolTable): ReadWriter[MethodInfo] = macroRW
  }

  implicit def rw(implicit st: SymbolTable): ReadWriter[LocalSummary] = macroRW

  def apply(classStreams: Iterator[java.io.InputStream])(implicit st: SymbolTable): LocalSummary = {
    val visitors = classStreams
      .map { cs =>
        val visitor = new MyClassVisitor()
        new ClassReader(cs).accept(visitor, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG)
        visitor
      }
      .toVector

    LocalSummary(
      visitors
        .map { v =>
          val cls = v.clsType
          val methodCallGraphs = v.classCallGraph.result()
          val methodHashes = v.classMethodHashes.result()
          val methodPrivate = v.classMethodPrivate.result()
          val methodAbstract = v.classMethodAbstract.result()
          cls -> ClassInfo(
            superClass = v.directSuperClass.get,
            directAncestors = v.directAncestors,
            methods = methodCallGraphs
              .keys
              .map { m =>
                m -> MethodInfo(
                  methodCallGraphs(m),
                  methodPrivate(m),
                  methodHashes(m),
                  methodAbstract(m)
                )
              }
              .toMap
          )
        }
        .toMap
    )
  }

  class MyClassVisitor()(implicit st: SymbolTable) extends ClassVisitor(Opcodes.ASM9) {
    val classCallGraph = Map.newBuilder[MethodSig, Set[MethodCall]]
    val classMethodHashes = Map.newBuilder[MethodSig, Int]
    val classMethodPrivate = Map.newBuilder[MethodSig, Boolean]
    val classMethodAbstract = Map.newBuilder[MethodSig, Boolean]
    var clsType: JCls = null
    var directSuperClass: Option[JCls] = None
    var directAncestors: Set[JCls] = Set()

    override def visit(
        version: Int,
        access: Int,
        name: String,
        signature: String,
        superName: String,
        interfaces: Array[String]
    ): Unit = {
      clsType = JCls.fromSlashed(name)
      directSuperClass = Option(superName).map(JCls.fromSlashed)
      directAncestors =
        (Option(superName) ++ Option(interfaces).toSeq.flatten).toSet.map(JCls.fromSlashed)
    }

    override def visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String,
        exceptions: Array[String]
    ): MethodVisitor = {
      new MyMethodVisitor(clsType, this, name, descriptor, access)
    }

    override def visitEnd(): Unit = {}
  }

  class MyMethodVisitor(
      currentCls: JCls,
      clsVisitor: MyClassVisitor,
      name: String,
      descriptor: String,
      access: Int
  )(implicit st: SymbolTable) extends MethodVisitor(Opcodes.ASM9) {
    val outboundCalls = collection.mutable.Set.empty[MethodCall]
    val labelIndices = collection.mutable.Map.empty[Label, Int]
    val jumpList = collection.mutable.Buffer.empty[Label]

    val methodSig = st.MethodSig(
      (access & Opcodes.ACC_STATIC) != 0,
      name,
      st.Desc.read(descriptor)
    )

    val insnSigs = collection.mutable.ArrayBuffer.empty[Int]

    var insnHash = 0

    def hash(x: Int): Unit = insnHash = scala.util.hashing.MurmurHash3.mix(insnHash, x)

    def completeHash() = {
      insnSigs.append(scala.util.hashing.MurmurHash3.finalizeHash(0, insnHash))
      insnHash = 0
    }

    def clinitCall(desc: String) = JType.read(desc) match {
      case descCls: JType.Cls if descCls != currentCls =>
        storeCallEdge(
          st.MethodCall(descCls, InvokeType.Static, "<clinit>", st.Desc.read("()V"))
        )
      case _ => // donothing
    }

    def storeCallEdge(x: MethodCall): Unit = outboundCalls.add(x)

    def hashlabel(x: Label): Unit = jumpList.append(x)

    def discardPreviousInsn(): Unit = insnSigs(insnSigs.size - 1) = 0

    override def visitFieldInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String
    ): Unit = {
      hash(opcode)
      hash(owner.hashCode)
      hash(name.hashCode)
      hash(descriptor.hashCode)
      completeHash()
      clinitCall(owner)
    }

    override def visitIincInsn(varIndex: Int, increment: Int): Unit = {
      hash(varIndex)
      hash(increment)
      completeHash()
    }

    override def visitInsn(opcode: Int): Unit = {
      hash(opcode)
      completeHash()
    }

    override def visitIntInsn(opcode: Int, operand: Int): Unit = {
      hash(opcode)
      hash(operand)
      completeHash()
    }

    override def visitInvokeDynamicInsn(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        bootstrapMethodArguments: Object*
    ): Unit = {
      for (bsmArg <- bootstrapMethodArguments) {
        bsmArg match {
          case handle: Handle =>
            val refOpt = handle.getTag match {
              case Opcodes.H_INVOKEVIRTUAL => Some((InvokeType.Virtual, handle.getName))
              case Opcodes.H_INVOKESTATIC => Some((InvokeType.Static, handle.getName))
              case Opcodes.H_INVOKESPECIAL => Some((InvokeType.Special, handle.getName))
              case Opcodes.H_NEWINVOKESPECIAL => Some((InvokeType.Special, "<init>"))
              case Opcodes.H_INVOKEINTERFACE => Some((InvokeType.Virtual, handle.getName))
              case _ => None
            }
            for ((invokeType, name) <- refOpt) storeCallEdge(
              st.MethodCall(
                JCls.fromSlashed(handle.getOwner),
                invokeType,
                name,
                st.Desc.read(handle.getDesc)
              )
            )
          case _ =>
        }
      }
      completeHash()
    }

    override def visitJumpInsn(opcode: Int, label: Label): Unit = {
      hashlabel(label)
      hash(opcode)
      completeHash()
    }

    override def visitLabel(label: Label): Unit = {
      labelIndices(label) = insnSigs.size
    }

    override def visitLdcInsn(value: Any): Unit = {
      hash(
        value match {
          case v: java.lang.String => v.hashCode()
          case v: java.lang.Integer => v.hashCode()
          case v: java.lang.Float => v.hashCode()
          case v: java.lang.Long => v.hashCode()
          case v: java.lang.Double => v.hashCode()
          case v: org.objectweb.asm.Type => v.hashCode()
          case v: org.objectweb.asm.Handle => v.hashCode()
          case v: org.objectweb.asm.ConstantDynamic => v.hashCode()
        }
      )
      completeHash()
    }

    override def visitLookupSwitchInsn(
        dflt: Label,
        keys: Array[Int],
        labels: Array[Label]
    ): Unit = {
      keys.foreach(hash)
      labels.foreach(hashlabel)
      Option(dflt).foreach(hashlabel)
      completeHash()
    }

    override def visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean
    ): Unit = {
      val call = st.MethodCall(
        JCls.fromSlashed(owner),
        opcode match {
          case Opcodes.INVOKESTATIC => InvokeType.Static
          case Opcodes.INVOKESPECIAL => InvokeType.Special
          case Opcodes.INVOKEVIRTUAL => InvokeType.Virtual
          case Opcodes.INVOKEINTERFACE => InvokeType.Virtual
        },
        name,
        st.Desc.read(descriptor)
      )

      // HACK: we skip any constants that get passed to `sourcecode.Line()`,
      // because we use that extensively in definig Mill targets but it is
      // generally not something we want to affect the output of a build
      val sourcecodeLineCall = st.MethodCall(
        JCls.fromSlashed("sourcecode/Line"),
        InvokeType.Special,
        "<init>",
        st.Desc.read("(I)V")
      )
      if (call == sourcecodeLineCall) discardPreviousInsn()

      hash(opcode)
      hash(name.hashCode)
      hash(owner.hashCode)
      hash(descriptor.hashCode)
      hash(isInterface.hashCode)

      storeCallEdge(call)
      clinitCall(owner)
      completeHash()
    }

    override def visitMultiANewArrayInsn(descriptor: String, numDimensions: Int): Unit = {
      hash(descriptor.hashCode)
      hash(numDimensions)
      completeHash()
    }

    override def visitTableSwitchInsn(min: Int, max: Int, dflt: Label, labels: Label*): Unit = {
      hash(min)
      hash(max)
      labels.foreach(hashlabel)
      Option(dflt).foreach(hashlabel)
      completeHash()
    }

    override def visitTypeInsn(opcode: Int, `type`: String): Unit = {
      clinitCall(`type`)
      hash(`type`.hashCode)
      hash(opcode)
      completeHash()
    }

    override def visitVarInsn(opcode: Int, varIndex: Int): Unit = {
      hash(varIndex)
      hash(opcode)
      completeHash()
    }

    override def visitEnd(): Unit = {
      clsVisitor.classCallGraph.addOne((methodSig, outboundCalls.toSet))
      clsVisitor.classMethodHashes.addOne((
        methodSig,
        insnSigs.hashCode() + jumpList.map(labelIndices).hashCode()
      ))
      clsVisitor.classMethodPrivate.addOne((methodSig, (access & Opcodes.ACC_PRIVATE) != 0))
      clsVisitor.classMethodAbstract.addOne((methodSig, (access & Opcodes.ACC_ABSTRACT) != 0))

    }
  }
}

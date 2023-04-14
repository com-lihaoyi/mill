package mill.codesig

import org.objectweb.asm.{ClassReader, ClassVisitor, Handle, Label, MethodVisitor, Opcodes}
import JType.{Cls => JCls}

/**
 * Parses over the Java bytecode and creates a [[Summary]] object, which
 * contains the key information needed for call-graph analysis and method hash
 * computation.
 */
object LocalSummarizer{

  case class Result(callGraph: Map[JCls, Map[MethodDef, Set[MethodCall]]],
                    methodHashes: Map[JCls, Map[MethodDef, Int]],
                    methodPrivate: Map[JCls, Map[MethodDef, Boolean]],
                    directSuperclasses: Map[JCls, JCls],
                    directAncestors: Map[JCls, Set[JCls]])
  object Result{
    implicit def rw: upickle.default.ReadWriter[Result] = upickle.default.macroRW
  }

  def summarize(classStreams: Iterator[java.io.InputStream]) = {

    val directSuperclasses = Map.newBuilder[JCls, JCls]
    val callGraph = Map.newBuilder[JCls, Map[MethodDef, Set[MethodCall]]]
    val methodHashes = Map.newBuilder[JCls, Map[MethodDef, Int]]
    val methodPrivate = Map.newBuilder[JCls, Map[MethodDef, Boolean]]
    val directAncestors = Map.newBuilder[JCls, Set[JCls]]

    for (cs <- classStreams) {

      val visitor = new MyClassVisitor()
      new ClassReader(cs).accept(visitor, 0)
      callGraph.addOne((visitor.clsType, visitor.classCallGraph.result()))
      methodHashes.addOne((visitor.clsType, visitor.classMethodHashes.result()))
      methodPrivate.addOne((visitor.clsType, visitor.classMethodPrivate.result()))
      directAncestors.addOne((visitor.clsType, visitor.directAncestors))
      directSuperclasses.addOne((visitor.clsType, visitor.directSuperClass.get))
    }

    Result(
      callGraph.result(),
      methodHashes.result(),
      methodPrivate.result(),
      directSuperclasses.result(),
      directAncestors.result()
    )
  }

  class MyClassVisitor extends ClassVisitor(Opcodes.ASM9) {
    val classCallGraph = Map.newBuilder[MethodDef, Set[MethodCall]]
    val classMethodHashes = Map.newBuilder[MethodDef, Int]
    val classMethodPrivate = Map.newBuilder[MethodDef, Boolean]
    var clsType: JCls = null
    var directSuperClass: Option[JCls] = None
    var directAncestors: Set[JCls] = Set()

    override def visit(version: Int,
                       access: Int,
                       name: String,
                       signature: String,
                       superName: String,
                       interfaces: Array[String]): Unit = {
      clsType = JCls.fromSlashed(name)
      directSuperClass = Option(superName).map(JCls.fromSlashed)
      directAncestors = (Option(superName) ++ Option(interfaces).toSeq.flatten).toSet.map(JCls.fromSlashed)
    }

    override def visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {
      new MyMethodVisitor(clsType, this, name, descriptor, access)
    }

    override def visitEnd(): Unit = {
    }
  }

  class MyMethodVisitor(currentCls: JCls,
                        clsVisitor: MyClassVisitor,
                        name: String,
                        descriptor: String,
                        access: Int) extends MethodVisitor(Opcodes.ASM9) {
    val outboundCalls = collection.mutable.Set.empty[MethodCall]
    val labelIndices = collection.mutable.Map.empty[Label, Int]
    val jumpList = collection.mutable.Buffer.empty[Label]

    val methodSig = MethodDef(
      (access & Opcodes.ACC_STATIC) != 0,
      name,
      Desc.read(descriptor),
    )

    val insnSigs = collection.mutable.ArrayBuffer.empty[Int]

    var insnHash = 0

    def hash(x: Int): Unit = insnHash = insnHash * 13 + x
    def completeHash() = {
      insnSigs.append(insnHash)
      insnHash = 0
    }

    def clinitCall(desc: String) = JType.read(desc) match {
      case descCls: JType.Cls if descCls != currentCls =>
        storeCallEdge(
          MethodCall(descCls, InvokeType.Static, "<clinit>", Desc.read("()V"))
        )
      case _ => //donothing
    }

    def storeCallEdge(x: MethodCall): Unit = outboundCalls.add(x)

    def hashlabel(x: Label): Unit = jumpList.append(x)

    def discardPreviousInsn(): Unit = insnSigs(insnSigs.size - 1) = 0

    override def visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String): Unit = {
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

    override def visitInvokeDynamicInsn(name: String,
                                        descriptor: String,
                                        bootstrapMethodHandle: Handle,
                                        bootstrapMethodArguments: Object*): Unit = {
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
              MethodCall(
                JCls.fromSlashed(handle.getOwner),
                invokeType,
                name,
                Desc.read(handle.getDesc)
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

    override def visitLookupSwitchInsn(dflt: Label, keys: Array[Int], labels: Array[Label]): Unit = {
      keys.foreach(hash)
      labels.foreach(hashlabel)
      Option(dflt).foreach(hashlabel)
      completeHash()
    }

    override def visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean): Unit = {
      val call = MethodCall(
        JCls.fromSlashed(owner),
        opcode match {
          case Opcodes.INVOKESTATIC => InvokeType.Static
          case Opcodes.INVOKESPECIAL => InvokeType.Special
          case Opcodes.INVOKEVIRTUAL => InvokeType.Virtual
          case Opcodes.INVOKEINTERFACE => InvokeType.Virtual
        },
        name,
        Desc.read(descriptor)
      )

      // HACK: we skip any constants that get passed to `sourcecode.Line()`,
      // because we use that extensively in definig Mill targets but it is
      // generally not something we want to affect the output of a build
      val sourcecodeLineCall = MethodCall(
        JCls.fromSlashed("sourcecode/Line"),
        InvokeType.Special,
        "<init>",
        Desc.read("(I)V")
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
      clsVisitor.classMethodHashes.addOne((methodSig, insnSigs.hashCode() + jumpList.map(labelIndices).hashCode()))
      clsVisitor.classMethodPrivate.addOne((methodSig, (access & Opcodes.ACC_PRIVATE) != 0))
    }
  }
}

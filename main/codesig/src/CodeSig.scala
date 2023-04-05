package mill.codesig
import org.objectweb.asm.{ClassReader, Opcodes}
import org.objectweb.asm.tree.{AbstractInsnNode, ClassNode, FieldInsnNode, FrameNode, IincInsnNode, InsnNode, IntInsnNode, InvokeDynamicInsnNode, JumpInsnNode, LabelNode, LdcInsnNode, LineNumberNode, LookupSwitchInsnNode, MethodInsnNode, MultiANewArrayInsnNode, TableSwitchInsnNode, TypeInsnNode, VarInsnNode}

import collection.JavaConverters._

object CodeSig{
  def process(classPathClasses: Seq[Array[Byte]]) = {
    val callGraph = collection.mutable.Map.empty[MethodSig, (Int, Set[MethodCall])]
    val classNodes = classPathClasses.map(loadClass)
    for(classNode <- classNodes){
      for(method <- classNode.methods.asScala){
        val outboundCalls = collection.mutable.Set.empty[MethodCall]
        val methodSig = MethodSig(
          classNode.name.replace('/', '.'),
          (method.access & Opcodes.ACC_STATIC) != 0,
          method.name,
          method.desc,
        )

        val insnSigs = collection.mutable.ArrayBuffer.empty[Int]

        val labelIndices = getLabelRealIndices(method.instructions.toArray)

        for (insn <- method.instructions.asScala){
          processInstruction(
            insn,
            labelIndices,
            insnSigs.append,
            outboundCalls.add
          )
          insnSigs.append(insn.getOpcode)
        }

        callGraph(methodSig) = (insnSigs.hashCode(), outboundCalls.toSet)
      }
    }
    callGraph
  }

  def processInstruction(insn: AbstractInsnNode,
                         labelIndices: Map[LabelNode, Int],
                         hash: Int => Unit,
                         storeCallEdge: MethodCall => Unit) = {
    def hashlabel(label: LabelNode) = hash(labelIndices(label))

    insn match {
      case insn: FieldInsnNode =>
        hash(insn.desc.hashCode)
        hash(insn.name.hashCode)
        hash(insn.owner.hashCode)
      case insn: FrameNode =>
      case insn: IincInsnNode =>
        hash(insn.`var`)
        hash(insn.incr)
      case insn: InsnNode =>
      case insn: IntInsnNode => hash(insn.operand)
      case insn: InvokeDynamicInsnNode => ???
      case insn: JumpInsnNode => hashlabel(insn.label)

      case insn: LabelNode =>
      case insn: LdcInsnNode =>
        hash(
          insn.cst match {
            case v: java.lang.Integer => v.hashCode()
            case v: java.lang.Float => v.hashCode()
            case v: java.lang.Long => v.hashCode()
            case v: java.lang.Double => v.hashCode()
            case v: org.objectweb.asm.Type => v.hashCode()
            case v: org.objectweb.asm.Handle => v.hashCode()
            case v: org.objectweb.asm.ConstantDynamic => v.hashCode()
          }
        )
      case insn: LineNumberNode =>
      case insn: LookupSwitchInsnNode =>
        insn.keys.asScala.foreach(i => hash(i.toInt))
        insn.labels.asScala.foreach(hashlabel)
        Option(insn.dflt).foreach(hashlabel)

      case insn: MethodInsnNode =>
        hash(insn.name.hashCode)
        hash(insn.owner.hashCode)
        hash(insn.desc.hashCode)
        hash(insn.itf.hashCode)

        storeCallEdge(
          MethodCall(
            insn.owner.replace('/', '.'),
            insn.getOpcode match{
              case Opcodes.INVOKESTATIC => InvokeType.Static
              case Opcodes.INVOKESPECIAL => InvokeType.Special
              case Opcodes.INVOKEVIRTUAL => InvokeType.Virtual
              case Opcodes.INVOKEINTERFACE => InvokeType.Virtual
            },
            insn.name,
            insn.desc
          )
        )

      case insn: MultiANewArrayInsnNode =>
        hash(insn.desc.hashCode)
        hash(insn.dims)
      case insn: TableSwitchInsnNode =>
        hash(insn.min)
        hash(insn.max)
        insn.labels.asScala.foreach(hashlabel)
        Option(insn.dflt).foreach(hashlabel)

      case insn: TypeInsnNode => hash(insn.desc.hashCode)
      case insn: VarInsnNode => hash(insn.`var`)
    }

  }

  def getLabelRealIndices(insns: Seq[AbstractInsnNode]) = {
    var index = 0
    insns
      .flatMap {
        case f: FrameNode => None
        case l: LineNumberNode => None
        case l: LabelNode => Some(l -> index)
        case _ =>
          index += 1
          None
      }
      .toMap
  }

  def loadClass(bytes: Array[Byte]) = {
    val classReader = new ClassReader(bytes)
    val classNode = new ClassNode()
    classReader.accept(classNode, 0)
    classNode
  }
}

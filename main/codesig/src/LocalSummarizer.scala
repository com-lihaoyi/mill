package mill.codesig
import mill.util.MultiBiMap

import collection.JavaConverters._
import org.objectweb.asm.{ClassReader, Handle, Opcodes}
import org.objectweb.asm.tree.{AbstractInsnNode, ClassNode, FieldInsnNode, FrameNode, IincInsnNode, InsnNode, IntInsnNode, InvokeDynamicInsnNode, JumpInsnNode, LabelNode, LdcInsnNode, LineNumberNode, LookupSwitchInsnNode, MethodInsnNode, MultiANewArrayInsnNode, TableSwitchInsnNode, TypeInsnNode, VarInsnNode}

/**
 * Parses over the Java bytecode and creates a [[Summary]] object, which
 * contains the key information needed for call-graph analysis and method hash
 * computation.
 */
object LocalSummarizer{

  case class Result(callGraph: Map[JType.Cls, Map[MethodDef, Set[MethodCall]]],
                    methodHashes: Map[JType.Cls, Map[MethodDef, Int]],
                    directSubclasses: MultiBiMap[JType.Cls, JType.Cls],
                    directAncestors: Map[JType.Cls, Set[JType.Cls]])

  def summarize(classNodes: Seq[ClassNode]) = {

    val directSubclasses = new MultiBiMap.Mutable[JType.Cls, JType.Cls]()
    val callGraph = Map.newBuilder[JType.Cls, Map[MethodDef, Set[MethodCall]]]
    val methodHashes = Map.newBuilder[JType.Cls, Map[MethodDef, Int]]
    val directAncestors = Map.newBuilder[JType.Cls, Set[JType.Cls]]

    for(cn <- classNodes){
      val classCallGraph = Map.newBuilder[MethodDef, Set[MethodCall]]
      val classMethodHashes = Map.newBuilder[MethodDef, Int]
      val clsType = JType.Cls.fromSlashed(cn.name)
      Option(cn.superName).foreach(sup =>
        directSubclasses.add(JType.Cls.fromSlashed(sup), clsType)
      )

      val clsDirectAncestors = Option(cn.superName) ++ Option(cn.interfaces).toSeq.flatMap(_.asScala)

      for(method <- cn.methods.asScala){
        val outboundCalls = collection.mutable.Set.empty[MethodCall]
        val methodSig = MethodDef(
          (method.access & Opcodes.ACC_STATIC) != 0,
          method.name,
          Desc.read(method.desc),
        )

        val insnSigs = collection.mutable.ArrayBuffer.empty[Int]

        val labelIndices = getLabelRealIndices(method.instructions.toArray)

        for (insn <- method.instructions.asScala){
          processInstruction(
            insn,
            labelIndices,
            insnSigs.append,
            outboundCalls.add,
            clsType
          )
          insnSigs.append(insn.getOpcode)
        }

        classCallGraph.addOne((methodSig, outboundCalls.toSet))
        classMethodHashes.addOne((methodSig, insnSigs.hashCode()))
      }

      callGraph.addOne((clsType, classCallGraph.result()))
      methodHashes.addOne((clsType, classMethodHashes.result()))
      directAncestors.addOne((clsType, clsDirectAncestors.toSet.map(JType.Cls.fromSlashed)))
    }

    Result(callGraph.result(), methodHashes.result(), directSubclasses, directAncestors.result())
  }

  def processInstruction(insn: AbstractInsnNode,
                         labelIndices: Map[LabelNode, Int],
                         hash: Int => Unit,
                         storeCallEdge: MethodCall => Unit,
                         currentCls: JType.Cls) = {
    def hashlabel(label: LabelNode) = hash(labelIndices(label))

    def clinitCall(desc: String) = {
      val descCls = JType.Cls.fromSlashed(desc)
      if (descCls != currentCls) storeCallEdge(
        MethodCall(descCls, InvokeType.Static, "<clinit>", Desc.read("()V"))
      )
    }

    insn match {
      case insn: FieldInsnNode =>
        hash(insn.desc.hashCode)
        hash(insn.name.hashCode)
        hash(insn.owner.hashCode)
        clinitCall(insn.owner)

      case insn: FrameNode =>

      case insn: IincInsnNode =>
        hash(insn.`var`)
        hash(insn.incr)

      case insn: InsnNode =>

      case insn: IntInsnNode => hash(insn.operand)

      case insn: InvokeDynamicInsnNode =>
        for(bsmArg <- insn.bsmArgs){
          bsmArg match{
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
                  JType.Cls.fromSlashed(handle.getOwner),
                  invokeType,
                  name,
                  Desc.read(handle.getDesc)
                )
              )
            case _ =>
          }
        }

      case insn: JumpInsnNode => hashlabel(insn.label)

      case insn: LabelNode =>

      case insn: LdcInsnNode =>
        hash(
          insn.cst match {
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
            JType.Cls.fromSlashed(insn.owner),
            insn.getOpcode match{
              case Opcodes.INVOKESTATIC => InvokeType.Static
              case Opcodes.INVOKESPECIAL => InvokeType.Special
              case Opcodes.INVOKEVIRTUAL => InvokeType.Virtual
              case Opcodes.INVOKEINTERFACE => InvokeType.Virtual
            },
            insn.name,
            Desc.read(insn.desc)
          )
        )
        clinitCall(insn.owner)


      case insn: MultiANewArrayInsnNode =>
        hash(insn.desc.hashCode)
        hash(insn.dims)

      case insn: TableSwitchInsnNode =>
        hash(insn.min)
        hash(insn.max)
        insn.labels.asScala.foreach(hashlabel)
        Option(insn.dflt).foreach(hashlabel)

      case insn: TypeInsnNode =>
        clinitCall(insn.desc)

        hash(insn.desc.hashCode)

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

}

package mill.codesig

import collection.JavaConverters._
import org.objectweb.asm.{Handle, Opcodes}
import org.objectweb.asm.tree.{AbstractInsnNode, ClassNode, FieldInsnNode, FrameNode, IincInsnNode, InsnNode, IntInsnNode, InvokeDynamicInsnNode, JumpInsnNode, LabelNode, LdcInsnNode, LineNumberNode, LookupSwitchInsnNode, MethodInsnNode, MultiANewArrayInsnNode, TableSwitchInsnNode, TypeInsnNode, VarInsnNode}
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

  def summarize(classNodes: Seq[ClassNode]) = {

    val directSuperclasses = Map.newBuilder[JCls, JCls]
    val callGraph = Map.newBuilder[JCls, Map[MethodDef, Set[MethodCall]]]
    val methodHashes = Map.newBuilder[JCls, Map[MethodDef, Int]]
    val methodPrivate = Map.newBuilder[JCls, Map[MethodDef, Boolean]]
    val directAncestors = Map.newBuilder[JCls, Set[JCls]]

    for(cn <- classNodes){
      val classCallGraph = Map.newBuilder[MethodDef, Set[MethodCall]]
      val classMethodHashes = Map.newBuilder[MethodDef, Int]
      val classMethodPrivate = Map.newBuilder[MethodDef, Boolean]
      val clsType = JCls.fromSlashed(cn.name)
      Option(cn.superName).foreach(sup =>
        directSuperclasses.addOne((clsType, JCls.fromSlashed(sup)))
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
            clsType,
            () => insnSigs.remove(insnSigs.size-1)
          )

        }

        classCallGraph.addOne((methodSig, outboundCalls.toSet))
        classMethodHashes.addOne((methodSig, insnSigs.hashCode()))
        classMethodPrivate.addOne((methodSig, (method.access & Opcodes.ACC_PRIVATE) != 0))
      }

      callGraph.addOne((clsType, classCallGraph.result()))
      methodHashes.addOne((clsType, classMethodHashes.result()))
      methodPrivate.addOne((clsType, classMethodPrivate.result()))
      directAncestors.addOne((clsType, clsDirectAncestors.toSet.map(JCls.fromSlashed)))
    }

    Result(
      callGraph.result(),
      methodHashes.result(),
      methodPrivate.result(),
      directSuperclasses.result(),
      directAncestors.result()
    )
  }

  def processInstruction(insn: AbstractInsnNode,
                         labelIndices: Map[LabelNode, Int],
                         hash: Int => Unit,
                         storeCallEdge: MethodCall => Unit,
                         currentCls: JCls,
                         discardPrevious: () => Unit) = {
    def hashlabel(label: LabelNode) = hash(labelIndices(label))

    def clinitCall(desc: String) = JType.read(desc) match{
      case descCls: JType.Cls if descCls != currentCls =>
        storeCallEdge(
          MethodCall(descCls, InvokeType.Static, "<clinit>", Desc.read("()V"))
        )
      case _ => //donothing
    }

    insn match {
      case insn: FieldInsnNode =>
        hash(insn.desc.hashCode)
        hash(insn.name.hashCode)
        hash(insn.owner.hashCode)
        hash(insn.getOpcode)
        clinitCall(insn.owner)

      case insn: FrameNode =>

      case insn: IincInsnNode =>
        hash(insn.`var`)
        hash(insn.incr)
        hash(insn.getOpcode)

      case insn: InsnNode =>

      case insn: IntInsnNode =>
        hash(insn.operand)
        hash(insn.getOpcode)

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
                  JCls.fromSlashed(handle.getOwner),
                  invokeType,
                  name,
                  Desc.read(handle.getDesc)
                )
              )
            case _ =>
          }
        }
        hash(insn.getOpcode)

      case insn: JumpInsnNode =>
        hashlabel(insn.label)
        hash(insn.getOpcode)

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
        hash(insn.getOpcode)

      case insn: LineNumberNode =>

      case insn: LookupSwitchInsnNode =>
        insn.keys.asScala.foreach(i => hash(i.toInt))
        insn.labels.asScala.foreach(hashlabel)
        Option(insn.dflt).foreach(hashlabel)
        hash(insn.getOpcode)

      case insn: MethodInsnNode =>
        val call = MethodCall(
          JCls.fromSlashed(insn.owner),
          insn.getOpcode match {
            case Opcodes.INVOKESTATIC => InvokeType.Static
            case Opcodes.INVOKESPECIAL => InvokeType.Special
            case Opcodes.INVOKEVIRTUAL => InvokeType.Virtual
            case Opcodes.INVOKEINTERFACE => InvokeType.Virtual
          },
          insn.name,
          Desc.read(insn.desc)
        )

//        if (call == MethodCall(JCls.fromSlashed("sourcecode/Line"), InvokeType.Special, "<init>", Desc.read("(I)V"))) {
//          discardPrevious()
//        }

        hash(insn.name.hashCode)
        hash(insn.owner.hashCode)
        hash(insn.desc.hashCode)
        hash(insn.itf.hashCode)

        storeCallEdge(call)
        clinitCall(insn.owner)
        hash(insn.getOpcode)

      case insn: MultiANewArrayInsnNode =>
        hash(insn.desc.hashCode)
        hash(insn.dims)
        hash(insn.getOpcode)

      case insn: TableSwitchInsnNode =>
        hash(insn.min)
        hash(insn.max)
        insn.labels.asScala.foreach(hashlabel)
        Option(insn.dflt).foreach(hashlabel)
        hash(insn.getOpcode)

      case insn: TypeInsnNode =>
        clinitCall(insn.desc)

        hash(insn.desc.hashCode)
        hash(insn.getOpcode)

      case insn: VarInsnNode =>
        hash(insn.`var`)
        hash(insn.getOpcode)
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

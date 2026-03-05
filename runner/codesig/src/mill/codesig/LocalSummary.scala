package mill.codesig

import mill.codesig.JvmModel.*
import mill.codesig.JvmModel.JType.Cls as JCls
import mill.codesig.LocalSummary.ClassInfo
import org.objectweb.asm.*
import upickle.{ReadWriter, macroRW}

import scala.collection.mutable

case class LocalSummary(items: Map[JCls, ClassInfo]) {
  def get(cls: JCls, m: MethodSig): Option[LocalSummary.MethodInfo] =
    items.get(cls).flatMap(_.methods.get(m))

  def mapValues[T](f: ClassInfo => T): Map[JCls, T] = items.map { case (k, v) => (k, f(v)) }

  def mapValuesOnly[T](f: ClassInfo => T): Seq[T] = items.map { case (_, v) => f(v) }.toSeq

  def contains(cls: JCls): Boolean = items.contains(cls)
}

/**
 * Parses over the Java bytecode and creates a [[LocalSummary]] object which
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
    implicit def rw(using st: SymbolTable): ReadWriter[ClassInfo] = macroRW
  }
  case class MethodInfo(
      calls: Set[MethodCall],
      callArg0SlotTypes: Map[MethodCall, Set[JCls]],
      callRefArgSlotTypes: Map[MethodCall, Set[JCls]],
      isPrivate: Boolean,
      codeHash: Int,
      isAbstract: Boolean
  )
  object MethodInfo {
    implicit def rw(using st: SymbolTable): ReadWriter[MethodInfo] = macroRW
  }

  implicit def rw(using st: SymbolTable): ReadWriter[LocalSummary] = macroRW

  def apply(classStreams: Iterator[java.io.InputStream])(using st: SymbolTable): LocalSummary = {
    val classDataAndVisitors = classStreams
      .map { cs =>
        val classBytes = cs.readAllBytes()
        val visitor = new MyClassVisitor()
        new ClassReader(classBytes).accept(visitor, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG)
        (classBytes, visitor)
      }
      .toVector

    LocalSummary(
      classDataAndVisitors
        .map { case (classBytes, v) =>
          val cls = v.clsType
          val methodCallGraphs = v.classCallGraph.result()
          val methodCallArg0SlotTypes = v.classMethodCallArg0SlotTypes.result()
          val methodCallRefArgSlotTypes = v.classMethodCallRefArgSlotTypes.result()
          val methodHashes = v.classMethodHashes.result()
          val methodPrivate = v.classMethodPrivate.result()
          val methodAbstract = v.classMethodAbstract.result()

          // Use ASM Analyzer to compute precise operand stack types at invoke
          // instructions. This gives us the actual types of method arguments
          // (e.g. Boolean instead of Object) regardless of how they were produced
          // (method return, field load, etc.), unlike the manual ALOAD-based tracking
          // which only works when args come from local variables.
          val analyzerArgTypes = analyzeMethodArgTypes(classBytes)

          cls -> ClassInfo(
            superClass = v.directSuperClass.get,
            directAncestors = v.directAncestors,
            methods = methodCallGraphs
              .keys
              .map { m =>
                val visitorRefArgTypes = methodCallRefArgSlotTypes(m)
                val frameRefArgTypes = analyzerArgTypes.getOrElse(m, Map.empty)
                // Merge: prefer frame-based types (more precise), fall back to visitor
                val mergedRefArgTypes = (visitorRefArgTypes.keySet ++ frameRefArgTypes.keySet)
                  .iterator
                  .map { call =>
                    val frameTypes = frameRefArgTypes.getOrElse(call, Set.empty)
                    val visitorTypes = visitorRefArgTypes.getOrElse(call, Set.empty)
                    call -> (if (frameTypes.nonEmpty) frameTypes else visitorTypes)
                  }
                  .filter(_._2.nonEmpty)
                  .toMap
                m -> MethodInfo(
                  methodCallGraphs(m),
                  methodCallArg0SlotTypes(m),
                  mergedRefArgTypes,
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

  /**
   * Use ASM's Analyzer with BasicInterpreter to compute precise operand stack
   * types at every method invoke instruction. Returns a map from MethodSig to
   * (MethodCall -> Set[JCls]) representing the actual reference arg types.
   */
  private def analyzeMethodArgTypes(
      classBytes: Array[Byte]
  )(using st: SymbolTable): Map[MethodSig, Map[MethodCall, Set[JCls]]] = {
    import org.objectweb.asm.tree.*
    import org.objectweb.asm.tree.analysis.*
    import scala.jdk.CollectionConverters.*

    val classNode = new ClassNode()
    new ClassReader(classBytes).accept(classNode, 0)

    val result = Map.newBuilder[MethodSig, Map[MethodCall, Set[JCls]]]

    for (method <- classNode.methods.asScala) {
      val methodSig = st.MethodSig(
        (method.access & Opcodes.ACC_STATIC) != 0,
        method.name,
        st.Desc.read(method.desc)
      )

      // Use a custom interpreter that preserves precise object types,
      // unlike BasicInterpreter which collapses all references to Object.
      val interpreter = new BasicInterpreter(Opcodes.ASM9) {
        override def newValue(tp: org.objectweb.asm.Type): BasicValue = {
          if (tp == null) return BasicValue.UNINITIALIZED_VALUE
          tp.getSort match {
            case org.objectweb.asm.Type.VOID => null
            case org.objectweb.asm.Type.BOOLEAN | org.objectweb.asm.Type.CHAR |
                org.objectweb.asm.Type.BYTE | org.objectweb.asm.Type.SHORT |
                org.objectweb.asm.Type.INT =>
              BasicValue.INT_VALUE
            case org.objectweb.asm.Type.FLOAT => BasicValue.FLOAT_VALUE
            case org.objectweb.asm.Type.LONG => BasicValue.LONG_VALUE
            case org.objectweb.asm.Type.DOUBLE => BasicValue.DOUBLE_VALUE
            case org.objectweb.asm.Type.ARRAY | org.objectweb.asm.Type.OBJECT =>
              new BasicValue(tp)
            case _ => BasicValue.REFERENCE_VALUE
          }
        }
        override def merge(a: BasicValue, b: BasicValue): BasicValue = {
          if (a == b) a
          else if (a == BasicValue.UNINITIALIZED_VALUE || b == BasicValue.UNINITIALIZED_VALUE)
            BasicValue.UNINITIALIZED_VALUE
          else BasicValue.REFERENCE_VALUE
        }
      }
      val analyzer = new Analyzer[BasicValue](interpreter)
      val frames: Array[Frame[BasicValue]] =
        try analyzer.analyze(classNode.name, method)
        catch { case _: AnalyzerException => null }

      if (frames != null) {
        val callArgTypes = Map.newBuilder[MethodCall, Set[JCls]]
        val insns = method.instructions
        for (i <- 0 until insns.size()) {
          insns.get(i) match {
            case invoke: MethodInsnNode
                if invoke.owner != null && invoke.owner.nonEmpty && invoke.owner(0) != '[' =>
              val frame = frames(i)
              if (frame != null) {
                val invokeType = invoke.getOpcode match {
                  case Opcodes.INVOKESTATIC => InvokeType.Static
                  case Opcodes.INVOKESPECIAL => InvokeType.Special
                  case Opcodes.INVOKEVIRTUAL => InvokeType.Virtual
                  case Opcodes.INVOKEINTERFACE => InvokeType.Virtual
                  case _ => null
                }
                if (invokeType != null) {
                  val desc = st.Desc.read(invoke.desc)
                  val call = st.MethodCall(
                    JCls.fromSlashed(invoke.owner),
                    invokeType,
                    invoke.name,
                    desc
                  )

                  // Extract ref arg types from the operand stack.
                  // Stack layout before invoke: [..., [objectref,] arg1, arg2, ...]
                  // Total consumed = argCount + (1 if non-static for receiver)
                  val argTypes = desc.args
                  val totalConsumed =
                    argTypes.size + (if (invoke.getOpcode == Opcodes.INVOKESTATIC) 0 else 1)
                  val stackSize = frame.getStackSize
                  if (stackSize >= totalConsumed) {
                    val argStartIdx = stackSize - argTypes.size
                    val refArgTypes = mutable.Set.empty[JCls]
                    for (j <- argTypes.indices) {
                      argTypes(j) match {
                        case _: JCls =>
                          val stackVal = frame.getStack(argStartIdx + j)
                          if (stackVal != null && stackVal.getType != null) {
                            val t = stackVal.getType
                            if (
                              t.getSort == org.objectweb.asm.Type.OBJECT &&
                              t.getInternalName != "null"
                            ) {
                              refArgTypes += JCls.fromSlashed(t.getInternalName)
                            }
                          }
                        case _ => // primitive arg, skip
                      }
                    }
                    if (refArgTypes.nonEmpty) {
                      callArgTypes += (call -> refArgTypes.toSet)
                    }
                  }
                }
              }
            case _ => // not a method invoke instruction
          }
        }
        val types = callArgTypes.result()
        if (types.nonEmpty) result += (methodSig -> types)
      }
    }
    result.result()
  }

  class MyClassVisitor()(using st: SymbolTable) extends ClassVisitor(Opcodes.ASM9) {
    val classCallGraph
        : mutable.Builder[(MethodSig, Set[MethodCall]), Map[MethodSig, Set[MethodCall]]] =
      Map.newBuilder[MethodSig, Set[MethodCall]]
    val classMethodHashes: mutable.Builder[(MethodSig, Int), Map[MethodSig, Int]] =
      Map.newBuilder[MethodSig, Int]
    val classMethodPrivate: mutable.Builder[(MethodSig, Boolean), Map[MethodSig, Boolean]] =
      Map.newBuilder[MethodSig, Boolean]
    val classMethodAbstract: mutable.Builder[(MethodSig, Boolean), Map[MethodSig, Boolean]] =
      Map.newBuilder[MethodSig, Boolean]
    val classMethodCallArg0SlotTypes
        : mutable.Builder[(MethodSig, Map[MethodCall, Set[JCls]]), Map[MethodSig, Map[MethodCall, Set[JCls]]]] =
      Map.newBuilder[MethodSig, Map[MethodCall, Set[JCls]]]
    val classMethodCallRefArgSlotTypes
        : mutable.Builder[(MethodSig, Map[MethodCall, Set[JCls]]), Map[MethodSig, Map[MethodCall, Set[JCls]]]] =
      Map.newBuilder[MethodSig, Map[MethodCall, Set[JCls]]]
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
  )(using st: SymbolTable) extends MethodVisitor(Opcodes.ASM9) {
    private val LazyHandleSuffix = "\\$lzy\\d+\\$lzyHandle$".r
    private val LazyNameSuffix = "\\$lzy\\d+$".r

    val outboundCalls: mutable.Set[MethodCall] = collection.mutable.Set.empty[MethodCall]
    val outboundCallArg0SlotTypes: mutable.Map[MethodCall, mutable.Set[JCls]] =
      collection.mutable.Map.empty[MethodCall, mutable.Set[JCls]]
    val outboundCallRefArgSlotTypes: mutable.Map[MethodCall, mutable.Set[JCls]] =
      collection.mutable.Map.empty[MethodCall, mutable.Set[JCls]]
    val labelIndices: mutable.Map[Label, Int] = collection.mutable.Map.empty[Label, Int]
    val jumpList: mutable.Buffer[Label] = collection.mutable.Buffer.empty[Label]

    val methodSig: MethodSig = st.MethodSig(
      (access & Opcodes.ACC_STATIC) != 0,
      name,
      st.Desc.read(descriptor)
    )

    val insnSigs: mutable.ArrayBuffer[Int] = collection.mutable.ArrayBuffer.empty[Int]

    var insnHash = 0

    // Scala 3 `$lzyINIT1` methods seem to do nothing but forward to other methods, but
    // their contents seems very unstable and prone to causing spurious invalidations
    var isScala3LazyInit = name.contains("$lzyINIT")
    var endScala3LazyInit = false
    var lazyNameSeenInClinit = false
    var lastALoadSlot: Option[Int] = None
    val recentALoadSlots: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty[Int]

    val paramSlotTypes: Map[Int, JCls] = {
      val b = Map.newBuilder[Int, JCls]
      var slot = if ((access & Opcodes.ACC_STATIC) != 0) 0 else 1
      if ((access & Opcodes.ACC_STATIC) == 0) b += 0 -> currentCls
      for (arg <- methodSig.desc.args) {
        arg match {
          case c: JCls => b += slot -> c
          case _ =>
        }
        slot = slot + (arg match {
          case JType.Prim.J | JType.Prim.D => 2
          case _ => 1
        })
      }
      b.result()
    }
    val localSlotTypes: mutable.Map[Int, JCls] = mutable.Map.from(paramSlotTypes)
    var lastPushedRefType: Option[JCls] = None

    def setLastPushedRefTypeFromDesc(desc: Desc): Unit = {
      lastPushedRefType = desc.ret match {
        case c: JCls => Some(c)
        case _ => None
      }
    }
    def hash(x: Int): Unit = {
      if (!isScala3LazyInit && !endScala3LazyInit) {
        insnHash = scala.util.hashing.MurmurHash3.mix(insnHash, x)
      }
    }

    def completeHash(): Unit = {
      insnSigs.append(scala.util.hashing.MurmurHash3.finalizeHash(0, insnHash))
      insnHash = 0
    }

    def clinitCall(desc: String): Unit = JType.read(desc) match {
      case descCls: JType.Cls if descCls != currentCls =>
        storeCallEdge(
          st.MethodCall(descCls, InvokeType.Static, "<clinit>", st.Desc.read("()V"))
        )
      case _ => // do nothing
    }

    def storeCallEdge(x: MethodCall): Unit = outboundCalls.add(x)
    def storeCallArg0SlotType(call: MethodCall, arg0Type: JCls): Unit = {
      val s = outboundCallArg0SlotTypes.getOrElseUpdate(call, collection.mutable.Set.empty[JCls])
      s += arg0Type
    }
    def storeCallRefArgSlotType(call: MethodCall, argType: JCls): Unit = {
      val s = outboundCallRefArgSlotTypes.getOrElseUpdate(call, collection.mutable.Set.empty[JCls])
      s += argType
    }

    def clearLastALoadSlot(): Unit = {
      lastALoadSlot = None
      recentALoadSlots.clear()
    }

    def hashlabel(x: Label): Unit = jumpList.append(x)

    def discardPreviousInsn(): Unit = insnSigs(insnSigs.size - 1) = 0
    def dropPreviousInsn(count: Int): Unit = {
      val n = math.min(count, insnSigs.size)
      var i = 0
      while (i < n) {
        insnSigs.remove(insnSigs.size - 1)
        i += 1
      }
    }

    def isLazyHandleField(name: String): Boolean = LazyHandleSuffix.findFirstIn(name).nonEmpty
    def isLazyName(name: String): Boolean = LazyNameSuffix.findFirstIn(name).nonEmpty

    override def visitFieldInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String
    ): Unit = {
      clearLastALoadSlot()
      val lazyValBodyStart = (owner, name, descriptor) match {
        case (
              "scala/runtime/LazyVals$Evaluating$",
              "MODULE$",
              "Lscala/runtime/LazyVals$Evaluating$;"
            ) => true
        case _ => false
      }
      val lazyValBodyEnd = (owner, name, descriptor) match {
        case (
              "scala/runtime/LazyVals$NullValue$",
              "MODULE$",
              "Lscala/runtime/LazyVals$NullValue$;"
            ) => true
        case _ => false
      }

      if (lazyValBodyStart && opcode == Opcodes.GETSTATIC) {
        if (!endScala3LazyInit) isScala3LazyInit = false
      } else if (lazyValBodyEnd && opcode == Opcodes.GETSTATIC) {
        endScala3LazyInit = true
      } else if (methodSig.name == "<clinit>" && lazyNameSeenInClinit && isLazyHandleField(name)) {
        // Ignore Scala lazy-handle storage in `<clinit>`, which is unstable and benign.
        lazyNameSeenInClinit = false
      } else {
        if (methodSig.name == "<clinit>" && lazyNameSeenInClinit) lazyNameSeenInClinit = false
        hash(opcode)
        hash(owner.hashCode)
        hash(name.hashCode)
        hash(descriptor.hashCode)
        completeHash()
        clinitCall(owner)
      }
      lastPushedRefType =
        if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) JType.read(descriptor) match {
          case c: JCls => Some(c)
          case _ => None
        }
        else None
    }

    override def visitIincInsn(varIndex: Int, increment: Int): Unit = {
      clearLastALoadSlot()
      hash(varIndex)
      hash(increment)
      completeHash()
    }

    override def visitInsn(opcode: Int): Unit = {
      clearLastALoadSlot()
      hash(opcode)
      completeHash()
      lastPushedRefType = None
    }

    override def visitIntInsn(opcode: Int, operand: Int): Unit = {
      clearLastALoadSlot()
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
      clearLastALoadSlot()
      // Hash the invokedynamic name and descriptor to detect changes in the
      // dynamic call site (e.g. makeConcatWithConstants for string concatenation)
      hash(name.hashCode)
      hash(descriptor.hashCode)

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
          // Hash bootstrap method arguments (e.g. string templates for makeConcatWithConstants)
          // to detect changes in string literals and other constants used in invokedynamic
          case v: java.lang.String => hash(v.hashCode)
          case v: java.lang.Integer => hash(v.hashCode)
          case v: java.lang.Float => hash(v.hashCode)
          case v: java.lang.Long => hash(v.hashCode)
          case v: java.lang.Double => hash(v.hashCode)
          case v: org.objectweb.asm.Type => hash(v.hashCode)
          case v: org.objectweb.asm.ConstantDynamic => hash(v.hashCode)
          case _ =>
        }
      }
      completeHash()
    }

    override def visitJumpInsn(opcode: Int, label: Label): Unit = {
      clearLastALoadSlot()
      hashlabel(label)
      hash(opcode)
      completeHash()
    }

    override def visitLabel(label: Label): Unit = {
      labelIndices(label) = insnSigs.size
    }

    override def visitLdcInsn(value: Any): Unit = {
      clearLastALoadSlot()
      value match {
        case v: java.lang.String if methodSig.name == "<clinit>" && isLazyName(v) =>
          // Drop the preceding `lookup()` and owner-class LDC, then skip the rest of
          // this lazy-handle setup sequence (`Type` LDC, `findVarHandle`, `PUTSTATIC`).
          lazyNameSeenInClinit = true
          dropPreviousInsn(2)
        case _: org.objectweb.asm.Type if methodSig.name == "<clinit>" && lazyNameSeenInClinit =>
          ()
        case _ =>
          if (methodSig.name == "<clinit>" && lazyNameSeenInClinit) lazyNameSeenInClinit = false
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
      lastPushedRefType = value match {
        case _: java.lang.String => Some(JCls.fromSlashed("java/lang/String"))
        case _: org.objectweb.asm.Type => Some(JCls.fromSlashed("java/lang/Class"))
        case _ => None
      }
    }

    override def visitLookupSwitchInsn(
        dflt: Label,
        keys: Array[Int],
        labels: Array[Label]
    ): Unit = {
      clearLastALoadSlot()
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
      val desc = st.Desc.read(descriptor)
      val arg0RefTypeOpt =
        if (
          opcode == Opcodes.INVOKESTATIC &&
          desc.args.headOption.exists(_.isInstanceOf[JCls])
        ) lastALoadSlot.flatMap(paramSlotTypes.get)
        else None
      val refArgSlotTypes: Set[JCls] = {
        val refArgCount = desc.args.count(_.isInstanceOf[JCls])
        if (refArgCount > 0 && recentALoadSlots.size >= refArgCount) {
          recentALoadSlots.takeRight(refArgCount).flatMap(localSlotTypes.get).toSet
        } else Set.empty
      }

      val isMethodHandlesLookupInClinit =
        methodSig.name == "<clinit>" &&
          lazyNameSeenInClinit &&
          ((owner == "java/lang/invoke/MethodHandles" &&
            name == "lookup" &&
            descriptor == "()Ljava/lang/invoke/MethodHandles$Lookup;") ||
            (owner == "java/lang/invoke/MethodHandles$Lookup" &&
              name == "findVarHandle" &&
              descriptor ==
              "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"))

      if (isMethodHandlesLookupInClinit) {
        return
      }

      if (methodSig.name == "<clinit>" && lazyNameSeenInClinit) lazyNameSeenInClinit = false

      // Skip analyzing array methods like `.clone()` or `.hashCode()`, since they always
      // provided by the standard library and do not contribute to the program's call graph
      if (owner(0) != '[') {
        val call = st.MethodCall(
          JCls.fromSlashed(owner),
          opcode match {
            case Opcodes.INVOKESTATIC => InvokeType.Static
            case Opcodes.INVOKESPECIAL => InvokeType.Special
            case Opcodes.INVOKEVIRTUAL => InvokeType.Virtual
            case Opcodes.INVOKEINTERFACE => InvokeType.Virtual
          },
          name,
          desc
        )

        // HACK: we skip any constants that get passed to `sourcecode.Line()`,
        // because we use that extensively in defining Mill tasks, but it is
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
        arg0RefTypeOpt.foreach(storeCallArg0SlotType(call, _))
        refArgSlotTypes.foreach(storeCallRefArgSlotType(call, _))
        clinitCall(owner)
        completeHash()
      }
      setLastPushedRefTypeFromDesc(desc)
      clearLastALoadSlot()
    }

    override def visitMultiANewArrayInsn(descriptor: String, numDimensions: Int): Unit = {
      clearLastALoadSlot()
      hash(descriptor.hashCode)
      hash(numDimensions)
      completeHash()
    }

    override def visitTableSwitchInsn(min: Int, max: Int, dflt: Label, labels: Label*): Unit = {
      clearLastALoadSlot()
      hash(min)
      hash(max)
      labels.foreach(hashlabel)
      Option(dflt).foreach(hashlabel)
      completeHash()
    }

    override def visitTypeInsn(opcode: Int, `type`: String): Unit = {
      clearLastALoadSlot()
      clinitCall(`type`)
      hash(`type`.hashCode)
      hash(opcode)
      completeHash()
      lastPushedRefType =
        if (opcode == Opcodes.NEW) Some(JCls.fromSlashed(`type`))
        else None
    }

    override def visitVarInsn(opcode: Int, varIndex: Int): Unit = {
      opcode match {
        case Opcodes.ALOAD =>
          lastALoadSlot = Some(varIndex)
          recentALoadSlots.append(varIndex)
          lastPushedRefType = localSlotTypes.get(varIndex)
        case Opcodes.ASTORE =>
          clearLastALoadSlot()
          lastPushedRefType.foreach(localSlotTypes(varIndex) = _)
          lastPushedRefType = None
        case _ =>
          clearLastALoadSlot()
          lastPushedRefType = None
      }
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
      clsVisitor.classMethodCallArg0SlotTypes.addOne(
        methodSig -> outboundCallArg0SlotTypes.view.mapValues(_.toSet).toMap
      )
      clsVisitor.classMethodCallRefArgSlotTypes.addOne(
        methodSig -> outboundCallRefArgSlotTypes.view.mapValues(_.toSet).toMap
      )

    }
  }
}

package mill.codesig

import mill.codesig.JvmModel.*
import mill.codesig.JvmModel.JType.Cls as JCls
import mill.codesig.LocalSummary.ClassInfo
import org.objectweb.asm.*
import org.objectweb.asm.tree.ClassNode
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
  private val LazyHandleSuffix = "\\$lzy\\d+\\$lzyHandle$".r
  private val LazyNameSuffix = "\\$lzy\\d+$".r

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
      callSiteArgTypes: Map[MethodCall, Set[JCls]],
      callSiteReceiverType: Map[MethodCall, JCls],
      isPrivate: Boolean,
      codeHash: Int,
      isAbstract: Boolean
  )
  object MethodInfo {
    implicit def rw(using st: SymbolTable): ReadWriter[MethodInfo] = macroRW
  }

  implicit def rw(using st: SymbolTable): ReadWriter[LocalSummary] = macroRW

  def apply(
      classStreams: Iterator[java.io.InputStream],
      ctx: Option[mill.api.TaskCtx] = None
  )(using st: SymbolTable): LocalSummary = {
    // Read all class bytes into memory first (sequential I/O), then process in parallel
    val classBytes = classStreams.map { cs =>
      try cs.readAllBytes()
      finally cs.close()
    }.toVector

    def processOneClass(bytes: Array[Byte]): (JCls, ClassInfo) = {
      // Parse into ClassNode; replay into MyClassVisitor for call graph
      // extraction, and reuse the same ClassNode for the Analyzer pass.
      val classNode = new ClassNode()
      new ClassReader(bytes).accept(classNode, 0)
      val v = new MyClassVisitor()
      classNode.accept(v)

      val cls = v.clsType
      val methodCallGraphs = v.classCallGraph.result()
      val methodHashes = v.classMethodHashes.result()
      val methodPrivate = v.classMethodPrivate.result()
      val methodAbstract = v.classMethodAbstract.result()

      val (argTypes, receiverTypes) = analyzeMethodArgTypes(classNode)

      cls -> ClassInfo(
        superClass = v.directSuperClass.get,
        directAncestors = v.directAncestors,
        methods = methodCallGraphs
          .keys
          .map { m =>
            m -> MethodInfo(
              methodCallGraphs(m),
              argTypes.getOrElse(m, Map.empty),
              receiverTypes.getOrElse(m, Map.empty),
              methodPrivate(m),
              methodHashes(m),
              methodAbstract(m)
            )
          }
          .toMap
      )
    }

    val results: Seq[(JCls, ClassInfo)] = ctx match {
      case Some(c) =>
        given mill.api.TaskCtx = c
        val fork = c.fork
        val numBatches = c.jobs
        val batchSize = math.max(1, (classBytes.size + numBatches - 1) / numBatches)
        val batches = classBytes.grouped(batchSize).zipWithIndex.toSeq
        fork.awaitAll(batches.map { case (batch, i) =>
          fork.async(
            c.dest / "parallel" / s"$i",
            s"${i + 1}",
            s"Analyzing classes (batch ${i + 1}/${batches.size})"
          ) { _ => batch.map(processOneClass) }
        }).flatten

      case None =>
        classBytes.map(processOneClass)
    }

    LocalSummary(results.toMap)
  }

  /**
   * Use ASM's Analyzer with a custom BasicInterpreter to compute precise operand
   * stack types at every method invoke instruction. Returns two per-method maps:
   *   1. call → precise types of reference-typed arguments (for expanding external type search)
   *   2. call → precise receiver type (for narrowing virtual dispatch targets)
   */
  import org.objectweb.asm.tree.analysis.BasicValue

  // Custom interpreter that preserves precise object types, unlike the default
  // BasicInterpreter which collapses all reference types to a single REFERENCE_VALUE.
  // Shared across all classes since it is stateless.
  private val sharedInterpreter =
    new org.objectweb.asm.tree.analysis.BasicInterpreter(Opcodes.ASM9) {
      override def newValue(tp: org.objectweb.asm.Type): BasicValue =
        if (tp == null) BasicValue.UNINITIALIZED_VALUE
        else if (
          tp.getSort == org.objectweb.asm.Type.ARRAY || tp.getSort == org.objectweb.asm.Type.OBJECT
        ) new BasicValue(tp)
        else super.newValue(tp)

      override def merge(a: BasicValue, b: BasicValue): BasicValue =
        if (a == b) a
        else if (a == BasicValue.UNINITIALIZED_VALUE || b == BasicValue.UNINITIALIZED_VALUE)
          BasicValue.UNINITIALIZED_VALUE
        else BasicValue.REFERENCE_VALUE
    }

  private def preciseType(v: BasicValue)(using st: SymbolTable): Option[JCls] =
    for {
      bv <- Option(v)
      t <- Option(bv.getType)
      if t.getSort == org.objectweb.asm.Type.OBJECT
      if t.getInternalName != "null"
    } yield JCls.fromSlashed(t.getInternalName)

  private def toInvokeType(opcode: Int): Option[InvokeType] = opcode match {
    case Opcodes.INVOKESTATIC => Some(InvokeType.Static)
    case Opcodes.INVOKESPECIAL => Some(InvokeType.Special)
    case Opcodes.INVOKEVIRTUAL | Opcodes.INVOKEINTERFACE => Some(InvokeType.Virtual)
    case _ => None
  }

  private def analyzeMethodArgTypes(
      classNode: ClassNode
  )(using
      st: SymbolTable
  ): (
      Map[MethodSig, Map[MethodCall, Set[JCls]]],
      Map[MethodSig, Map[MethodCall, JCls]]
  ) = {
    import org.objectweb.asm.tree.*
    import org.objectweb.asm.tree.analysis.*
    import scala.jdk.CollectionConverters.*

    val allArgTypes = Map.newBuilder[MethodSig, Map[MethodCall, Set[JCls]]]
    val allReceiverTypes = Map.newBuilder[MethodSig, Map[MethodCall, JCls]]

    for (method <- classNode.methods.asScala) {
      // Skip the expensive Analyzer for methods without any method invocations
      val insns = method.instructions
      var hasInvoke = false
      var i = 0
      while (i < insns.size() && !hasInvoke) {
        insns.get(i) match {
          case _: MethodInsnNode => hasInvoke = true
          case _ =>
        }
        i += 1
      }
      if (!hasInvoke) () // skip — no invocations to analyze
      else {
        val methodSig = st.MethodSig(
          (method.access & Opcodes.ACC_STATIC) != 0,
          method.name,
          st.Desc.read(method.desc)
        )

        // AnalyzerException can occur on valid bytecode with unusual patterns
        // (e.g. subroutines, dead code). Fall back to no precise types for this method.
        val frames =
          try new Analyzer[BasicValue](sharedInterpreter).analyze(classNode.name, method)
          catch { case _: AnalyzerException => null }

        if (frames != null) {
          val callArgTypes = Map.newBuilder[MethodCall, Set[JCls]]
          val callReceiverTypes = Map.newBuilder[MethodCall, JCls]

          for (i <- 0 until insns.size()) insns.get(i) match {
            case invoke: MethodInsnNode
                if invoke.owner != null && invoke.owner.nonEmpty && invoke.owner(0) != '[' =>
              for {
                frame <- Option(frames(i))
                invokeType <- toInvokeType(invoke.getOpcode)
              } {
                val desc = st.Desc.read(invoke.desc)
                val call =
                  st.MethodCall(JCls.fromSlashed(invoke.owner), invokeType, invoke.name, desc)
                val argStartIdx = frame.getStackSize - desc.args.size

                // For non-static calls, capture the receiver's precise type.
                // This is the most important signal for narrowing virtual dispatch.
                if (invokeType != InvokeType.Static && argStartIdx > 0) {
                  preciseType(frame.getStack(argStartIdx - 1))
                    .foreach(callReceiverTypes += call -> _)
                }

                val refArgs = desc.args.indices
                  .collect {
                    case j if desc.args(j).isInstanceOf[JCls] =>
                      preciseType(frame.getStack(argStartIdx + j))
                  }
                  .flatten
                  .toSet

                if (refArgs.nonEmpty) callArgTypes += call -> refArgs
              }

            case _ =>
          }

          val argMap = callArgTypes.result()
          val recMap = callReceiverTypes.result()
          if (argMap.nonEmpty) allArgTypes += methodSig -> argMap
          if (recMap.nonEmpty) allReceiverTypes += methodSig -> recMap
        }
      }
    }
    (allArgTypes.result(), allReceiverTypes.result())
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
    val outboundCalls: mutable.Set[MethodCall] = collection.mutable.Set.empty[MethodCall]
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

    def hashlabel(x: Label): Unit = jumpList.append(x)

    def discardPreviousInsn(): Unit = insnSigs(insnSigs.size - 1) = 0

    def inLazyHandleSequence: Boolean = methodSig.name == "<clinit>" && lazyNameSeenInClinit
    def resetLazySequence(): Unit =
      if (methodSig.name == "<clinit>" && lazyNameSeenInClinit) lazyNameSeenInClinit = false

    def isLazyHandleField(name: String): Boolean = LazyHandleSuffix.findFirstIn(name).nonEmpty
    def isLazyName(name: String): Boolean = LazyNameSuffix.findFirstIn(name).nonEmpty

    override def visitFieldInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String
    ): Unit = {
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
      } else if (inLazyHandleSequence && isLazyHandleField(name)) {
        lazyNameSeenInClinit = false
      } else {
        resetLazySequence()
        hash(opcode)
        hash(owner.hashCode)
        hash(name.hashCode)
        hash(descriptor.hashCode)
        completeHash()
        clinitCall(owner)
      }
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
      hashlabel(label)
      hash(opcode)
      completeHash()
    }

    override def visitLabel(label: Label): Unit = {
      labelIndices(label) = insnSigs.size
    }

    override def visitLdcInsn(value: Any): Unit = {
      value match {
        case v: java.lang.String if methodSig.name == "<clinit>" && isLazyName(v) =>
          // Drop the preceding `lookup()` and owner-class LDC, then skip the rest of
          // this lazy-handle setup sequence (`Type` LDC, `findVarHandle`, `PUTSTATIC`).
          lazyNameSeenInClinit = true
          if (insnSigs.size >= 2) {
            insnSigs.remove(insnSigs.size - 1); insnSigs.remove(insnSigs.size - 1)
          }
        case _: org.objectweb.asm.Type if inLazyHandleSequence =>
          ()
        case _ =>
          resetLazySequence()
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
      if (
        inLazyHandleSequence &&
        ((owner == "java/lang/invoke/MethodHandles" &&
          name == "lookup" &&
          descriptor == "()Ljava/lang/invoke/MethodHandles$Lookup;") ||
          (owner == "java/lang/invoke/MethodHandles$Lookup" &&
            name == "findVarHandle" &&
            descriptor ==
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"))
      ) {
        return
      }

      resetLazySequence()

      // Skip analyzing array methods like `.clone()` or `.hashCode()`, since they always
      // provided by the standard library and do not contribute to the program's call graph
      if (owner(0) != '[') {
        val desc = st.Desc.read(descriptor)
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
        clinitCall(owner)
        completeHash()
      }
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

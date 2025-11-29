package mill.api

import mill.api.internal.OverrideMapping

import scala.annotation.implicitNotFound
import scala.quoted.*

/**
 * The contextual information provided to a [[mill.api.Module]] or [[mill.api.Task]]
 */
@implicitNotFound(
  "Modules and Tasks can only be defined within a mill Module (in `build.mill` or `package.mill` files)"
)
trait ModuleCtx extends ModuleCtx.Nested {
  def enclosing: String

  /**
   * the line number that this module is defined at. Useful for
   * error reporting purposes
   */
  def lineNum: Int

  /**
   * the file name that this module is defined in. Useful for
   * error reporting purposes
   */
  def fileName: String

  /**
   * The `class` or `trait` that lexically surrounds this definition
   */
  private[mill] def enclosingCls: Class[?]

  private[mill] def withCrossValues(crossValues: Seq[Any]): ModuleCtx
  private[mill] def withMillSourcePath(millSourcePath: os.Path): ModuleCtx
  private[mill] def withSegments(segments: Segments): ModuleCtx
  private[mill] def withEnclosingModule(enclosingModule: ModuleCtx.Wrapper): ModuleCtx
  private[mill] def withFileName(fileName: String): ModuleCtx = this
  private[mill] def withLineNum(line: Int): ModuleCtx = this
  private[mill] def withDiscover(discover: Discover): ModuleCtx
}

object ModuleCtx extends LowPriCtx {
  trait Wrapper {
    def moduleSegments: Segments = moduleCtx.segments
    def moduleCtx: ModuleCtx
    private[mill] def moduleLinearized: Seq[Class[?]]
    private[mill] def moduleDynamicBuildOverrides: Map[String, upickle.core.BufferedValue] = Map()
  }

  import upickle.core.BufferedValue
  implicit val bufferedRw: upickle.ReadWriter[BufferedValue] =
    new upickle.ReadWriter[BufferedValue] {
      def visitArray(length: Int, index: Int): upickle.core.ArrVisitor[Any, BufferedValue] =
        new upickle.core.ArrVisitor[Any, BufferedValue] {
          def subVisitor = bufferedRw
          private[this] val vs = collection.mutable.ArrayBuffer.newBuilder[BufferedValue]
          def visitValue(v: Any, index: Int): Unit = vs += v.asInstanceOf[BufferedValue]

          def visitEnd(index: Int) = BufferedValue.Arr(vs.result(), index)
        }

      def visitBinary(bytes: Array[Byte], offset: Int, len: Int, index: Int) =
        BufferedValue.Binary(bytes, offset, len, index)
      def visitChar(s: Char, index: Int) = BufferedValue.Char(s, index)
      def visitExt(tag: Byte, bytes: Array[Byte], offset: Int, len: Int, index: Int) =
        BufferedValue.Ext(tag, bytes, offset, len, index)
      def visitFalse(index: Int) = BufferedValue.False(index)
      def visitFloat32(d: Float, index: Int) = BufferedValue.Float32(d, index)
      def visitFloat64(d: Double, index: Int) = BufferedValue.NumRaw(d, index)
      def visitFloat64String(s: String, index: Int) = BufferedValue.Float64String(s, index)
      def visitFloat64StringParts(s: CharSequence, decIndex: Int, expIndex: Int, index: Int) =
        BufferedValue.Num(s, decIndex, expIndex, index)
      def visitInt32(i: Int, index: Int) = BufferedValue.Int32(i, index)
      def visitInt64(i: Long, index: Int) = BufferedValue.Int64(i, index)
      def visitNull(index: Int) = BufferedValue.Null(index)
      def visitObject(
          length: Int,
          jsonableKeys: Boolean,
          index: Int
      ): upickle.core.ObjVisitor[Any, BufferedValue] =
        new upickle.core.ObjVisitor[Any, BufferedValue] {
          private[this] var key: BufferedValue = null
          private[this] val vs =
            collection.mutable.ArrayBuffer.newBuilder[(BufferedValue, BufferedValue)]

          def subVisitor = bufferedRw

          def visitKey(index: Int) = bufferedRw

          def visitKeyValue(s: Any): Unit = key = s.asInstanceOf[BufferedValue]

          def visitValue(v: Any, index: Int): Unit = vs += (key -> v.asInstanceOf[BufferedValue])

          def visitEnd(index: Int) = BufferedValue.Obj(vs.result(), jsonableKeys = true, index)
        }
      def visitString(s: CharSequence, index: Int) = BufferedValue.Str(s, index)
      def visitTrue(index: Int) = BufferedValue.True(index)
      def visitUInt64(i: Long, index: Int) = BufferedValue.UInt64(i, index)

      def write0[V](out: upickle.core.Visitor[?, V], v: BufferedValue): V =
        BufferedValue.transform(v, out)

    }
  private[mill] case class HeaderData(
      `extends`: Seq[String] = Nil,
      moduleDeps: Seq[String] = Nil,
      compileModuleDeps: Seq[String] = Nil,
      runModuleDeps: Seq[String] = Nil,
      @upickle.implicits.flatten rest: Map[String, upickle.core.BufferedValue]
  ) derives upickle.ReadWriter

  private case class Impl(
      enclosing: String,
      lineNum: Int,
      millSourcePath: os.Path,
      segments: Segments,
      external: Boolean,
      fileName: String,
      enclosingModule: ModuleCtx.Wrapper,
      crossValues: Seq[Any],
      discover: Discover
  ) extends ModuleCtx {
    def enclosingCls = enclosingModule.getClass
    def withCrossValues(crossValues: Seq[Any]): ModuleCtx = copy(crossValues = crossValues)
    def withMillSourcePath(millSourcePath: os.Path): ModuleCtx =
      copy(millSourcePath = millSourcePath)
    def withSegments(segments: Segments): ModuleCtx = copy(segments = segments)
    def withEnclosingModule(enclosingModule: ModuleCtx.Wrapper): ModuleCtx =
      copy(enclosingModule = enclosingModule)

    override def withFileName(fileName: String) = copy(fileName = fileName)
    override def withLineNum(lineNum: Int) = copy(lineNum = lineNum)
    def withDiscover(discover: Discover): ModuleCtx = copy(discover = discover)
  }

  /**
   * A subset of the [[ModuleCtx]] interface that are implicitly propagated
   * from the enclosing Module.
   */
  trait Nested {

    /**
     * The runtime [[Module]] object that contains this definition
     */
    private[mill] def enclosingModule: ModuleCtx.Wrapper

    /**
     * The enclosing module's default source root
     */
    private[mill] def millSourcePath: os.Path

    /**
     * The full path of this task or module, from the [[RootModule0]]
     */
    private[mill] def segments: Segments

    /**
     * whether this is in an [[ExternalModule]]
     */
    private[mill] def external: Boolean

    /**
     * The [[Discover]] instance associate with this [[RootModule0]] hierarchy
     */
    private[mill] def discover: Discover

    def crossValues: Seq[Any]
  }
  implicit def makeNested(
      implicit
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      fileName: sourcecode.File,
      enclosingClass: EnclosingClass,
      ctx: ModuleCtx.Nested
  ): ModuleCtx = {
    // Manually break apart `sourcecode.Enclosing` instead of using
    // `sourcecode.Name` to work around bug with anonymous classes
    // returning `$anon` names
    val lastSegmentStr =
      millModuleEnclosing0.value.split("\\.|#| ").filter(!_.startsWith("$anon")).last

    Impl(
      millModuleEnclosing0.value,
      millModuleLine0.value,
      ctx.millSourcePath / lastSegmentStr,
      ctx.segments ++
        OverrideMapping.computeSegments(
          ctx.enclosingModule,
          ctx.discover,
          lastSegmentStr,
          enclosingClass.value
        ).getOrElse(Segments(List(Segment.Label(lastSegmentStr)))),
      ctx.external,
      fileName.value,
      ctx.enclosingModule,
      Nil,
      ctx.discover
    )
  }
  def makeRoot(
      millModuleEnclosing0: sourcecode.Enclosing,
      millModuleLine0: sourcecode.Line,
      millSourcePath: os.Path,
      segments0: Segments,
      external0: Boolean,
      fileName: sourcecode.File
  ): ModuleCtx = {

    Impl(
      millModuleEnclosing0.value,
      millModuleLine0.value,
      millSourcePath,
      segments0,
      external0,
      fileName.value,
      null,
      Seq(),
      null
    )
  }
}

trait LowPriCtx {
  // Binary compatibility stub
  def dummyInfo: ModuleCtx = throw new Exception(LowPriCtx.errorMessage)

  // Dummy `Ctx` available in implicit scope but never actually used.
  // as it is provided by the codegen. Defined for IDEs to think that one is available
  // and not show errors in build.mill/package.mill even though they can't see the codegen
  inline implicit def dummyInfo2: ModuleCtx = ${ LowPriCtx.dummyInfoImpl }
}

object LowPriCtx {
  private def errorMessage =
    "Modules and Tasks can only be defined within a mill Module (in `build.mill` or `package.mill` files)"

  def dummyInfoImpl(using quotes: Quotes): Expr[ModuleCtx] = {
    import quotes.reflect.*

    if (
      sys.env.contains(mill.constants.EnvVars.MILL_ENABLE_STATIC_CHECKS) ||
      sys.props.contains(mill.constants.EnvVars.MILL_ENABLE_STATIC_CHECKS)
    ) {
      report.errorAndAbort(errorMessage)
    } else '{ throw new Exception(${ Expr(errorMessage) }) }
  }
}

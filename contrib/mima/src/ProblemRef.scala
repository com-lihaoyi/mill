package mill.mima

trait ProblemRef
trait TemplateRef extends ProblemRef

trait MemberRef extends ProblemRef

sealed abstract class Problem extends ProblemRef

// Template problems
sealed abstract class TemplateProblem() extends Problem with TemplateRef
final case class MissingClassProblem() extends TemplateProblem
final case class IncompatibleTemplateDefProblem() extends TemplateProblem
final case class InaccessibleClassProblem() extends TemplateProblem
final case class AbstractClassProblem() extends TemplateProblem
final case class FinalClassProblem() extends TemplateProblem
final case class CyclicTypeReferenceProblem() extends TemplateProblem
final case class MissingTypesProblem() extends TemplateProblem

// Member problems
sealed abstract class MemberProblem extends Problem with MemberRef

/// Field problems
final case class MissingFieldProblem() extends MemberProblem
final case class InaccessibleFieldProblem() extends MemberProblem
final case class IncompatibleFieldTypeProblem() extends MemberProblem

/// Member-generic problems
final case class StaticVirtualMemberProblem() extends AbstractMethodProblem
final case class VirtualStaticMemberProblem() extends AbstractMethodProblem

/// Method problems
sealed abstract class MissingMethodProblem() extends MemberProblem
final case class DirectMissingMethodProblem() extends MissingMethodProblem
final case class ReversedMissingMethodProblem() extends MissingMethodProblem
final case class InaccessibleMethodProblem() extends MemberProblem
final case class IncompatibleMethTypeProblem() extends MemberProblem
final case class IncompatibleResultTypeProblem() extends MemberProblem
final case class IncompatibleSignatureProblem() extends MemberProblem
final case class FinalMethodProblem() extends MemberProblem
sealed abstract class AbstractMethodProblem() extends MemberProblem
final case class DirectAbstractMethodProblem() extends AbstractMethodProblem
final case class ReversedAbstractMethodProblem() extends MemberProblem
final case class UpdateForwarderBodyProblem() extends MemberProblem
final case class NewMixinForwarderProblem() extends MemberProblem
final case class InheritedNewAbstractMethodProblem()
  extends AbstractMethodProblem

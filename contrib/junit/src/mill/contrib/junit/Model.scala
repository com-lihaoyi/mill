package mill.contrib.junit

import scala.xml.Elem

sealed trait XMLAdaptable {
  def asXML: Elem
}
case class TestCase(className: String, name: String, time: String, detail: Option[XMLAdaptable])
    extends XMLAdaptable {
  override def asXML: Elem = <testcase classname={className} name={name} time={time}>
    {detail.map(_.asXML).getOrElse {}}
  </testcase>
}

case object ErrorDetail extends XMLAdaptable {
  override def asXML: Elem = <error message="No Exception or message provided"/>
}

case object FailureDetail extends XMLAdaptable {
  override def asXML: Elem = <failure message="No Exception or message provided"/>
}

case object SkippedDetail extends XMLAdaptable {
  override def asXML: Elem = <skipped/>
}

case class TestSuite(
    hostname: String,
    name: String,
    tests: Int,
    errors: Int,
    failures: Int,
    skipped: Int,
    time: String,
    timestamp: String,
    testCases: Seq[TestCase]
) extends XMLAdaptable {
  override def asXML: Elem =
    <testsuite hostname={hostname} name={name} tests={tests.toString} errors={
      errors.toString
    } failures={failures.toString} skipped={skipped.toString} time={time} timestamp={timestamp}>
    <properties></properties>
    {testCases.map(_.asXML)}
    <system-out><![CDATA[]]></system-out>
    <system-err><![CDATA[]]></system-err>
  </testsuite>
}

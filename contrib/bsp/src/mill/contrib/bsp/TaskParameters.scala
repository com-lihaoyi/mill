package mill.contrib.bsp
import java.util

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._
import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, CompileParams, RunParams, TestParams}

trait Parameters {
  def getTargets: List[BuildTargetIdentifier]

  def getArguments: Option[Seq[String]]

  def getOriginId: Option[String]
}

case class CParams(compileParams: CompileParams) extends Parameters {

  override def getTargets: List[BuildTargetIdentifier] = {
    compileParams.getTargets.asScala.toList
  }

  override def getArguments: Option[Seq[String]] = {
    try {
      Option(compileParams.getArguments.asScala)
    }catch {
      case e: Exception => Option.empty[Seq[String]]
    }
  }

  override def getOriginId: Option[String] = {
    try {
      Option(compileParams.getOriginId)
    }catch {
      case e: Exception => Option.empty[String]
    }
  }

}
case class RParams(runParams: RunParams) extends Parameters {

  override def getTargets: List[BuildTargetIdentifier] = {
    List(runParams.getTarget)
  }

  override def getArguments: Option[Seq[String]] = {
    try {
      Option(runParams.getArguments.asScala)
    }catch {
      case e: Exception => Option.empty[Seq[String]]
    }
  }

  override def getOriginId: Option[String] = {
    try {
      Option(runParams.getOriginId)
    }catch {
      case e: Exception => Option.empty[String]
    }
  }

}
case class TParams(testParams: TestParams) extends Parameters {

  override def getTargets: List[BuildTargetIdentifier] = {
    testParams.getTargets.asScala.toList
  }

  override def getArguments: Option[Seq[String]] = {
    try {
      Option(testParams.getArguments.asScala)
    }catch {
      case e: Exception => Option.empty[Seq[String]]
    }
  }

  override def getOriginId: Option[String] = {
    try {
      Option(testParams.getOriginId)
    }catch {
      case e: Exception => Option.empty[String]
    }
  }
}

object TaskParameters {
  def fromCompileParams(compileParams: CompileParams): Parameters = {
    CParams(compileParams)
  }

  def fromRunParams(runParams: RunParams): Parameters = {
    RParams(runParams)
  }

  def fromTestParams(testParams: TestParams): Parameters = {
    TParams(testParams)
  }
}
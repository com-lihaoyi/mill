import $ivy.`com.lihaoyi::scalatags:0.6.7`
import scalatags.Text.implicits._
import scalatags.Text.svgTags._
import scalatags.Text.svgAttrs._


val svgWidth = 24
val svgHeight = 24
val numSections = 3
val bandWidth = 16
val bandGap = 8
val screwPitch = 2.0

def section(verticalOffset: Double) = {
  val p = sectionLine(0).map{case (x, y) => (x, y + verticalOffset)}
  val p1 = p.map{case (x, y) => (x, y - bandWidth/2)}
  val p2 = p.map{case (x, y) => (x, y + bandWidth/2)}
  (p1 ++ p2.reverse).map{case (x, y) => s"$x,$y"}.mkString(" ")
}

def sectionLine(startRadians: Double) = for{
  y <- Seq(-svgHeight / 2, svgHeight / 2)
} yield {
  val x = -y * svgWidth / svgHeight
  (x.toInt + svgWidth/2, y.toInt * screwPitch + svgHeight/2)
}

val sections = for(i <- -numSections/2 to numSections/2) yield polyline(
  points := section(i * (bandWidth + bandGap)),
  fill := "#666",
)
val svgFrag = svg(
  xmlns := "http://www.w3.org/2000/svg",
  height := svgHeight,
  width := svgWidth
)(
  sections:_*
)

write.over(pwd/"logo.svg", "<!DOCTYPE html>" + svgFrag)
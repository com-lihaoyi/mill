package jawn
package benchmark

import java.io.{BufferedReader, File, FileInputStream, FileReader}
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._
import scala.collection.mutable

case class Slice(s: String, begin: Int, limit: Int) extends CharSequence {
  val length: Int = limit - begin
  def charAt(i: Int): Char = s.charAt(begin + i)
  def subSequence(start: Int, end: Int): Slice =
    Slice(s, begin + start, Math.min(end + begin, limit))
  override def toString: String =
    s.substring(begin, limit)
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ParseLongBench {

  val longs: Array[Long] = Array(
    -1346837161442476189L, -4666345991836441070L, 4868830844043235709L,
    2992690405064579158L, -2017521011608078634L, -3039682866169364757L,
    8997687047891586260L, 5932727796276454607L, 4062739618560250554L,
    8668950167358198490L, -8565613821858118870L, 8049785848575684314L,
    -580831266940599830L, -3593199367295538945L, 8374322595267797482L,
    3088261552516619129L, -6879203747403593851L, -1842900848925949857L,
    4484592876047641351L, 5182973278356955602L, -6840392853855436945L,
    -4176340556015032222L, -536379174926548619L, 6343722878919863216L,
    1557757008211571405L, -334093799456298669L, 619602023052756397L,
    6904874397154297343L, -4332034907782234995L, -8767842695446545180L,
    -6127250063205613011L, 6902212562850963795L, 4778607575334665692L,
    7674074815344809639L, -3834944692798167050L, 7406081418831471202L,
    -9126886315356724563L, 8093878176633322645L, 2471547025788214028L,
    -5018828829942988155L, -6676531171364391367L, 8189793226936659851L,
    7150026713387306746L, -6065566098373722052L, 3281133763697608570L,
    957103694526079944L, -3009447279791131829L, -1995600795755716697L,
    2361055030313262510L, -4312828282749171343L, 8836216125516165138L,
    5548785979447786253L, 8567551485822958810L, 5931896003625723150L,
    3472058092439106147L, 4363240277904515929L, -2999484068697753019L,
    -8285358702782547958L, -2407429647076308777L, 4411565001760018584L,
    792384115860070648L, 3328145302561962294L, -2377559446421434356L,
    -7837698939558960516L, -565806101451282875L, -4792610084643070650L,
    2713520205731589923L, -6521104721472605988L, 5037187811345411645L,
    3866939564433764178L, -3851229228204678079L, -8171137274242372558L,
    -14713951794749384L, 2061783257002637655L, -7375571393873059570L,
    7402007407273053723L, -5104318069025846447L, -8956415532448219980L,
    4904595193891993401L, 5396360181536889307L, -8043917553767343384L,
    -3666269817017255250L, -6535587792359353103L, -4553034734642385706L,
    -7544140164897268962L, 2468330113904053484L, 5790319365381968237L,
    -2734383156062609640L, -4831208471935595172L, 4502079643250626043L,
    4778622151522470246L, 7233054223498326990L, 5833883346008509644L,
    -8013495378054295093L, 2944606201054530456L, -8608231828651976245L,
    -6957117814546267426L, -4744827311133020624L, 2640030216500286789L,
    8343959867315747844L)

  val strs: Array[CharSequence] =
    longs.map(_.toString)

  val seqs: Array[CharSequence] =
    longs.map { n =>
      val prefix = "x" * (n & 63).toInt
      val suffix = "y" * ((n * 7) & 63).toInt
      val i = prefix.length
      val s = n.toString
      Slice(prefix + s + suffix, i, s.length + i)
    }

  val str: CharSequence = "23948271429443"

  val seq: CharSequence = Slice("weigjewigjwi23948271429443jgewigjweiwjegiwgjiewjgeiwjg", 12, 26)

  def sumJava(css: Array[CharSequence]): Long = {
    var sum: Long = 0
    var i = 0
    while (i < css.length) {
      sum += java.lang.Long.parseLong(css(i).toString)
      i += 1
    }
    sum
  }

  def sumStd(css: Array[CharSequence]): Long = {
    var sum: Long = 0
    var i = 0
    while (i < css.length) {
      sum += css(i).toString.toLong
      i += 1
    }
    sum
  }

  def sumSafe(css: Array[CharSequence]): Long = {
    var sum: Long = 0
    var i = 0
    while (i < css.length) {
      sum += Util.parseLong(css(i))
      i += 1
    }
    sum
  }

  def sumUnsafe(css: Array[CharSequence]): Long = {
    var sum: Long = 0
    var i = 0
    while (i < css.length) {
      sum += Util.parseLongUnsafe(css(i))
      i += 1
    }
    sum
  }

  @Benchmark def stringArrayJava(): Long = sumJava(strs)
  @Benchmark def seqArrayJava(): Long = sumJava(seqs)
  @Benchmark def stringValueJava(): Long = java.lang.Long.parseLong(str.toString)
  @Benchmark def seqValueJava(): Long = java.lang.Long.parseLong(seq.toString)

  @Benchmark def stringArrayStd(): Long = sumStd(strs)
  @Benchmark def seqArrayStd(): Long = sumStd(seqs)
  @Benchmark def stringValueStd(): Long = str.toString.toLong
  @Benchmark def seqValueStd(): Long = seq.toString.toLong

  @Benchmark def stringArraySafe(): Long = sumSafe(strs)
  @Benchmark def seqArraySafe(): Long = sumSafe(seqs)
  @Benchmark def stringValueSafe(): Long = Util.parseLong(str)
  @Benchmark def seqValueSafe(): Long = Util.parseLong(seq)

  @Benchmark def stringArrayUnsafe(): Long = sumUnsafe(strs)
  @Benchmark def seqArrayUnsafe(): Long = sumUnsafe(seqs)
  @Benchmark def stringValueUnsafe(): Long = Util.parseLongUnsafe(str)
  @Benchmark def seqValueUnsafe(): Long = Util.parseLongUnsafe(seq)
}

package io.circe.numbers

import io.circe.testing.{ IntegralString, JsonNumberString }
import java.math.BigDecimal
import org.scalatest.FlatSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scala.math.{ BigDecimal => SBigDecimal }
import scala.util.Try

class BiggerDecimalSuite extends FlatSpec with GeneratorDrivenPropertyChecks {
  implicit override val generatorDrivenConfig = PropertyCheckConfiguration(
    minSuccessful = 1000,
    sizeRange = 10000
  )

  private[this] def doubleEqv(x: Double, y: Double): Boolean = java.lang.Double.compare(x, y) == 0
  private[this] def trailingZeros(i: BigInt): Int = i.toString.reverse.takeWhile(_ == '0').size
  private[this] def significantDigits(i: BigInt): Int = i.toString.size - trailingZeros(i)

  "fromDouble(0)" should "equal fromBigDecimal(ZERO) (#348)" in {
    assert(BiggerDecimal.fromDouble(0) === BiggerDecimal.fromBigDecimal(BigDecimal.ZERO))
  }

  "fromDouble" should "round-trip Double values" in forAll { (value: Double) =>
    val d = BiggerDecimal.fromDouble(value)

    assert(
      doubleEqv(d.toDouble, value) && d.toBigDecimal.exists { roundTripped =>
        doubleEqv(roundTripped.doubleValue, value)
      }
    )
  }

  it should "round-trip negative zero" in {
    val d = BiggerDecimal.fromDouble(-0.0)

    assert(doubleEqv(d.toDouble, -0.0))
  }

  "signum" should "agree with BigInteger" in forAll { (value: BigInt) =>
    val d = BiggerDecimal.fromBigInteger(value.bigInteger)

    assert(d.signum == value.signum)
  }

  it should "agree with BigDecimal" in forAll { (value: SBigDecimal) =>
    val d = BiggerDecimal.fromBigDecimal(value.bigDecimal)

    assert(d.signum == value.signum)
  }

  it should "agree with Long" in forAll { (value: Long) =>
    val d = BiggerDecimal.fromLong(value)

    assert(d.signum == value.signum)
  }

  it should "agree with Double" in forAll { (value: Double) =>
    val d = BiggerDecimal.fromDouble(value)

    assert(d.signum == value.signum)
  }

  "fromLong" should "round-trip Long values" in forAll { (value: Long) =>
    val d = BiggerDecimal.fromLong(value)

    assert(d.toBigDecimal.map(_.longValue) === Some(value))
  }

  "toLong" should "round-trip Long values" in forAll { (value: Long) =>
    val d = BiggerDecimal.fromLong(value)

    assert(d.toLong === Some(value))
  }

  "fromLong and fromDouble" should "agree on Int-sized integral values" in forAll { (value: Int) =>
    val dl = BiggerDecimal.fromLong(value.toLong)
    val dd = BiggerDecimal.fromDouble(value.toDouble)

    assert(dl === dd)
  }

  "fromBigDecimal" should "round-trip BigDecimal values" in forAll { (value: SBigDecimal) =>
    val result = BiggerDecimal.fromBigDecimal(value.underlying)

    assert(
      Try(new BigDecimal(value.toString)).toOption.forall { parsedValue =>
        result.toBigDecimal.exists { roundTripped =>
          roundTripped.compareTo(parsedValue) == 0
        }
      }
    )
  }

  "fromBigInteger" should "round-trip BigInteger values" in forAll { (value: BigInt) =>
    assert(BiggerDecimal.fromBigInteger(value.underlying).toBigInteger === Some(value.underlying))
  }
  
  "integralIsValidLong" should "agree with toLong" in forAll { (input: IntegralString) =>
    assert(BiggerDecimal.integralIsValidLong(input.value) === Try(input.value.toLong).isSuccess)
  }

  "parseBiggerDecimal" should "parse any BigDecimal string" in forAll { (value: SBigDecimal) =>
    val d = BiggerDecimal.parseBiggerDecimal(value.toString)

    assert(
      d.nonEmpty && Try(new BigDecimal(value.toString)).toOption.forall { parsedValue =>
        d.flatMap(_.toBigDecimal).exists { roundTripped =>
          roundTripped.compareTo(parsedValue) == 0
        }
      }
    )
  }

  it should "parse number strings with big exponents" in {
    forAll { (integral: BigInt, fractionalDigits: BigInt, exponent: BigInt) =>
      val fractional = fractionalDigits.abs
      val s = s"$integral.${ fractional }e$exponent"

      val scale = -exponent + (
        (integral == 0, fractional == 0) match {
          case (true, true) => 0
          case (_, true) => -trailingZeros(integral)
          case (_, _) => significantDigits(fractional)
        }
      )

      (BiggerDecimal.parseBiggerDecimal(s), Try(new BigDecimal(s)).toOption) match {
        case (Some(parsedBiggerDecimal), Some(parsedBigDecimal)) if scale.isValidInt =>
          assert(parsedBiggerDecimal.toBigDecimal.exists(_.compareTo(parsedBigDecimal) == 0))
        case (Some(_), None) => assert(true)
        case _ => assert(false)
      }
    }
  }

  it should "parse JSON numbers" in forAll { (jns: JsonNumberString) =>
    assert(BiggerDecimal.parseBiggerDecimal(jns.value).nonEmpty)
  }

  it should "fail on bad input" in {
    val badNumbers = List("", "x", "01", "1x", "1ex", "1.0x", "1.x", "1e-x", "1e-0x")

    badNumbers.foreach { input =>
      assert(BiggerDecimal.parseBiggerDecimal(input) === None)
    }
  }
}

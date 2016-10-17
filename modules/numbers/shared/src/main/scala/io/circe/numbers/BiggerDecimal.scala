package io.circe.numbers

import java.math.{ BigDecimal, BigInteger }
import scala.annotation.{ switch, tailrec }

/**
 * Represents a large decimal number.
 *
 * In theory `BigDecimal` can represent a very large range of valid JSON numbers (in most cases if a
 * JSON number string can fit in memory, it's possible to construct an exact `BigDecimal`
 * representation), but in practice this becomes intractable for many small JSON numbers (e.g.
 * "1e2147483648" cannot be directly parsed as a `BigDecimal`).
 *
 * This type makes it possible to represent much, much larger numbers efficiently (although it
 * doesn't support many operations on these values). It also makes it possible to distinguish
 * between positive and negative zeros (unlike `BigDecimal`), which may be useful in some
 * applications.
 */
sealed abstract class BiggerDecimal extends Serializable {
  def isWhole: Boolean
  def isNegativeZero: Boolean

  /**
   * The sign of this value.
   *
   * Returns -1 if it is less than 0, +1 if it is greater than 0, and 0 if it is
   * equal to 0. Note that this follows the behavior of [[scala.Double]] for
   * negative zero (returning 0).
   */
  def signum: Int

  /**
   * Convert to a `java.math.BigDecimal` if the `scale` is within the range of [[scala.Int]].
   */
  def toBigDecimal: Option[BigDecimal]

  /**
   * Convert to a `java.math.BigInteger` if this is a sufficiently small whole number.
   *
   */
  def toBigIntegerWithMaxDigits(maxDigits: BigInteger): Option[BigInteger]

  /**
   * Convert to a `java.math.BigInteger` if this is a sufficiently small whole number.
   *
   * The maximum number of digits is somewhat arbitrarily set at 2^18 digits, since larger values
   * may require excessive processing power. Larger values may be converted to `BigInteger` with
   * [[toBigIntegerWithMaxDigits]] or via [[toBigDecimal]].
   */
  final def toBigInteger: Option[BigInteger] = toBigIntegerWithMaxDigits(BiggerDecimal.MaxBigIntegerDigits)

  /**
   * Convert to the nearest [[scala.Double]].
   */
  def toDouble: Double

  /**
   * Convert to a [[scala.Long]] if this is a valid `Long` value.
   */
  def toLong: Option[Long]

  /**
   * Convert to the nearest [[scala.Long]].
   */
  def truncateToLong: Long
}

/**
 * Represents numbers as an unscaled value and a scale.
 *
 * This representation is the same as that used by `java.math.BigDecimal`, with two differences.
 * First, the scale is a `java.math.BigInteger`, not a [[scala.Int]], and the unscaled value will
 * never be an exact multiple of ten (in order to facilitate comparison).
 */
private[numbers] final class SigAndExp(
  val unscaled: BigInteger,
  val scale: BigInteger
) extends BiggerDecimal {
  def isWhole: Boolean = scale.signum != 1
  def isNegativeZero: Boolean = false
  def signum: Int = unscaled.signum

  def toBigDecimal: Option[BigDecimal] =
    if (scale.compareTo(BiggerDecimal.MaxInt) <= 0 && scale.compareTo(BiggerDecimal.MinInt) >= 0) {
      Some(new BigDecimal(unscaled, scale.intValue))
    } else None

  def toBigIntegerWithMaxDigits(maxDigits: BigInteger): Option[BigInteger] =
    if (!isWhole) None else {
      val digits = BigInteger.valueOf(unscaled.toString.length.toLong).subtract(scale)

      if (digits.compareTo(BiggerDecimal.MaxBigIntegerDigits) > 0) None else Some(
        new BigDecimal(unscaled, scale.intValue).toBigInteger
      )
    }

  def toDouble: Double = if (scale.compareTo(BiggerDecimal.MaxInt) <= 0 && scale.compareTo(BiggerDecimal.MinInt) >= 0) {
    new BigDecimal(unscaled, scale.intValue).doubleValue
  } else (if (scale.signum == 1) 0.0 else Double.PositiveInfinity) * unscaled.signum

  def toLong: Option[Long] = if (!this.isWhole) None else {
    toBigInteger match {
      case Some(i) =>
        val asLong = i.longValue

        if (BigInteger.valueOf(asLong) == i) Some(asLong) else None
      case None => None
    }
  }

  def truncateToLong: Long = toDouble.round

  override def equals(that: Any): Boolean = that match {
    case other: SigAndExp =>
      (unscaled == BigInteger.ZERO && other.unscaled == BigInteger.ZERO) ||
      (unscaled == other.unscaled && scale == other.scale)
    case _ => false
  }

  override def hashCode: Int = if (unscaled == BigInteger.ZERO) 0 else scale.hashCode + unscaled.hashCode

  override def toString: String = if (scale == BigInteger.ZERO) unscaled.toString else {
    s"${ unscaled }e${ scale.negate }"
  }
}

final object BiggerDecimal {
  private[numbers] val MaxBigIntegerDigits: BigInteger = BigInteger.valueOf(1L << 18)

  private[numbers] val MaxInt: BigInteger = BigInteger.valueOf(Int.MaxValue)
  private[numbers] val MinInt: BigInteger = BigInteger.valueOf(Int.MinValue)

  val NegativeZero: BiggerDecimal = new BiggerDecimal {
    final def isWhole: Boolean = true
    final def isNegativeZero: Boolean = true
    final def signum: Int = 0
    final val toBigDecimal: Option[BigDecimal] = Some(BigDecimal.ZERO)
    final def toBigIntegerWithMaxDigits(maxDigits: BigInteger): Option[BigInteger] =
      Some(BigInteger.ZERO)
    final def toDouble: Double = -0.0
    final val toLong: Option[Long] = Some(truncateToLong)
    final def truncateToLong: Long = 0L

    final override def equals(that: Any): Boolean = that match {
      case other: BiggerDecimal => other.isNegativeZero
      case _ => false
    }
    final override def hashCode: Int = (-0.0).hashCode
    final override def toString: String = "-0"
  }

  @tailrec
  private[this] def removeTrailingZeros(d: BigInteger, depth: Long): SigAndExp = if (d == BigInteger.ZERO) {
    new SigAndExp(d, BigInteger.ZERO)
  } else {
    val divAndRem = d.divideAndRemainder(BigInteger.TEN)

    if (divAndRem(1) == BigInteger.ZERO) removeTrailingZeros(divAndRem(0), depth + 1) else {
      new SigAndExp(d, BigInteger.valueOf(-depth))
    }
  }

  def fromBigInteger(i: BigInteger): BiggerDecimal = removeTrailingZeros(i, 0L)

  def fromBigDecimal(d: BigDecimal): BiggerDecimal = try {
    val noZeros = d.stripTrailingZeros
    new SigAndExp(noZeros.unscaledValue, BigInteger.valueOf(noZeros.scale.toLong))
  } catch {
    case _: ArithmeticException =>
      val unscaledAndZeros = removeTrailingZeros(d.unscaledValue, 0L)

      new SigAndExp(
        unscaledAndZeros.unscaled,
        BigInteger.valueOf(d.scale.toLong).subtract(unscaledAndZeros.scale)
      )
  }

  def fromLong(d: Long): BiggerDecimal = fromBigDecimal(BigDecimal.valueOf(d))
  def fromDouble(d: Double): BiggerDecimal = if (java.lang.Double.compare(d, -0.0) == 0) {
    NegativeZero
  } else fromBigDecimal(BigDecimal.valueOf(d))

  private[this] final val MaxLongString = "9223372036854775807"
  private[this] final val MinLongString = "-9223372036854775808"

  /**
   * Is a string representing an integral value a valid [[scala.Long]]?
   *
   * Note that this method assumes that the input is a valid integral JSON
   * number string (e.g. that it does have leading zeros).
   */
  def integralIsValidLong(s: String): Boolean = {
    val bound = if (s.charAt(0) == '-') MinLongString else MaxLongString

    s.length < bound.length || (s.length == bound.length && s.compareTo(bound) <= 0)
  }

  private[this] final val FAILED = 0
  private[this] final val START = 1
  private[this] final val AFTER_ZERO = 2
  private[this] final val AFTER_DOT = 3
  private[this] final val FRACTIONAL = 4
  private[this] final val AFTER_E = 5
  private[this] final val AFTER_EXP_SIGN = 6
  private[this] final val EXPONENT = 7
  private[this] final val INTEGRAL = 8

  /**
   * Parse string into [[BiggerDecimal]].
   */
  def parseBiggerDecimal(input: String): Option[BiggerDecimal] = Option(parseBiggerDecimalUnsafe(input))

  /**
   * Parse string into [[BiggerDecimal]], returning `null` on parsing failure.
   */
  def parseBiggerDecimalUnsafe(input: String): BiggerDecimal = {
    val len = input.length

    if (len == 0) null else {
      var zeros = 0
      var decIndex = -1
      var expIndex = -1
      var i = if (input.charAt(0) == '-') 1 else 0
      var c = input.charAt(i)

      var state = if (input.charAt(i) != '0') START else {
        i = i + 1
        AFTER_ZERO
      }

      while (i < len && state != FAILED) {
        val c = input.charAt(i)

        (state: @switch) match {
          case START =>
            if (c >= '1' && c <= '9') {
              state = INTEGRAL
            } else {
              state = FAILED
            }
          case AFTER_ZERO =>
            if (c == '.') {
              state = AFTER_DOT
            } else if (c == 'e' || c == 'E') {
              state = AFTER_E
            } else {
              state = FAILED
            }
          case INTEGRAL =>
            if (c == '0') {
              zeros = zeros + 1
              state = INTEGRAL
            } else if (c >= '1' && c <= '9') {
              zeros = 0
              state = INTEGRAL
            } else if (c == '.') {
              state = AFTER_DOT
            } else if (c == 'e' || c == 'E') {
              state = AFTER_E
            } else {
              state = FAILED
            }
          case AFTER_DOT =>
            decIndex = i - 1
            if (c == '0') {
              zeros = 1
              state = FRACTIONAL
            } else if (c >= '1' && c <= '9') {
              zeros = 0
              state = FRACTIONAL
            } else {
              state = FAILED
            }
          case AFTER_E =>
            expIndex = i - 1
            if (c >= '0' && c <= '9') {
              state = EXPONENT
            } else if (c == '+' || c == '-') {
              state = AFTER_EXP_SIGN
            } else {
              state = FAILED
            }
          case FRACTIONAL =>
            if (c == '0') {
              zeros = zeros + 1
              state = FRACTIONAL
            } else if (c >= '1' && c <= '9') {
              zeros = 0
              state = FRACTIONAL
            } else if (c == 'e' || c == 'E') {
              state = AFTER_E
            } else {
              state = FAILED
            }
          case AFTER_EXP_SIGN =>
            if (c >= '0' && c <= '9') {
              state = EXPONENT
            } else {
              state = FAILED
            }
          case EXPONENT =>
            if (c >= '0' && c <= '9') {
              state = EXPONENT
            } else {
              state = FAILED
            }
        }

        i += 1
      }

      if (state == FAILED) null else {
        val integral = if (decIndex >= 0) input.substring(0, decIndex) else {
          if (expIndex == -1) input else {
            input.substring(0, expIndex)
          }
        }

        val fractional = if (decIndex == -1) "" else {
          if (expIndex == -1) input.substring(decIndex + 1) else {
            input.substring(decIndex + 1, expIndex)
          }
        }

        val unscaledString = integral + fractional
        val unscaled = new BigInteger(unscaledString.substring(0, unscaledString.length - zeros))
        val rescale = BigInteger.valueOf(fractional.length.toLong - zeros)
        val exponent = if (expIndex == -1) BigInteger.ZERO else {
          new BigInteger(input.substring(expIndex + 1))
        }

        if (input.charAt(0) == '-' && unscaled == BigInteger.ZERO) {
          BiggerDecimal.NegativeZero
        } else {
          new SigAndExp(
            unscaled,
            if (unscaled == BigInteger.ZERO) BigInteger.ZERO else rescale.subtract(exponent)
          )
        }
      }
    }
  }
}

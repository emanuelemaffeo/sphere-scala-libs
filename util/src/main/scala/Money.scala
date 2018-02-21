package io.sphere.util

import java.math.MathContext
import java.text.NumberFormat
import java.util.Currency

import cats.Monoid

import scala.math._
import BigDecimal.RoundingMode._
import scala.math.BigDecimal.RoundingMode

sealed trait BaseMoney {
  def `type`: String

  def currency: Currency

  // Use with CAUTION! will loose precision in case of a high precision money value
  def centAmount: Long

  def fractionDigits: Int

  def toMoneyWithPrecisionLoss: Money

  def + (m: Money): BaseMoney
  def + (m: HighPrecisionMoney): BaseMoney
  def + (m: BaseMoney): BaseMoney
  def + (m: BigDecimal)(implicit mode: RoundingMode): BaseMoney

  def - (m: Money): BaseMoney
  def - (m: HighPrecisionMoney): BaseMoney
  def - (m: BaseMoney): BaseMoney
  def - (m: BigDecimal)(implicit mode: RoundingMode): BaseMoney

  def * (m: Money)(implicit mode: RoundingMode): BaseMoney
  def * (m: HighPrecisionMoney)(implicit mode: RoundingMode): BaseMoney
  def * (m: BaseMoney)(implicit mode: RoundingMode): BaseMoney
  def * (m: BigDecimal)(implicit mode: RoundingMode): BaseMoney
}

object BaseMoney {
  def requireSameCurrency(m1: BaseMoney, m2: BaseMoney): Unit =
    require(m1.currency eq m2.currency, s"${m1.currency} != ${m2.currency}")

  def toScalaRoundingMode(mode: java.math.RoundingMode): RoundingMode =
    BigDecimal.RoundingMode(mode.ordinal())
}

/**
 * Represents an amount of money in a certain currency.
 *
 * This implementation does not support fractional money units (eg a tenth cent).
 * Amounts are always rounded to the nearest, smallest unit of the respective currency.
 * The rounding mode can be specified using an implicit `BigDecimal.RoundingMode`.
 *
 * @param amount The amount as a decimal value. The scale must be equal to or less than the
 *               number of fractional digits of the currency.
 * @param currency The currency of the amount.
 */
case class Money(amount: BigDecimal, currency: Currency) extends BaseMoney with Ordered[Money] {
  import Money._

  require(amount.scale == currency.getDefaultFractionDigits,
    "The scale of the given amount does not match the scale of the provided currency." +
    " - " + amount.scale + " <-> " + currency.getDefaultFractionDigits)

  private val centFactor: Double = 1 / pow(10, currency.getDefaultFractionDigits)

  lazy val centAmount: Long = (amount / centFactor).toLong

  private val backwardsCompatibleRoundingModeForOperations = BigDecimal.RoundingMode.HALF_EVEN

  val `type` = TypeName

  lazy val fractionDigits = currency.getDefaultFractionDigits

  def withCentAmount(centAmount: Long): Money = {
    val newAmount = BigDecimal(centAmount) * centFactor
    copy(amount = newAmount.setScale(currency.getDefaultFractionDigits))
  }

  def toHighPrecisionMoney(fractionDigits: Int): HighPrecisionMoney =
    HighPrecisionMoney.fromMoney(this, fractionDigits)

  /**
   * Creates a new Money instance with the same currency and the amount conforming
   * to the given MathContext (scale and rounding mode).
   */
  def apply(mc: MathContext): Money =
    fromDecimalAmount(this.amount(mc), this.currency)(RoundingMode.HALF_EVEN)

  def + (m: Money): Money = {
    BaseMoney.requireSameCurrency(this, m)

    fromDecimalAmount(this.amount + m.amount, this.currency)(backwardsCompatibleRoundingModeForOperations)
  }

  def + (m: HighPrecisionMoney): HighPrecisionMoney =
    this.toHighPrecisionMoney(m.fractionDigits) + m

  def + (money: BaseMoney): BaseMoney = money match {
    case m: Money ⇒ this + m
    case m: HighPrecisionMoney ⇒ this + m
  }

  def + (m: BigDecimal)(implicit mode: RoundingMode): Money =
    this + fromDecimalAmount(m, this.currency)

  def - (m: Money): Money = {
    BaseMoney.requireSameCurrency(this, m)
    fromDecimalAmount(this.amount - m.amount, this.currency)(backwardsCompatibleRoundingModeForOperations)
  }

  def - (money: BaseMoney): BaseMoney = money match {
    case m: Money ⇒ this - m
    case m: HighPrecisionMoney ⇒ this - m
  }
  
  def - (m: HighPrecisionMoney): HighPrecisionMoney =
    this.toHighPrecisionMoney(m.fractionDigits) - m

  def - (m: BigDecimal)(implicit mode: RoundingMode): Money =
    this - fromDecimalAmount(m, this.currency)

  def * (m: Money)(implicit mode: RoundingMode): Money = {
    BaseMoney.requireSameCurrency(this, m)
    this * m.amount
  }
  
  def * (m: HighPrecisionMoney)(implicit mode: RoundingMode): HighPrecisionMoney =
    this.toHighPrecisionMoney(m.fractionDigits) * m

  def * (money: BaseMoney)(implicit mode: RoundingMode): BaseMoney = money match {
    case m: Money ⇒ this * m
    case m: HighPrecisionMoney ⇒ this * m
  }
  
  def * (m: BigDecimal)(implicit mode: RoundingMode): Money =
    fromDecimalAmount((this.amount * m).setScale(this.amount.scale, mode), this.currency)

  /** Divide to integral value + remainder */
  def /% (m: BigDecimal)(implicit mode: RoundingMode): (Money, Money) = {
    val (result, remainder) = this.amount /% m

    (fromDecimalAmount(result, this.currency), fromDecimalAmount(remainder, this.currency))
  }

  def % (m: Money)(implicit mode: RoundingMode): Money = this.remainder(m)

  def % (m: BigDecimal)(implicit mode: RoundingMode): Money =
    this.remainder(fromDecimalAmount(m, this.currency))

  def remainder(m: Money)(implicit mode: RoundingMode): Money = {
    BaseMoney.requireSameCurrency(this, m)

    fromDecimalAmount(this.amount.remainder(m.amount), this.currency)
  }

  def remainder(m: BigDecimal)(implicit mode: RoundingMode): Money = this.remainder(fromDecimalAmount(m, this.currency)(RoundingMode.HALF_EVEN))

  def unary_- = fromDecimalAmount(-this.amount, this.currency)(BigDecimal.RoundingMode.UNNECESSARY)

  /**
   * Partitions this amount of money into several parts where the size of the individual
   * parts are defined by the given ratios. The partitioning takes care of not losing or gaining
   * any money by distributing any remaining "cents" evenly across the partitions.
   *
   * <p>Example: (0.05 EUR) partition (3,7) == Seq(0.02 EUR, 0.03 EUR)</p>
   */
  def partition(ratios: Int*): Seq[Money] = {
    val total = ratios.sum
    val amountInCents = (this.amount / centFactor).toBigInt
    val amounts = Seq(ratios.map(amountInCents * _ / total):_*)
    var remainder = amounts.foldLeft(amountInCents)(_ - _)
    amounts.map(amount => {
      remainder -= 1
      fromDecimalAmount(BigDecimal(amount + (if (remainder >= 0) 1 else 0)) * centFactor, this.currency)(backwardsCompatibleRoundingModeForOperations)
    })
  }

  def toMoneyWithPrecisionLoss: Money = this

  def compare(that: Money): Int =  {
    BaseMoney.requireSameCurrency(this, that)
    this.amount compare that.amount
  }

  override def toString = ("%." + this.currency.getDefaultFractionDigits + "f %s")
    .format(this.amount, this.currency.getSymbol)

  def toString(nf: NumberFormat) = {
    require(nf.getCurrency eq this.currency)
    nf.format(this.amount.doubleValue) + " " + this.currency.getSymbol
  }
}

object Money {
  final implicit class MoneyNotation(val amount: Double) extends AnyVal {
    def EUR = Money.EUR(amount)
    def USD = Money.USD(amount)
    def GBP = Money.GBP(amount)
    def JPY = Money.JPY(amount)
  }

  def EUR(amount: BigDecimal): Money = fromDecimalAmount(amount, Currency.getInstance("EUR"))(BigDecimal.RoundingMode.HALF_EVEN)
  def USD(amount: BigDecimal): Money = fromDecimalAmount(amount, Currency.getInstance("USD"))(BigDecimal.RoundingMode.HALF_EVEN)
  def GBP(amount: BigDecimal): Money = fromDecimalAmount(amount, Currency.getInstance("GBP"))(BigDecimal.RoundingMode.HALF_EVEN)
  def JPY(amount: BigDecimal): Money = fromDecimalAmount(amount, Currency.getInstance("JPY"))(BigDecimal.RoundingMode.HALF_EVEN)

  val TypeName = "centAmount"

  def fromDecimalAmount(amount: BigDecimal, currency: Currency)(implicit mode: RoundingMode) =
    Money(amount.setScale(currency.getDefaultFractionDigits, mode), currency)

  def fromCentAmount(centAmount: Long, currency: Currency) = {
    val centFactor = BigDecimal(1) / BigDecimal(10).pow(currency.getDefaultFractionDigits)
    val amount = BigDecimal(centAmount) * centFactor

    fromDecimalAmount(amount, currency)(BigDecimal.RoundingMode.UNNECESSARY)
  }

  def zero(currency: Currency) =
    fromCentAmount(0L, currency)

  implicit def moneyMonoid(implicit c: Currency): Monoid[Money] = new Monoid[Money] {
    def combine(x: Money, y: Money): Money = x + y
    val empty: Money = Money.zero(c)
  }

  implicit def moneyMonoid(implicit c: Currency, mode: RoundingMode): Monoid[Money] = new Monoid[Money] {
    def combine(x: Money, y: Money): Money = x + y
    val empty: Money = Money.zero(c)
  }
}

case class HighPrecisionMoney(amount: BigDecimal, fractionDigits: Int, centAmount: Long, currency: Currency) extends BaseMoney with Ordered[Money] {
  import HighPrecisionMoney._

  require(amount.scale == fractionDigits,
    "The scale of the given amount does not match the scale of the provided currency." +
    " - " + amount.scale + " <-> " + fractionDigits)

  require(fractionDigits >= currency.getDefaultFractionDigits,
    "`fractionDigits` should be  >= than the default fraction digits of the currency.")

  val `type` = TypeName

  lazy val preciseAmountAsLong: Long =
    (amount * BigDecimal(10).pow(fractionDigits)).toLongExact

  def withFractionDigits(fd: Int)(implicit mode: RoundingMode) = {
    val newAmount = amount.setScale(fd, mode)

    HighPrecisionMoney(newAmount, fd, roundToCents(newAmount, currency), currency)
  }

  def + (other: HighPrecisionMoney): HighPrecisionMoney =
    calc(this, other, _ + _)(RoundingMode.UNNECESSARY)

  def + (m: Money): HighPrecisionMoney =
    this + m.toHighPrecisionMoney(fractionDigits)

  def + (money: BaseMoney): BaseMoney = money match {
    case m: Money ⇒ this + m
    case m: HighPrecisionMoney ⇒ this + m
  }

  def + (other: BigDecimal)(implicit mode: RoundingMode): HighPrecisionMoney =
    this + fromDecimalAmount(other, this.fractionDigits, this.currency)

  def - (other: HighPrecisionMoney): HighPrecisionMoney =
    calc(this, other, _ - _)(RoundingMode.UNNECESSARY)

  def - (m: Money): HighPrecisionMoney =
    this - m.toHighPrecisionMoney(fractionDigits)

  def - (money: BaseMoney): BaseMoney = money match {
    case m: Money ⇒ this - m
    case m: HighPrecisionMoney ⇒ this - m
  }

  def - (other: BigDecimal)(implicit mode: RoundingMode): HighPrecisionMoney =
    this - fromDecimalAmount(other, this.fractionDigits, this.currency)

  def * (other: HighPrecisionMoney)(implicit mode: RoundingMode): HighPrecisionMoney =
    calc(this, other, _ * _)

  def * (m: Money)(implicit mode: RoundingMode): HighPrecisionMoney =
    this * m.toHighPrecisionMoney(fractionDigits)

  def * (money: BaseMoney)(implicit mode: RoundingMode): BaseMoney = money match {
    case m: Money ⇒ this * m
    case m: HighPrecisionMoney ⇒ this * m
  }
  
  def * (other: BigDecimal)(implicit mode: RoundingMode): HighPrecisionMoney =
    this * fromDecimalAmount(other, this.fractionDigits, this.currency)

  /** Divide to integral value + remainder */
  def /% (m: BigDecimal)(implicit mode: RoundingMode): (HighPrecisionMoney, HighPrecisionMoney) = {
    val (result, remainder) = this.amount /% m

    fromDecimalAmount(result, fractionDigits, this.currency) →
      fromDecimalAmount(remainder, fractionDigits, this.currency)
  }

  def % (other: HighPrecisionMoney)(implicit mode: RoundingMode): HighPrecisionMoney =
    this.remainder(other)

  def % (other: BigDecimal)(implicit mode: RoundingMode): HighPrecisionMoney =
    this.remainder(fromDecimalAmount(other, this.fractionDigits, this.currency))

  def remainder(other: HighPrecisionMoney)(implicit mode: RoundingMode): HighPrecisionMoney =
    calc(this, other, _ remainder _)

  def remainder(other: BigDecimal)(implicit mode: RoundingMode): HighPrecisionMoney =
    this.remainder(fromDecimalAmount(other, this.fractionDigits, this.currency))

  def unary_- = fromDecimalAmount(-this.amount, this.fractionDigits, this.currency)(BigDecimal.RoundingMode.UNNECESSARY)

  /**
   * Partitions this amount of money into several parts where the size of the individual
   * parts are defined by the given ratios. The partitioning takes care of not losing or gaining
   * any money by distributing any remaining "cents" evenly across the partitions.
   *
   * <p>Example: (0.05 EUR) partition (3,7) == Seq(0.02 EUR, 0.03 EUR)</p>
   */
  def partition(ratios: Int*)(implicit mode: RoundingMode): Seq[HighPrecisionMoney] = {
    val total = ratios.sum
    val factor = BigDecimal(1) / BigDecimal(10).pow(fractionDigits)
    val amountAsInt = (this.amount / factor).toBigInt
    val portionAmounts = ratios.map(amountAsInt * _ / total)
    var remainder = portionAmounts.foldLeft(amountAsInt)(_ - _)

    portionAmounts.map { portionAmount ⇒
      remainder -= 1

      fromDecimalAmount(BigDecimal(portionAmount + (if (remainder >= 0) 1 else 0)) * factor, this.fractionDigits, this.currency)
    }
  }

  def toMoneyWithPrecisionLoss: Money =
    Money.fromCentAmount(this.centAmount, currency)

  def compare(other: Money): Int = {
    BaseMoney.requireSameCurrency(this, other)

    this.amount compare other.amount
  }

  override def toString = ("%." + this.fractionDigits + "f %s")
    .format(this.amount, this.currency.getSymbol)

  def toString(nf: NumberFormat) = {
    require(nf.getCurrency eq this.currency)

    nf.format(this.amount.doubleValue) + " " + this.currency.getSymbol
  }
}

object HighPrecisionMoney {
  final implicit class HighPrecisionMoneyNotation(val amount: Double) extends AnyVal {
    def EUR = HighPrecisionMoney.EUR(amount)
    def USD = HighPrecisionMoney.USD(amount)
    def GBP = HighPrecisionMoney.GBP(amount)
    def JPY = HighPrecisionMoney.JPY(amount)
  }

  def EUR(amount: BigDecimal) = simpleValueMeantToBeUsedOnlyInTests(amount, "EUR")
  def USD(amount: BigDecimal) = simpleValueMeantToBeUsedOnlyInTests(amount, "USD")
  def GBP(amount: BigDecimal) = simpleValueMeantToBeUsedOnlyInTests(amount, "GBP")
  def JPY(amount: BigDecimal) = simpleValueMeantToBeUsedOnlyInTests(amount, "JPY")

  val TypeName = "preciseAmount"

  private def simpleValueMeantToBeUsedOnlyInTests(amount: BigDecimal, currencyCode: String): HighPrecisionMoney = {
    val currency = Currency.getInstance(currencyCode)

    fromDecimalAmount(amount, currency.getDefaultFractionDigits, currency)(BigDecimal.RoundingMode.HALF_EVEN)
  }

  def roundToCents(amount: BigDecimal, currency: Currency)(implicit mode: RoundingMode): Long =
    (amount.setScale(currency.getDefaultFractionDigits, mode) / centFactor(currency)).toLong

  def sameScale(m1: HighPrecisionMoney, m2: HighPrecisionMoney): (BigDecimal, BigDecimal, Int) = {
    val newFractionDigits = math.max(m1.fractionDigits, m2.fractionDigits)

    def scale(m: HighPrecisionMoney, s: Int) =
      if (m.fractionDigits < s) m.amount.setScale(s)
      else if (m.fractionDigits == s) m.amount
      else throw new IllegalStateException("Downscale is not allowed/expected at this point!")

    (scale(m1, newFractionDigits), scale(m2, newFractionDigits), newFractionDigits)
  }

  def calc(m1: HighPrecisionMoney, m2: HighPrecisionMoney, fn: (BigDecimal, BigDecimal) ⇒ BigDecimal)(implicit mode: RoundingMode) = {
    BaseMoney.requireSameCurrency(m1, m2)

    val (a1, a2, fd) = sameScale(m1, m2)

    fromDecimalAmount(fn(a1, a2), fd, m1.currency)
  }

  def factor(fractionDigits: Int) = BigDecimal(1) / BigDecimal(10).pow(fractionDigits)
  def centFactor(currency: Currency) = factor(currency.getDefaultFractionDigits)

  def fromDecimalAmount(amount: BigDecimal, fractionDigits: Int, currency: Currency)(implicit mode: RoundingMode) = {
    val scaledAmount = amount.setScale(fractionDigits, mode)

    HighPrecisionMoney(scaledAmount, fractionDigits, roundToCents(scaledAmount, currency), currency)
  }

  def fromCentAmount(centAmount: Long, fractionDigits: Int, currency: Currency) = {
    val amount = BigDecimal(centAmount) * centFactor(currency)

    HighPrecisionMoney(amount.setScale(fractionDigits, BigDecimal.RoundingMode.UNNECESSARY), fractionDigits, centAmount, currency)
  }

  def zero(fractionDigits: Int, currency: Currency) =
    fromCentAmount(0L, fractionDigits, currency)

  /* centAmount provides an escape hatch in cases where the default rounding mode is not applicable */
  def fromPreciseAmount(preciseAmount: Long, fractionDigits: Int, currency: Currency, centAmount: Option[Long]) = {
    val amount = BigDecimal(preciseAmount) * factor(fractionDigits)
    val scaledAmount = amount.setScale(fractionDigits, BigDecimal.RoundingMode.UNNECESSARY)

    // TODO: revisit this part! the rounding mode might be dynamic and configured elsewhere
    val actualCentAmount = centAmount getOrElse roundToCents(scaledAmount, currency)(
      BigDecimal.RoundingMode.HALF_EVEN)

    HighPrecisionMoney(scaledAmount, fractionDigits, actualCentAmount, currency)
  }

  def fromMoney(money: Money, fractionDigits: Int): HighPrecisionMoney =
    HighPrecisionMoney(money.amount.setScale(fractionDigits, RoundingMode.UNNECESSARY), fractionDigits, money.centAmount, money.currency)

  def monoid(fractionDigits: Int, c: Currency): Monoid[HighPrecisionMoney] = new Monoid[HighPrecisionMoney] {
    def combine(x: HighPrecisionMoney, y: HighPrecisionMoney): HighPrecisionMoney = x + y
    val empty: HighPrecisionMoney = HighPrecisionMoney.zero(fractionDigits, c)
  }
}
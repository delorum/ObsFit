package ru.obsfit;

import java.math.BigDecimal;
import java.math.MathContext;

/** Stable decimal text for persisted values, without units, trailing zeroes or exponents. */
public final class Numbers {
  private Numbers() {}

  public static String plain(double value) {
    return BigDecimal.valueOf(value)
        .round(new MathContext(15))
        .stripTrailingZeros()
        .toPlainString();
  }
}

package ru.obsfit;

import static org.junit.Assert.*;

import org.junit.Test;

public class NumbersTest {
  @Test
  public void plainTextHasNoUnitsExponentOrUnnecessaryZeroes() {
    assertEquals("35", Numbers.plain(35));
    assertEquals("0", Numbers.plain(-0d));
    assertEquals("12.5", Numbers.plain(12.5));
    assertEquals("-12.5", Numbers.plain(-12.5));
    assertEquals("0.000000000000001", Numbers.plain(1e-15));
    assertEquals("1000000000", Numbers.plain(1e9));
  }

  @Test
  public void persistedPrecisionIsUnchanged() {
    assertEquals("1.23456789012346", Numbers.plain(1.234567890123456));
    assertEquals("1234567890.12346", Numbers.plain(1234567890.123456));
    assertEquals("0.001", Numbers.plain(.001));
  }

  @Test
  public void nonFiniteNumbersCannotBePersisted() {
    for (double value :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
      assertThrows(NumberFormatException.class, () -> Numbers.plain(value));
  }
}

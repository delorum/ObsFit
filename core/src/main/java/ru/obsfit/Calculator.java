package ru.obsfit;

import java.math.*;

/** Small arithmetic grammar; never evaluates code or accesses Android. */
public final class Calculator {
  private static final MathContext PRECISION = MathContext.DECIMAL128;
  private final String source;
  private int position;

  private Calculator(String source) {
    this.source = source;
  }

  public static String evaluate(String expression) {
    if (expression.isBlank()) throw new IllegalArgumentException("Введите выражение");
    if (expression.length() > 512) throw new IllegalArgumentException("Слишком длинное выражение");
    Calculator parser = new Calculator(expression);
    BigDecimal result = parser.sum(0);
    parser.spaces();
    if (parser.position != expression.length())
      throw new IllegalArgumentException("Ожидается оператор");
    double number = result.doubleValue();
    if (!Double.isFinite(number) || number == 0 && result.signum() != 0)
      throw new IllegalArgumentException("Результат вне допустимого диапазона");
    return Numbers.plain(number);
  }

  private void spaces() {
    while (position < source.length() && Character.isWhitespace(source.charAt(position)))
      position++;
  }

  private boolean take(char c) {
    spaces();
    if (position < source.length() && source.charAt(position) == c) {
      position++;
      return true;
    }
    return false;
  }

  private BigDecimal sum(int depth) {
    BigDecimal value = product(depth);
    while (true) {
      if (take('+')) value = value.add(product(depth), PRECISION);
      else if (take('-')) value = value.subtract(product(depth), PRECISION);
      else return value;
    }
  }

  private BigDecimal product(int depth) {
    BigDecimal value = primary(depth);
    while (true) {
      if (take('*')) value = value.multiply(primary(depth), PRECISION);
      else if (take('/')) {
        BigDecimal divisor = primary(depth);
        if (divisor.signum() == 0) throw new IllegalArgumentException("Деление на ноль");
        value = value.divide(divisor, PRECISION);
      } else return value;
    }
  }

  private BigDecimal primary(int depth) {
    if (depth > 64) throw new IllegalArgumentException("Слишком много вложенных операций");
    if (take('+')) return primary(depth + 1);
    if (take('-')) return primary(depth + 1).negate();
    if (take('(')) {
      BigDecimal value = sum(depth + 1);
      if (!take(')')) throw new IllegalArgumentException("Закройте скобку");
      return value;
    }
    spaces();
    int begin = position;
    boolean decimal = false, digit = false;
    while (position < source.length()) {
      char c = source.charAt(position);
      if (c >= '0' && c <= '9') {
        digit = true;
        position++;
      } else if ((c == '.' || c == ',') && !decimal) {
        decimal = true;
        position++;
      } else break;
    }
    if (!digit) throw new IllegalArgumentException("Выражение не завершено");
    return new BigDecimal(source.substring(begin, position).replace(',', '.'), PRECISION);
  }
}

package ru.obsfit.ui;

import ru.obsfit.CalculatorForm;

/** Open a calculator for a form field or a pending ingredient weight. */
public interface CalculatorActions {
  void calculator(CalculatorForm.Target target);

  void ingredientCalculator(boolean catalog, int index, String name, String initial);
}

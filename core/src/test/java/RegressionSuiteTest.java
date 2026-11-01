import org.junit.Test;

/** Keep the established regression scenarios, now discoverable through Gradle/JUnit and CI. */
public final class RegressionSuiteTest {
  @Test
  public void markdown() throws Exception {
    String vault = System.getenv("OBSFIT_VAULT_PATH");
    CoreTest.main(vault == null ? new String[0] : new String[] {vault});
  }

  @Test
  public void search() {
    FoodSearchTest.main(new String[0]);
  }

  @Test
  public void catalog() {
    CatalogTest.main(new String[0]);
  }

  @Test
  public void ingredients() {
    RecipeIngredientsTest.main(new String[0]);
  }

  @Test
  public void nutrition() {
    OffNutritionTest.main(new String[0]);
  }

  @Test
  public void httpRecovery() throws Exception {
    ru.obsfit.HttpGetTest.main(new String[0]);
  }
}

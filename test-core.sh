#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p core-tests/classes
javac -encoding UTF-8 -d core-tests/classes app/src/main/java/ru/obsfit/VaultMarkdown.java app/src/main/java/ru/obsfit/FoodSearch.java app/src/main/java/ru/obsfit/RecipeIngredients.java app/src/main/java/ru/obsfit/OffNutrition.java app/src/main/java/ru/obsfit/HttpGet.java core-tests/CoreTest.java core-tests/FoodSearchTest.java core-tests/CatalogTest.java core-tests/RecipeIngredientsTest.java core-tests/OffNutritionTest.java core-tests/ru/obsfit/HttpGetTest.java
java -cp core-tests/classes CoreTest "$@"
java -cp core-tests/classes FoodSearchTest
java -cp core-tests/classes CatalogTest
java -cp core-tests/classes RecipeIngredientsTest
java -cp core-tests/classes OffNutritionTest
java -cp core-tests/classes ru.obsfit.HttpGetTest

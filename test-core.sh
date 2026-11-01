#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p core-tests/classes
javac -encoding UTF-8 -d core-tests/classes app/src/main/java/ru/obsfit/VaultMarkdown.java app/src/main/java/ru/obsfit/FoodSearch.java app/src/main/java/ru/obsfit/RecipeIngredients.java core-tests/CoreTest.java core-tests/FoodSearchTest.java core-tests/CatalogTest.java core-tests/RecipeIngredientsTest.java
java -cp core-tests/classes CoreTest "$@"
java -cp core-tests/classes FoodSearchTest
java -cp core-tests/classes CatalogTest
java -cp core-tests/classes RecipeIngredientsTest

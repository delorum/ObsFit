import ru.obsfit.OffNutrition;
import java.util.*;

public final class OffNutritionTest {
    static int checks;
    static void equal(Object e,Object a){checks++;if(!Objects.equals(e,a))throw new AssertionError("Expected "+e+", got "+a);}
    static void near(double e,Double a){checks++;if(a==null||Math.abs(e-a)>.0001)throw new AssertionError("Expected "+e+", got "+a);}
    public static void main(String[] args){
        // Real OFF response for the user's 4620002683160 (2026-11-01).
        near(339,OffNutrition.kcal100(Map.of("energy-kcal_100g",1419,"energy-kj_100g",1413.6,"energy_100g",1413.6)));
        near(339,OffNutrition.kcal100(Map.of("energy-kcal_100g",1419,"energy_100g",1413.6)));
        near(539,OffNutrition.kcal100(Map.of("energy-kcal_100g",539,"energy-kj_100g",2252,"energy_100g",2252)));
        near(339,OffNutrition.kcal100(Map.of("energy-kcal_100g",339,"energy-kj_100g",1419)));
        near(100,OffNutrition.kcal100(Map.of("energy-kj_100g",418.4)));
        near(100,OffNutrition.kcal100(Map.of("energy_100g",418.4,"energy_unit","kcal")));
        near(100,OffNutrition.kcal100(Map.of("energy-kj_100g",418.4,"energy-kj_unit","kcal")));
        near(0,OffNutrition.kcal100(Map.of("energy-kcal_100g",0,"energy-kj_100g",418.4)));
        near(0,OffNutrition.kcal100(Map.of("energy_100g",0)));
        near(12.5,OffNutrition.kcal100(Map.of("energy-kcal_100g","12,5")));
        near(100,OffNutrition.kcal100(Map.of("energy-kj_100g","418,4")));
        near(100,OffNutrition.kcal100(Map.of("energy-kcal_100g",-1,"energy-kj_100g",418.4)));
        near(100,OffNutrition.kcal100(Map.of("energy-kcal_100g","NaN","energy_100g",418.4)));
        near(100,OffNutrition.kcal100(Map.of("energy-kcal_100g","","energy-kj_100g",418.4)));
        near(100,OffNutrition.kcal100(Map.of("energy-kcal_100g",1419,"energy-kj_100g",418.4)));
        equal(null,OffNutrition.kcal100(Map.of("energy-kcal_100g",1419)));
        equal(null,OffNutrition.kcal100(Map.of("energy-kcal_100g",99999,"energy-kj_100g",99999)));
        equal(null,OffNutrition.kcal100(Map.of("energy-kcal_100g",Double.POSITIVE_INFINITY)));
        equal(null,OffNutrition.kcal100(Map.of("energy-kcal_serving",339,"energy-kcal_value",339,"energy_unit","kcal")));
        equal(null,OffNutrition.kcal100(Map.of("energy-kcal_100g",true)));
        equal(null,OffNutrition.kcal100(Map.of()));equal(null,OffNutrition.kcal100(null));
        System.out.println("ObsFit OFF nutrition: "+checks+" checks passed");
    }
}

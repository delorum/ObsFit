package ru.obsfit;

import java.util.Map;

/** OFF normalized *_100g fields have fixed units, independent of the contributor's *_unit. */
public final class OffNutrition {
    private OffNutrition() {}
    public static final double KJ_PER_KCAL=4.184;
    public static Double kcal100(Map<String,?> nutriments) {
        if(nutriments==null)return null;
        Double kcal=value(nutriments.get("energy-kcal_100g"));
        if(plausible(kcal))return kcal;
        // Some OFF records label a kJ amount as kcal as well (e.g. barcode 4620002683160).
        // Only repair it when kcal is physically implausible and an independent kJ field agrees.
        if(kcal!=null&&kcal>1000&&plausible(kcal/KJ_PER_KCAL)) {
            for(String field:new String[]{"energy-kj_100g","energy_100g"}) {
                Double kj=value(nutriments.get(field));
                if(kj!=null&&Math.abs(kj-kcal)<=kcal*.02)return (double)Math.round(kcal/KJ_PER_KCAL);
            }
        }
        for(String field:new String[]{"energy-kj_100g","energy_100g"}) {
            Double kj=value(nutriments.get(field));
            if(kj!=null&&plausible(kj/KJ_PER_KCAL))return kj/KJ_PER_KCAL;
        }
        return null;
    }
    private static boolean plausible(Double kcal) {return kcal!=null&&kcal>=0&&kcal<=1000;}
    private static Double value(Object raw) {
        if(raw==null||raw instanceof Boolean)return null;
        try {
            double value=raw instanceof Number?((Number)raw).doubleValue():Double.parseDouble(raw.toString().trim().replace(',','.'));
            return Double.isFinite(value)&&value>=0?value:null;
        }catch(NumberFormatException ex){return null;}
    }
}

package ru.obsfit;

import org.json.JSONObject;
import android.util.Log;
import java.net.*;
import java.io.*;
import java.util.*;

public final class ProductLookup {
    public record Product(String name, Double kcal100) {}
    public static Product find(String barcode) throws Exception {
        if(!barcode.matches("\\d{8,14}")) throw new IOException("Нужен товарный штрихкод из 8–14 цифр");
        URL url=new URL("https://world.openfoodfacts.org/api/v2/product/"+barcode+".json?fields=product_name_ru,product_name,nutriments");
        final String body;
        try {body=HttpGet.get(url,(attempt,error,elapsed)->Log.w("ObsFitLookup","Open Food Facts retry after attempt "+attempt+", elapsed="+elapsed+"ms, error="+error.getClass().getSimpleName()));}
        catch(HttpGet.Failure error) {
            if(error.status==404)throw new IOException("Товар не найден в Open Food Facts. Заполните данные вручную.",error);
            if(error.status==429)throw new IOException("Open Food Facts временно ограничил запросы. Попробуйте позже.",error);
            throw new IOException("Open Food Facts недоступен (HTTP "+error.status+"). Можно заполнить данные вручную.",error);
        }catch(SocketTimeoutException error){throw new IOException("Open Food Facts не ответил после повторной попытки. Проверьте соединение и попробуйте позже.",error);}
        catch(IOException error){throw new IOException("Не удалось получить данные Open Food Facts: "+error.getMessage(),error);}
        JSONObject response=new JSONObject(body),p=response.optJSONObject("product");
        if(response.optInt("status")!=1||p==null)throw new IOException("Товар не найден в Open Food Facts. Заполните данные вручную.");
        String name=p.optString("product_name_ru","");if(name.isBlank())name=p.optString("product_name","");
        JSONObject nutriments=p.optJSONObject("nutriments");Map<String,Object> energy=new HashMap<>();
        if(nutriments!=null)for(String field:new String[]{"energy-kcal_100g","energy-kj_100g","energy_100g"})energy.put(field,nutriments.opt(field));
        return new Product(name,OffNutrition.kcal100(energy));
    }
}

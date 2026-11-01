package ru.obsfit;

import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class ProductLookup {
    public record Product(String name, Double kcal100) {}
    public static Product find(String barcode) throws Exception {
        if(!barcode.matches("\\d{8,14}")) throw new IOException("Нужен товарный штрихкод из 8–14 цифр");
        URL url=new URL("https://world.openfoodfacts.org/api/v2/product/"+barcode+".json?fields=product_name_ru,product_name,nutriments");
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        connection.setConnectTimeout(12000);connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent","ObsFit/0.1.7 (Android; personal Obsidian nutrition tracker)");
        connection.setRequestProperty("Accept","application/json");
        try {
            int status=connection.getResponseCode();
            if(status==404) throw new IOException("Товар не найден в Open Food Facts. Заполните данные вручную.");
            if(status!=200) throw new IOException("Open Food Facts недоступен (HTTP "+status+"). Можно заполнить данные вручную.");
            JSONObject response;
            try(InputStream in=connection.getInputStream()) {
                ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1) { if(data.size()+n>1024*1024) throw new IOException("Слишком большой ответ сервера");data.write(buffer,0,n); }
                response=new JSONObject(data.toString(StandardCharsets.UTF_8.name()));
            }
            JSONObject p=response.optJSONObject("product");
            if(response.optInt("status")!=1||p==null) throw new IOException("Товар не найден в Open Food Facts. Заполните данные вручную.");
            String name=p.optString("product_name_ru","");if(name.isBlank()) name=p.optString("product_name","");
            JSONObject nutriments=p.optJSONObject("nutriments");Double kcal=null;
            if(nutriments!=null) {
                double value=nutriments.optDouble("energy-kcal_100g",Double.NaN);
                if(!Double.isFinite(value)) { double kj=nutriments.optDouble("energy-kj_100g",Double.NaN);if(Double.isFinite(kj)) value=kj/4.184; }
                if(Double.isFinite(value)&&value>=0) kcal=value;
            }
            return new Product(name,kcal);
        } finally { connection.disconnect(); }
    }
}

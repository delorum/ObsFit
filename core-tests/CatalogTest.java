import ru.obsfit.*;
import java.util.*;

public final class CatalogTest {
    static int checks;
    static void equal(Object expected,Object actual){checks++;if(!Objects.equals(expected,actual))throw new AssertionError("Expected "+expected+", got "+actual);}
    static void near(double expected,double actual){checks++;if(Math.abs(expected-actual)>.01)throw new AssertionError("Expected "+expected+", got "+actual);}
    static void rejects(Runnable action,String fragment){checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException ex){if(ex.getMessage().contains(fragment))return;throw new AssertionError(ex);}throw new AssertionError("Expected rejection: "+fragment);}
    static VaultMarkdown.Food food(String name,List<String> aliases,String ingredients,double kcal,double grams){return new VaultMarkdown.Food(name,aliases,ingredients,kcal,grams);}
    public static void main(String[] args){
        String source="# Еда\nИнструкция\n<!-- food-table:start -->\n\n| Название | Ингредиенты | Ккал | Вес |\n| --- | --- | --- | --- |\n| хлеб<br>хл | | 200 | 40 |\n| сыр<br>ср | | 300 | 20 |\n| бутерброд<br>ббр | хл 40 г<br>сыр 20 г | 233.33 | 60 |\n\n<!-- food-table:end -->\nТекст после таблицы\n";
        String replacements="---\n### другое\nчужой\n";
        var catalog=VaultMarkdown.catalog(source);
        String changed=VaultMarkdown.putFood(catalog,"хлеб",food("хлеб",List.of("хл"),"",250,50),replacements);
        var parsed=VaultMarkdown.catalog(changed);
        near(250,parsed.get("хл").kcal100);near(50,parsed.get("хл").weight);near(266.6667,parsed.get("ббр").kcal100);near(60,parsed.get("ббр").weight);
        equal(true,changed.startsWith("# Еда\nИнструкция\n<!-- food-table:start -->"));equal(true,changed.endsWith("<!-- food-table:end -->\nТекст после таблицы\n"));
        String renamed=VaultMarkdown.putFood(catalog,"хлеб",food("хлеб белый",List.of("хб"),"",200,40),replacements);
        var rename=VaultMarkdown.catalog(renamed);
        equal("хлеб белый",rename.get("хлеб").name);equal(null,rename.get("хл"));
        equal("хлеб белый 40 г<br>сыр 20 г",rename.get("ббр").ingredients);near(233.3333,rename.get("ббр").kcal100);
        var diary=VaultMarkdown.day("хлеб 40 г 80 ккал\nСъеденоКалорий:: 80",rename);near(80,diary.total);equal("хлеб белый",diary.entries.get(0).name);
        rejects(()->VaultMarkdown.deleteFood(catalog,"хлеб"),"бутерброд");rejects(()->VaultMarkdown.deleteFood(catalog,"сыр"),"бутерброд");
        String noRecipe=VaultMarkdown.deleteFood(catalog,"бутерброд");equal(2,VaultMarkdown.catalog(noRecipe).foods.size());
        String deleted=VaultMarkdown.deleteFood(VaultMarkdown.catalog(noRecipe),"хлеб");equal(1,VaultMarkdown.catalog(deleted).foods.size());
        near(80,VaultMarkdown.day("хлеб 40 г 80 ккал",VaultMarkdown.catalog(deleted)).total);
        String empty=VaultMarkdown.deleteFood(VaultMarkdown.catalog(deleted),"сыр");equal(0,VaultMarkdown.catalog(empty).foods.size());
        String recreated=VaultMarkdown.putFood(VaultMarkdown.catalog(empty),null,food("вода",List.of("вд"),"",0,200),replacements);equal(1,VaultMarkdown.catalog(recreated).foods.size());
        String recipe=VaultMarkdown.putFood(catalog,"бутерброд",food("бутерброд",List.of("ббр"),"хлеб 80 г<br>сыр 20 г",0,0),replacements);
        near(100,VaultMarkdown.catalog(recipe).get("ббр").weight);near(220,VaultMarkdown.catalog(recipe).get("ббр").kcal100);
        String created=VaultMarkdown.putFood(catalog,null,food("сырный бутер",List.of("сб"),"сыр 30 г<br>хлеб 20 г",0,0),replacements);
        near(50,VaultMarkdown.catalog(created).get("сб").weight);near(260,VaultMarkdown.catalog(created).get("сб").kcal100);
        rejects(()->VaultMarkdown.putFood(catalog,"хлеб",food("сыр",List.of(),"",100,40),replacements),"уже есть");
        rejects(()->VaultMarkdown.putFood(catalog,"хлеб",food("хлеб",List.of("ср"),"",100,40),replacements),"уже есть");
        rejects(()->VaultMarkdown.putFood(catalog,"хлеб",food("хлеб",List.of("чужой"),"",100,40),replacements),"Замены.md");
        rejects(()->VaultMarkdown.putFood(catalog,"хлеб",food("хлеб",List.of("ХЛЕБ"),"",100,40),replacements),"уже есть");
        rejects(()->VaultMarkdown.putFood(catalog,"хлеб",food("хлеб",List.of(),"",100,0),replacements),"вес");
        rejects(()->VaultMarkdown.putFood(catalog,"бутерброд",food("бутерброд",List.of(),"неизвестно 40 г",0,0),replacements),"простой едой");
        rejects(()->VaultMarkdown.putFood(catalog,"хлеб",food("хлеб",List.of(),"сыр 30 г",0,0),replacements),"простой едой");
        rejects(()->VaultMarkdown.deleteFood(catalog,"нет"),"изменился");rejects(()->VaultMarkdown.putFood(catalog,"нет",food("новое",List.of(),"",100,40),replacements),"изменился");
        String crlf=source.replace("\n","\r\n");String output=VaultMarkdown.putFood(VaultMarkdown.catalog(crlf),"сыр",food("сыр",List.of("ср"),"",350,20),replacements);
        equal(true,output.contains("\r\nТекст после таблицы\r\n"));equal(false,output.replace("\r\n","").contains("\n"));
        equal(changed,VaultMarkdown.putFood(parsed,"хлеб",food("хлеб",List.of("хл"),"",250,50),replacements));
        System.out.println("ObsFit catalog: "+checks+" checks passed");
    }
}

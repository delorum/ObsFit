import ru.obsfit.*;
import java.util.*;

public final class FoodSearchTest {
    static int checks;
    static VaultMarkdown.Food food(String name,String...aliases){return new VaultMarkdown.Food(name,List.of(aliases),"",100,100);}
    static void equal(Object expected,Object actual){checks++;if(!Objects.equals(expected,actual))throw new AssertionError("Expected "+expected+", got "+actual);}
    static List<String> names(List<VaultMarkdown.Food> foods,String query){return FoodSearch.find(foods,query,f->0).stream().map(f->f.name).toList();}
    static void first(List<VaultMarkdown.Food> foods,String query,String name){List<String> found=names(foods,query);equal(name,found.isEmpty()?null:found.get(0));}
    public static void main(String[] args){
        var foods=List.of(food("белый хлеб","бт"),food("колбаса","клб"),food("сыр Паулина"),food("овсяный блин"),food("капучино","кофе с молоком","кап"),food("мёд"),food("питьевой йогурт"),food("сок"));
        first(foods,"белый хлеб","белый хлеб");first(foods,"БТ","белый хлеб");
        first(foods,"колбса","колбаса");first(foods,"колббаса","колбаса");first(foods,"колбпса","колбаса");
        first(foods,"паулниа","сыр Паулина");first(foods,"капучинно","капучино");
        first(foods,"капп","капучино");first(foods,"кофе молоком","капучино");
        first(foods,"овс бл","овсяный блин");first(foods,"блин овс","овсяный блин");
        first(foods,"хлеб бел","белый хлеб");first(foods,"бл хл","белый хлеб");first(foods,"овсбл","овсяный блин");
        first(foods,"мед","мёд");first(foods,"МЁД","мёд");first(foods,"  ПИТЬЕВОЙ-йогурт  ","питьевой йогурт");
        equal(List.of(),names(foods,"штангенциркуль"));equal(List.of(),names(foods,"бж"));equal(List.of(),names(foods,"хлеб хлеб"));
        equal(foods.size(),FoodSearch.find(foods,"",f->0).size());equal(foods.size(),FoodSearch.find(foods,"   ",f->0).size());
        var ranking=List.of(food("сыр"),food("сырный соус"),food("суп сыр"),food("сир"));
        var ranked=FoodSearch.find(ranking,"сыр",f->f.name.equals("сир")?999:0);
        equal("сыр",ranked.get(0).name);equal("сырный соус",ranked.get(1).name);equal("суп сыр",ranked.get(2).name);equal("сир",ranked.get(3).name);
        equal("сир",FoodSearch.find(ranking,"",f->f.name.equals("сир")?999:0).get(0).name);
        equal("сыр б",FoodSearch.find(List.of(food("сыр а"),food("сыр б")),"сыр",f->f.name.equals("сыр б")?999:0).get(0).name);
        var assign=List.of(food("молочный молоко"));first(assign,"моло молоч","молочный молоко");
        equal(List.of(),names(foods,"а".repeat(1000)));equal(foods.size(),names(foods,"---").size());
        equal("белый хлеб",foods.get(0).name);equal(List.of("бт"),foods.get(0).aliases);
        List<VaultMarkdown.Food> big=new ArrayList<>();for(int i=0;i<2000;i++)big.add(food("продукт "+i,"еда"+i));
        first(big,"продукт 1999","продукт 1999");
        System.out.println("ObsFit fuzzy search: "+checks+" checks passed");
    }
}

package ru.obsfit;

import java.text.Normalizer;
import java.util.*;
import java.util.function.ToLongFunction;

/** Local fuzzy search. Exact matches first, then fragments, word matches and typos. */
public final class FoodSearch {
    private FoodSearch() {}
    private static final int NONE=Integer.MAX_VALUE;
    private record Match(VaultMarkdown.Food food,int score,long recent) {}
    static String normalize(String text) {
        return Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.forLanguageTag("ru"))
            .replace('ё','е').replaceAll("[^\\p{L}\\p{N}]+"," ").trim();
    }
    public static List<VaultMarkdown.Food> find(List<VaultMarkdown.Food> foods,String query,ToLongFunction<VaultMarkdown.Food> recent) {
        String needle=normalize(query);List<Match> matches=new ArrayList<>();
        for(VaultMarkdown.Food food:foods) {
            int best=NONE;
            for(String name:food.names()) best=Math.min(best,score(needle,normalize(name)));
            if(best!=NONE) matches.add(new Match(food,best,recent.applyAsLong(food)));
        }
        matches.sort(Comparator.comparingInt(Match::score).thenComparing(Comparator.comparingLong(Match::recent).reversed())
            .thenComparing(m->VaultMarkdown.key(m.food.name)));
        List<VaultMarkdown.Food> result=new ArrayList<>();for(Match match:matches)result.add(match.food);
        return result;
    }
    private static int score(String query,String name) {
        if(query.isEmpty()||query.equals(name))return 0;
        if(name.startsWith(query))return 10;
        if(name.contains(query))return 20;
        // Bound approximate work; long literal matches still work above.
        if(query.length()>128||name.length()>256)return NONE;
        String[] words=name.split(" +"), tokens=query.split(" +");
        if(tokens.length>1) {
            // Assign distinct words to query tokens, irrespective of their order.
            if(tokens.length>words.length)return NONE;
            int cost=assign(tokens,words);
            if(cost!=NONE)return 30+cost;
        } else {
            int best=NONE;
            for(String word:words)best=Math.min(best,wordScore(query,word));
            if(best!=NONE)return 30+best;
        }
        String compact=name.replace(" ",""), needle=query.replace(" ","");
        if(needle.length()>=3&&compact.length()<=needle.length()*3&&subsequence(needle,compact)) return 70+compact.length()-needle.length();
        return NONE;
    }
    private static int wordScore(String query,String word) {
        if(query.equals(word))return 0;
        if(word.startsWith(query))return 2;
        if(word.contains(query))return 4;
        if(query.length()>=3) {
            int allowed=query.length()>=10?3:query.length()>=6?2:1;
            if(Math.abs(query.length()-word.length())<=allowed) {
                int edits=distance(query,word);
                if(edits<=allowed)return 10+edits*3;
            }
        }
        if(query.length()>=2&&word.length()<=query.length()*3&&subsequence(query,word))return 22+word.length()-query.length();
        return NONE;
    }
    private static boolean subsequence(String query,String text) {
        int i=0;for(int j=0;j<text.length()&&i<query.length();j++)if(query.charAt(i)==text.charAt(j))i++;
        return i==query.length();
    }
    /** Minimum-cost distinct-word assignment using the rectangular Hungarian algorithm. */
    private static int assign(String[] tokens,String[] words) {
        int n=tokens.length,m=words.length,blocked=10000;
        int[][] costs=new int[n][m];
        for(int i=0;i<n;i++)for(int j=0;j<m;j++){int s=wordScore(tokens[i],words[j]);costs[i][j]=s==NONE?blocked:s;}
        int[] u=new int[n+1],v=new int[m+1],p=new int[m+1],way=new int[m+1];
        for(int i=1;i<=n;i++) {
            p[0]=i;int j0=0;int[] min=new int[m+1];Arrays.fill(min,blocked*10);boolean[] used=new boolean[m+1];
            do {
                used[j0]=true;int i0=p[j0],delta=blocked*10,j1=0;
                for(int j=1;j<=m;j++)if(!used[j]) {
                    int cur=costs[i0-1][j-1]-u[i0]-v[j];if(cur<min[j]){min[j]=cur;way[j]=j0;}
                    if(min[j]<delta){delta=min[j];j1=j;}
                }
                for(int j=0;j<=m;j++)if(used[j]){u[p[j]]+=delta;v[j]-=delta;}else min[j]-=delta;
                j0=j1;
            }while(p[j0]!=0);
            do {int j1=way[j0];p[j0]=p[j1];j0=j1;}while(j0!=0);
        }
        int total=0;for(int j=1;j<=m;j++)if(p[j]!=0){int cost=costs[p[j]-1][j-1];if(cost==blocked)return NONE;total+=cost;}
        return total;
    }
    /** Optimal-string-alignment distance: insertions, omissions, substitutions and adjacent swaps. */
    private static int distance(String a,String b) {
        int[][] d=new int[a.length()+1][b.length()+1];
        for(int i=0;i<=a.length();i++)d[i][0]=i;
        for(int j=0;j<=b.length();j++)d[0][j]=j;
        for(int i=1;i<=a.length();i++)for(int j=1;j<=b.length();j++) {
            d[i][j]=Math.min(Math.min(d[i-1][j]+1,d[i][j-1]+1),d[i-1][j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
            if(i>1&&j>1&&a.charAt(i-1)==b.charAt(j-2)&&a.charAt(i-2)==b.charAt(j-1))d[i][j]=Math.min(d[i][j],d[i-2][j-2]+1);
        }
        return d[a.length()][b.length()];
    }
}

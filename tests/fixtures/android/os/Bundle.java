package android.os;
import java.util.HashMap;
public class Bundle extends HashMap<String,Object> {
 public boolean getBoolean(String k){return getBoolean(k,false);} public boolean getBoolean(String k, boolean d){return containsKey(k)?(Boolean)get(k):d;}
 public int getInt(String k,int d){return containsKey(k)?(Integer)get(k):d;}
}

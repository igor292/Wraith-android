package app.wraith.client;

import org.json.*;
import java.util.*;

public final class Json {
    private Json() {}
    public static JSONObject obj(Object... pairs) throws JSONException {
        JSONObject o = new JSONObject();
        for (int i=0;i<pairs.length;i+=2) o.put((String)pairs[i], pairs[i+1]);
        return o;
    }
    public static JSONArray arr(Object... values) {
        JSONArray a = new JSONArray(); for(Object v:values) a.put(v); return a;
    }
    public static JSONObject copy(JSONObject o) throws JSONException { return new JSONObject(o.toString()); }
    public static List<JSONObject> objects(JSONArray a) {
        List<JSONObject> out=new ArrayList<>();
        if(a!=null) for(int i=0;i<a.length();i++) { JSONObject o=a.optJSONObject(i); if(o!=null) out.add(o); }
        return out;
    }
}

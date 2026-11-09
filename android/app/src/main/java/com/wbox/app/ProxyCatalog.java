package com.wbox.app;

import org.json.*;
import java.util.*;

final class ProxyCatalog {
    static List<String> order(JSONObject root,boolean sb) {
        List<String> result=new ArrayList<>();JSONArray list=root.optJSONArray(sb?"outbounds":"proxy-groups");
        if(list!=null)for(int i=0;i<list.length();i++){
            JSONObject g=list.optJSONObject(i);if(g==null)continue;
            if(sb&&!List.of("selector","urltest").contains(g.optString("type")))continue;
            String name=g.optString(sb?"tag":"name");if(!name.isEmpty()&&!name.equals("GLOBAL"))result.add(name);
        }
        return result;
    }
    static List<String> ordered(JSONObject snapshot,List<String> configured) {
        LinkedHashSet<String> names=new LinkedHashSet<>(configured);
        snapshot.keys().forEachRemaining(names::add);
        names.removeIf(n->n.equals("GLOBAL")||snapshot.optJSONObject(n)==null||snapshot.optJSONObject(n).optJSONArray("all")==null);
        return new ArrayList<>(names);
    }
    static JSONObject source(Profiles p)throws Exception {
        if(p.mode().equals("nodes"))return new JSONObject();
        String text=p.data.optString("config_"+p.engine());
        return text.isBlank()?new JSONObject():new JSONObject(Profiles.yamlMap(text));
    }
    static JSONObject offline(Profiles p)throws Exception {
        JSONObject result=new JSONObject();
        if(p.mode().equals("nodes")){
            JSONArray all=new JSONArray();for(Node node:p.nodes()){all.put(node.name);result.put(node.name,new JSONObject().put("type",node.protocol));}
            if(all.length()>0)result.put("节点",new JSONObject().put("type","Selector").put("all",all).put("now",p.data.optString("selected",all.optString(0))));
            return result;
        }
        boolean sb=p.engine().equals("sing-box");JSONObject root=source(p);
        for(String field:sb?List.of("outbounds","endpoints"):List.of("proxies","proxy-groups")){
            JSONArray entries=root.optJSONArray(field);if(entries==null)continue;
            for(int i=0;i<entries.length();i++){
                JSONObject value=entries.getJSONObject(i);String name=value.optString(sb?"tag":"name");if(name.isEmpty())continue;
                String type=value.optString("type");type=switch(type){case "select","selector"->"Selector";case "url-test","urltest"->"URLTest";case "fallback"->"Fallback";case "load-balance"->"LoadBalance";default->type;};
                JSONObject entry=new JSONObject().put("type",type);
                JSONArray members=value.optJSONArray(sb?"outbounds":"proxies");
                if(members!=null || (!sb&&field.equals("proxy-groups"))){if(members==null)members=new JSONArray();entry.put("all",members).put("now",value.optString("default",members.optString(0)));}
                result.put(name,entry);
            }
        }
        applySelections(p,result);
        return result;
    }
    static JSONObject selections(Profiles p) {
        JSONObject profile=p.activeConfig();
        JSONObject saved=profile==null?null:profile.optJSONObject("group_selections");
        return saved==null?new JSONObject():saved;
    }
    static void applySelections(Profiles p,JSONObject groups)throws JSONException {
        JSONObject saved=selections(p);
        for(Iterator<String> it=saved.keys();it.hasNext();) {
            String name=it.next(),node=saved.optString(name);JSONObject group=groups.optJSONObject(name);
            if(group!=null&&group.optString("type").equals("Selector")&&contains(group.optJSONArray("all"),node))group.put("now",node);
        }
    }
    static boolean contains(JSONArray all,String node) {
        if(all!=null)for(int i=0;i<all.length();i++)if(node.equals(all.optString(i)))return true;
        return false;
    }
    static void restore(Profiles p,CoreApi api)throws Exception {
        if(!p.mode().equals("config")||selections(p).length()==0)return;
        JSONObject groups=api.proxies(),saved=selections(p);
        for(Iterator<String> it=saved.keys();it.hasNext();) {
            String name=it.next(),node=saved.optString(name);JSONObject group=groups.optJSONObject(name);
            if(group!=null&&group.optString("type").equals("Selector")&&contains(group.optJSONArray("all"),node))api.select(name,node);
        }
    }
    static String resolve(JSONObject groups,String name) {
        Set<String> seen=new HashSet<>();
        while(seen.add(name)){JSONObject group=groups.optJSONObject(name);String selected=group==null?"":group.optString("now");if(selected.isEmpty())return name;name=selected;}
        throw new IllegalArgumentException("代理组存在循环选择");
    }
}

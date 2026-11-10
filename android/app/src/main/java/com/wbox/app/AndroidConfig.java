package com.wbox.app;

import org.json.*;
import java.util.*;

/** Android owns routing via VpnService, not the unprivileged command-line core.
 * See upstream route/network.go and SFA bg/VPNService.kt. */
final class AndroidConfig {
    static void adaptMihomo(Map<String,Object> config) {
        config.remove("interface-name");config.remove("routing-mark");
        Object proxies=config.get("proxies");
        if(proxies instanceof List<?>)for(Object proxy:(List<?>)proxies)if(proxy instanceof Map<?,?>){((Map<?,?>)proxy).remove("interface-name");((Map<?,?>)proxy).remove("routing-mark");}
    }
    static void adaptSingBox(JSONObject config, JSONArray originalInbounds) throws JSONException {
        JSONObject route=config.optJSONObject("route");
        if(route==null){route=new JSONObject();config.put("route",route);}
        // A CLI network monitor needs forbidden netlink sockets on Android.
        // The VPN excludes our UID, so sockets already use Android's physical
        // default network without bind-to-device, routing marks or root.
        route.put("auto_detect_interface",false);
        Set<String> tunTags=new HashSet<>();
        if(originalInbounds!=null)for(int i=0;i<originalInbounds.length();i++){
            JSONObject inbound=originalInbounds.optJSONObject(i);
            if(inbound!=null&&inbound.optString("type").equals("tun")&&!inbound.optString("tag").isEmpty())tunTags.add(inbound.optString("tag"));
        }
        remapInboundRules(route.optJSONArray("rules"),tunTags);
        JSONObject originalDns=config.optJSONObject("dns");
        if(originalDns!=null)remapInboundRules(originalDns.optJSONArray("rules"),tunTags);
        for(String key:List.of("default_interface","default_mark","default_network_strategy","default_network_type","default_fallback_network_type","default_fallback_delay","override_android_vpn"))route.remove(key);
        stripSocketBinding(config.optJSONArray("outbounds"));
        stripSocketBinding(config.optJSONArray("endpoints"));
        JSONObject dns=config.optJSONObject("dns");
        if(dns!=null)stripSocketBinding(dns.optJSONArray("servers"));
    }
    private static void remapInboundRules(JSONArray rules, Set<String> tunTags)throws JSONException {
        if(rules==null||tunTags.isEmpty())return;
        for(int i=0;i<rules.length();i++){
            JSONObject rule=rules.optJSONObject(i);if(rule==null)continue;
            Object match=rule.opt("inbound");
            if(match instanceof String&&tunTags.contains(match))rule.put("inbound","wbox-in");
            if(match instanceof JSONArray){JSONArray list=(JSONArray)match;for(int j=0;j<list.length();j++)if(tunTags.contains(list.optString(j)))list.put(j,"wbox-in");}
            remapInboundRules(rule.optJSONArray("rules"),tunTags);
        }
    }
    private static void stripSocketBinding(JSONArray objects){
        if(objects==null)return;
        for(int i=0;i<objects.length();i++){
            JSONObject object=objects.optJSONObject(i);if(object==null)continue;
            for(String key:List.of("bind_interface","routing_mark","network_strategy","network_type","fallback_network_type","fallback_delay"))object.remove(key);
        }
    }
}

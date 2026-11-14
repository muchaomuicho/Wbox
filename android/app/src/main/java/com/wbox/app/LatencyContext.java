package com.wbox.app;

import org.json.*;

/** Probe identity follows its source, independent of the current selection or VPN. */
final class LatencyContext {
    static String key(Profiles source)throws Exception {
        JSONObject value=new JSONObject().put("mode",source.mode()).put("engine",source.engine())
            .put("url",source.data.optString("test_url",Profiles.TEST_URL));
        if(source.mode().equals("config")) {
            JSONObject profile=source.activeConfig();
            value.put("id",profile==null?"":profile.optString("id"))
                .put("content",profile==null?source.data.optString("config_"+source.engine()):profile.optString("content"))
                .put("providers",source.probeResourceIdentity==null?LatencyResources.identity(source):source.probeResourceIdentity);
        } else value.put("nodes",source.data.optJSONArray("nodes"));
        return value.toString();
    }
    static boolean valid(Profiles current,Profiles source)throws Exception {
        if(!current.data.optString("test_url",Profiles.TEST_URL).equals(source.data.optString("test_url",Profiles.TEST_URL)))return false;
        if(source.mode().equals("config")) {
            JSONObject original=source.activeConfig();
            if(original==null)return false;
            for(int i=0;i<current.configs().length();i++) {
                JSONObject profile=current.configs().getJSONObject(i);
                if(profile.optString("id").equals(original.optString("id")))
                    return profile.optString("core").equals(original.optString("core"))&&profile.optString("content").equals(original.optString("content"))&&
                        (source.probeResourceIdentity==null||source.probeResourceIdentity.equals(LatencyResources.identity(source)));
            }
            return false;
        }
        String engine=current.mode().equals("nodes")?current.engine():current.data.optString("node_engine",current.engine());
        return engine.equals(source.engine())&&String.valueOf(current.data.optJSONArray("nodes")).equals(String.valueOf(source.data.optJSONArray("nodes")));
    }
}

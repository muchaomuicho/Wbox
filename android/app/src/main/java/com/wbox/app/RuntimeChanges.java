package com.wbox.app;

import org.json.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Serial runtime transaction shared by configuration, node and VPN settings changes. */
final class RuntimeChanges {
    static String identity(Profiles p)throws Exception {
        JSONObject value=new JSONObject().put("mode",p.mode()).put("engine",p.engine());
        if(p.mode().equals("config")) {
            JSONObject config=p.activeConfig();
            if(config!=null)value.put("id",config.optString("id")).put("content",config.optString("content")).put("selections",config.optJSONObject("group_selections"));
        } else if(!p.nodes().isEmpty()) {
            JSONArray nodes=new JSONArray();
            for(var entry:ActiveNodeConfig.resolve(p.nodes(),p.data.optString("selected"),p.engine()).entrySet())nodes.put(entry.getValue());
            value.put("nodes",nodes);
        }
        String mode=p.data.optString("app_mode","all");value.put("app_mode",mode);
        if(!mode.equals("all")) {
            List<String> apps=new ArrayList<>();JSONArray array=p.data.optJSONArray("apps");
            if(array!=null)for(int i=0;i<array.length();i++)apps.add(array.getString(i));
            Collections.sort(apps);value.put("apps",new JSONArray(apps));
        }
        return value.toString();
    }
    static boolean hasTarget(Profiles p)throws Exception {return p.mode().equals("nodes")?!p.nodes().isEmpty():p.activeConfig()!=null;}
    static void commit(Profiles profiles,JSONObject next,NodeChanges.Connection connection)throws Exception {
        JSONObject previous=new JSONObject(profiles.data.toString());
        Profiles staged=profiles.copy();staged.data=next;
        boolean restart=connection.running()&&!identity(staged).equals(identity(profiles));
        if(restart&&hasTarget(staged))staged.compile("validation");
        if(restart)connection.stop();
        try {
            profiles.data=next;profiles.save();
            if(restart&&hasTarget(profiles)){
                if(connection.cancelled())throw new CancellationException("配置应用已取消");
                connection.start();
                if(connection.cancelled())throw new CancellationException("配置应用已取消");
            }
        }catch(Exception error) {
            if(restart)connection.stop();
            profiles.data=previous;profiles.save();
            if(restart&&!connection.cancelled())try{connection.start();}catch(Exception restore){throw new IllegalStateException("修改未保存，恢复连接失败："+restore.getMessage(),error);}
            throw error;
        }
    }
}

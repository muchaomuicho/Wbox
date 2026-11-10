package com.wbox.app;

import org.json.JSONObject;

/** Applies a node library change and restores its saved version if activation fails. */
final class NodeChanges {
    interface Connection {
        boolean running();
        boolean cancelled();
        void stop()throws Exception;
        void start()throws Exception;
    }
    static void commit(Profiles profiles,JSONObject next,Connection connection)throws Exception {
        JSONObject previous=profiles.data;
        boolean restart=connection.running()&&profiles.mode().equals("nodes");
        Profiles staged=profiles.copy();staged.data=next;
        if(restart&&!staged.nodes().isEmpty())restart=!staged.compile("validation").equals(profiles.compile("validation"));
        if(restart)connection.stop();
        try {
            profiles.data=next;profiles.save();
            if(restart&&!staged.nodes().isEmpty()&&!connection.cancelled())connection.start();
        }catch(Exception error){
            if(restart)connection.stop();
            profiles.data=previous;profiles.save();
            if(restart&&!connection.cancelled())try{connection.start();}catch(Exception restore){throw new IllegalStateException("修改未保存，恢复连接失败："+restore.getMessage(),error);}
            throw error;
        }
    }
}

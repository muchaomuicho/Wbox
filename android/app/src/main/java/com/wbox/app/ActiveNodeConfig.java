package com.wbox.app;

import org.json.JSONObject;
import java.util.*;

/** Resolves only the selected node's chain, inside its own subscription. */
final class ActiveNodeConfig {
    static LinkedHashMap<Node,JSONObject> resolve(List<Node> nodes,String selected,String engine)throws Exception {
        Node current=nodes.stream().filter(n->n.name.equals(selected)).findFirst().orElse(nodes.get(0));
        LinkedHashMap<Node,JSONObject> built=new LinkedHashMap<>();
        add(current,nodes,engine,built,new HashSet<>());
        LinkedHashMap<Node,JSONObject> ordered=new LinkedHashMap<>();
        ordered.put(current,built.get(current));ordered.putAll(built);return ordered;
    }
    private static void add(Node node,List<Node> nodes,String engine,Map<Node,JSONObject> built,Set<Node> visiting)throws Exception {
        if(visiting.contains(node))throw new IllegalArgumentException("当前节点的前置代理存在循环依赖。");
        if(built.containsKey(node))return;
        if(!node.compatible(engine))throw new IllegalArgumentException("节点「"+node.name+"」不支持此内核，请切换内核。");
        boolean sb=engine.equals("sing-box");
        JSONObject value=sb?node.singbox():new JSONObject(node.mihomo());
        String key=sb?"detour":"dialer-proxy",dependency=value.optString(key);
        visiting.add(node);
        if(!dependency.isEmpty()) {
            List<Node> matches=new ArrayList<>();
            for(Node candidate:nodes){JSONObject nativeNode=sb?candidate.nativeSingbox:candidate.nativeMihomo;
                if(candidate.source.equals(node.source)&&(candidate.name.equals(dependency)||(nativeNode!=null&&nativeNode.optString(sb?"tag":"name").equals(dependency))))matches.add(candidate);
            }
            if(matches.size()!=1)throw new IllegalArgumentException("节点「"+node.name+"」的前置代理「"+dependency+"」"+(matches.isEmpty()?"不存在":"名称重复")+"。请编辑该节点，或重新导入完整节点订阅。");
            Node target=matches.get(0);add(target,nodes,engine,built,visiting);value.put(key,target.name);
        }
        visiting.remove(node);built.put(node,value);
    }
}

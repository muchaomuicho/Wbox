package com.wbox.app;

import android.util.JsonReader;
import java.util.*;

final class ConnectionRecord {
    String id="",network="",source="",destination="",inbound="",outbound="",rule="";
    long upload,download,upRate,downRate;
    boolean matches(String query){return (network+" "+source+" "+destination+" "+inbound+" "+outbound+" "+rule).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));}
    static ConnectionRecord read(JsonReader reader)throws Exception {
        ConnectionRecord c=new ConnectionRecord();String host="",ip="",port="",sourceIp="",sourcePort="",type="";
        reader.beginObject();while(reader.hasNext()){
            String key=reader.nextName();
            switch(key){
                case "id":c.id=reader.nextString();break;
                case "upload":c.upload=reader.nextLong();break;
                case "download":c.download=reader.nextLong();break;
                case "rule":if(reader.peek()==android.util.JsonToken.NULL)reader.nextNull();else c.rule=reader.nextString();break;
                case "chains":if(reader.peek()==android.util.JsonToken.NULL){reader.nextNull();break;}List<String> chain=new ArrayList<>();reader.beginArray();while(reader.hasNext())chain.add(reader.nextString());reader.endArray();c.outbound=String.join(" → ",chain);break;
                case "metadata":
                    if(reader.peek()==android.util.JsonToken.NULL){reader.nextNull();break;}
                    reader.beginObject();while(reader.hasNext()){
                        String name=reader.nextName();if(reader.peek()==android.util.JsonToken.NULL){reader.nextNull();continue;}
                        switch(name){case "network":c.network=reader.nextString().toUpperCase(Locale.ROOT);break;case "host":host=reader.nextString();break;case "destinationIP":ip=reader.nextString();break;case "destinationPort":port=reader.nextString();break;case "sourceIP":sourceIp=reader.nextString();break;case "sourcePort":sourcePort=reader.nextString();break;case "inboundName":c.inbound=reader.nextString();break;case "type":type=reader.nextString();break;default:reader.skipValue();}
                    }reader.endObject();break;
                default:reader.skipValue();
            }
        }reader.endObject();c.destination=(host.isEmpty()?ip:host)+(port.isEmpty()?"":":"+port);c.source=sourceIp+":"+sourcePort;if(c.inbound.isEmpty())c.inbound=type;return c;
    }
}

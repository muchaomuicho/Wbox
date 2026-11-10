package com.wbox.app;

import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

/** Seeds core-owned default data before startup; never downloads via the VPN. */
final class Geodata {
    interface Source { InputStream open(String name)throws IOException; }
    static Source assets(android.content.Context context) {
        return name->name.equals("manifest.json")?context.getAssets().open(name):decode(context.getAssets().open(name+".xz"));
    }
    static InputStream decode(InputStream compressed)throws IOException {
        try {return new org.tukaani.xz.XZInputStream(compressed,8192);}
        catch(IOException|RuntimeException error){compressed.close();throw error;}
    }
    static void prepare(File directory,Map<String,Object> config,Source source,Consumer<String> log)throws Exception {
        prepare(directory,config,source,log,false);
    }
    static void prepareRequired(File directory,Map<String,Object> config,Source source,Consumer<String> log)throws Exception {
        prepare(directory,config,source,log,true);
    }
    static boolean required(Map<String,Object> config,String key) {
        // Classical providers can contain GEOIP/GEOSITE rules not visible here.
        if(Boolean.TRUE.equals(config.get("geo-auto-update")))return true;
        if(config.get("rule-providers") instanceof Map<?,?> providers)
            for(Object value:providers.values())if(value instanceof Map<?,?> rule&&"classical".equals(rule.get("behavior")))return true;
        String text=new JSONObject(config).toString().toLowerCase(Locale.ROOT);
        boolean site=key.equals("geosite");
        if(text.contains(site?"geosite,":"geoip,")||text.contains(site?"geosite:":"geoip:"))return true;
        if(config.get("dns") instanceof Map<?,?> dns&&dns.get("fallback") instanceof List<?> fallback&&!fallback.isEmpty()) {
            Map<?,?> filter=dns.get("fallback-filter") instanceof Map<?,?> f?f:Map.of();
            if(!site&&!Boolean.FALSE.equals(filter.get("geoip")))return true;
            if(site&&filter.get("geosite") instanceof List<?> sites&&!sites.isEmpty())return true;
        }
        return false;
    }
    private static void prepare(File directory,Map<String,Object> config,Source source,Consumer<String> log,boolean onlyRequired)throws Exception {
        JSONArray manifest;
        try(InputStream input=source.open("manifest.json")){manifest=new JSONArray(Profiles.readLimited(input));}
        Map<?,?> custom=config.get("geox-url") instanceof Map<?,?>?(Map<?,?>)config.get("geox-url"):Map.of();
        for(int i=0;i<manifest.length();i++) {
            JSONObject entry=manifest.getJSONObject(i);String name=entry.getString("name"),key=entry.getString("key");
            boolean dataMode=Boolean.TRUE.equals(config.get("geodata-mode"));
            if(key.equals("geoip")&&!dataMode||key.equals("mmdb")&&dataMode)continue;
            Object url=custom.get(key);
            if(url!=null&&!url.toString().isBlank()&&!url.toString().equalsIgnoreCase(entry.getString("url")))continue;
            JSONArray aliases=entry.getJSONArray("aliases");File target=new File(directory,name);
            File[] existing=directory.listFiles();if(existing!=null){Arrays.sort(existing,Comparator.comparing(File::getName));boolean found=false;for(File file:existing){if(!file.isFile())continue;for(int a=0;a<aliases.length();a++)if(file.getName().equalsIgnoreCase(aliases.getString(a))){target=file;found=true;break;}if(found)break;}}
            if(onlyRequired&&!required(config,key)) {
                // App-private runtime files only: remove an obsolete bundled copy,
                // but preserve any downloaded or user-modified database.
                if(target.isFile()&&target.length()==entry.getLong("size")) {
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");
                    try(InputStream in=new FileInputStream(target)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
                    StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
                    if(hex.toString().equalsIgnoreCase(entry.getString("sha256")))Files.delete(target.toPath());
                }
                continue;
            }
            if(looksComplete(target,name.endsWith(".metadb")))continue;
            File temp=new File(target.getPath()+".wbox-tmp");
            try {
                MessageDigest hash=MessageDigest.getInstance("SHA-256");
                try(InputStream input=source.open(name);OutputStream output=new FileOutputStream(temp)) {
                    byte[] buffer=new byte[65536];int count;
                    while((count=input.read(buffer))!=-1){hash.update(buffer,0,count);output.write(buffer,0,count);}
                }
                StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
                if(!hex.toString().equalsIgnoreCase(entry.getString("sha256")))throw new IOException("随附地理数据库校验失败："+name);
                Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                log.accept("INFO 已准备离线规则数据库 "+name);
            }finally{Files.deleteIfExists(temp.toPath());}
        }
    }
    // A cheap truncation check; the core still validates database semantics.
    static boolean looksComplete(File file,boolean mmdb)throws IOException {
        if(!file.isFile()||file.length()<32)return false;
        try(RandomAccessFile input=new RandomAccessFile(file,"r")) {
            if(mmdb) {
                byte[] tail=new byte[(int)Math.min(input.length(),131072)];input.seek(input.length()-tail.length);input.readFully(tail);
                byte[] marker={(byte)0xab,(byte)0xcd,(byte)0xef,77,97,120,77,105,110,100,46,99,111,109};
                for(int i=tail.length-marker.length-17;i>=0;i--){boolean match=true;for(int j=0;j<marker.length;j++)if(tail[i+j]!=marker[j]){match=false;break;}if(match)return true;}
                return false;
            }
            while(input.getFilePointer()<input.length()) {
                if(input.read()!=10)return false;
                long length=0;int shift=0,b;
                do {b=input.read();if(b<0||shift>28)return false;length|=(long)(b&127)<<shift;shift+=7;}while((b&128)!=0);
                if(length<=0||length>input.length()-input.getFilePointer())return false;
                input.seek(input.getFilePointer()+length);
            }
            return true;
        }
    }
}

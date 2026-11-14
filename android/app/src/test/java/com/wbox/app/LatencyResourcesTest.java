package com.wbox.app;

import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.io.File;
import static org.junit.Assert.*;

public class LatencyResourcesTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void implicitProviderCacheIsFixedToCapturedBytes()throws Exception {
        Profiles p=new Profiles(temp.getRoot());
        String content="{\"proxy-providers\":{\"Remote\":{\"type\":\"http\",\"url\":\"http://example.test/provider\",\"interval\":3600,\"health-check\":{\"enable\":true}}},\"proxy-groups\":[{\"name\":\"Main\",\"type\":\"select\",\"use\":[\"Remote\"]}],\"rules\":[\"MATCH,Main\"]}";
        p.importConfig(content,null,null,true);
        String id=p.activeConfig().getString("id");
        String hash=String.format("%032x",new java.math.BigInteger(1,java.security.MessageDigest.getInstance("MD5").digest("http://example.test/provider".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        Path cache=temp.getRoot().toPath().resolve("runtime/mihomo/"+id+"/proxies/"+hash);Files.createDirectories(cache.getParent());
        String original="proxies: [{name: A, type: http, server: localhost, port: 82}]\n";
        Files.write(cache,original.getBytes(java.nio.charset.StandardCharsets.UTF_8));LatencyResources.capture(p);String identity=LatencyContext.key(p);
        Files.write(cache,original.replace("82","83000").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File output=temp.newFolder("probe");String compiled=LatencyResources.fix(p,p.compile("test",31080,31090,true,output),output);
        JSONObject provider=new JSONObject(Profiles.yamlMap(compiled)).getJSONObject("proxy-providers").getJSONObject("Remote");
        assertEquals("file",provider.getString("type"));assertFalse(provider.has("url"));assertFalse(provider.getJSONObject("health-check").getBoolean("enable"));
        assertEquals(original,new String(Files.readAllBytes(new File(provider.getString("path")).toPath()),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(identity,LatencyContext.key(p));assertFalse(LatencyContext.valid(p,p));
        assertEquals(content,p.activeConfig().getString("content"));assertEquals(original.replace("82","83000"),new String(Files.readAllBytes(cache),java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void bundledRuleLocalizationDoesNotShiftCapturedRuleResources()throws Exception {
        Profiles p=new Profiles(temp.getRoot());
        String content="{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}],\"route\":{\"rule_set\":[{\"tag\":\"known\",\"type\":\"remote\",\"format\":\"binary\",\"url\":\"https://anti-ad.net/anti-ad-sing-box.srs\"},{\"tag\":\"custom\",\"type\":\"local\",\"format\":\"source\",\"path\":\"custom.json\"}]}}";
        p.importConfig(content,null,null,true);
        Path cache=temp.getRoot().toPath().resolve("runtime/sing-box/"+p.activeConfig().getString("id")+"/custom.json");Files.createDirectories(cache.getParent());
        String original="{\"version\":3,\"rules\":[{\"domain\":[\"before.example\"]}]}";
        String updated=original.replace("before.example","after.example");
        Files.write(cache,original.getBytes(java.nio.charset.StandardCharsets.UTF_8));LatencyResources.capture(p);Files.write(cache,updated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File output=temp.newFolder("probe-rules");String compiled=p.compile("test",31080,31090,true,output);
        JSONObject localizedKnown=new JSONObject(compiled).getJSONObject("route").getJSONArray("rule_set").getJSONObject(0);
        String knownPath=localizedKnown.getString("path");byte[] knownBytes=Files.readAllBytes(Path.of(knownPath));
        JSONArray rules=new JSONObject(LatencyResources.fix(p,compiled,output)).getJSONObject("route").getJSONArray("rule_set");
        JSONObject known=rules.getJSONObject(0),custom=rules.getJSONObject(1);
        assertEquals("known",known.getString("tag"));assertEquals("binary",known.getString("format"));assertEquals(knownPath,known.getString("path"));
        assertArrayEquals(knownBytes,Files.readAllBytes(Path.of(known.getString("path"))));
        assertEquals("custom",custom.getString("tag"));assertEquals("local",custom.getString("type"));assertEquals("source",custom.getString("format"));
        assertEquals(original,new String(Files.readAllBytes(Path.of(custom.getString("path"))),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(updated,new String(Files.readAllBytes(cache),java.nio.charset.StandardCharsets.UTF_8));assertEquals(content,p.activeConfig().getString("content"));
    }
    @Test public void bundledRuleFormatAndSameNamedProxyProviderArePreserved()throws Exception {
        Profiles p=new Profiles(temp.getRoot());
        String content="{\"proxy-providers\":{\"known\":{\"type\":\"http\",\"url\":\"https://example.test/proxies\",\"path\":\"proxies.yaml\"}},\"proxy-groups\":[{\"name\":\"Main\",\"type\":\"select\",\"use\":[\"known\"]}],\"rule-providers\":{\"known\":{\"type\":\"http\",\"behavior\":\"domain\",\"format\":\"yaml\",\"url\":\"https://anti-ad.net/clash.yaml\",\"path\":\"rules.yaml\"}},\"rules\":[\"RULE-SET,known,DIRECT\",\"MATCH,Main\"]}";
        p.importConfig(content,null,null,true);
        Path runtime=temp.getRoot().toPath().resolve("runtime/mihomo/"+p.activeConfig().getString("id"));Files.createDirectories(runtime);
        Path proxyCache=runtime.resolve("proxies.yaml"),ruleCache=runtime.resolve("rules.yaml");
        String proxies="proxies: [{name: A, type: http, server: localhost, port: 82}]\n",rules="payload:\n - cached.example\n";
        Files.write(proxyCache,proxies.getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.write(ruleCache,rules.getBytes(java.nio.charset.StandardCharsets.UTF_8));LatencyResources.capture(p);
        Files.write(proxyCache,proxies.replace("82","83").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File output=temp.newFolder("probe-formats");String compiled=p.compile("test",31080,31090,true,output);
        JSONObject localized=new JSONObject(Profiles.yamlMap(compiled)).getJSONObject("rule-providers").getJSONObject("known");
        String bundledPath=localized.getString("path");byte[] bundledBytes=Files.readAllBytes(Path.of(bundledPath));
        JSONObject fixed=new JSONObject(Profiles.yamlMap(LatencyResources.fix(p,compiled,output)));
        JSONObject rule=fixed.getJSONObject("rule-providers").getJSONObject("known"),proxy=fixed.getJSONObject("proxy-providers").getJSONObject("known");
        assertEquals("file",rule.getString("type"));assertEquals("mrs",rule.getString("format"));assertEquals(bundledPath,rule.getString("path"));
        assertArrayEquals(bundledBytes,Files.readAllBytes(Path.of(rule.getString("path"))));
        assertEquals("file",proxy.getString("type"));assertFalse(proxy.has("url"));assertEquals(proxies,new String(Files.readAllBytes(Path.of(proxy.getString("path"))),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(rules,new String(Files.readAllBytes(ruleCache),java.nio.charset.StandardCharsets.UTF_8));assertEquals(content,p.activeConfig().getString("content"));
    }
}

package com.wbox.app;

import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class GeodataTest {
    @Rule public org.junit.rules.TemporaryFolder temp=new org.junit.rules.TemporaryFolder();
    private final File assets=new File(System.getProperty("wbox.root"),"resources/geodata");
    private final Geodata.Source source=name->new FileInputStream(new File(assets,name));
    @Test public void compressedAssetsDecodeToVerifiedOriginalDatabases()throws Exception {
        File generated=new File(System.getProperty("wbox.root"),"android/app/build/generated/wboxGeodata");
        Geodata.Source packed=name->name.equals("manifest.json")?source.open(name):Geodata.decode(new FileInputStream(new File(generated,name+".xz")));
        File dir=temp.newFolder();Geodata.prepare(dir,Map.of("geodata-mode",true),packed,s->{});
        assertEquals(new File(assets,"GeoIP.dat").length(),new File(dir,"GeoIP.dat").length());
        Geodata.prepare(dir,Map.of(),packed,s->{});assertTrue(Geodata.looksComplete(new File(dir,"geoip.metadb"),true));
        assertThrows(IOException.class,()->Geodata.decode(new ByteArrayInputStream(new byte[30])));
    }
    @Test public void onlyUsedDatabasesAreCopiedAndObsoleteBundledCopiesAreRemoved()throws Exception {
        File dir=temp.newFolder();Geodata.prepare(dir,Map.of(),source,s->{});
        Geodata.prepareRequired(dir,Map.of("rules",List.of("MATCH,DIRECT")),source,s->{});assertEquals(0,dir.list().length);
        Geodata.prepareRequired(dir,Map.of("rules",List.of("GEOSITE,CN,DIRECT")),source,s->{});
        assertTrue(new File(dir,"GeoSite.dat").isFile());assertFalse(new File(dir,"geoip.metadb").exists());
        Files.write(new File(dir,"GeoSite.dat").toPath(),new byte[]{1,2,3});
        Geodata.prepareRequired(dir,Map.of(),source,s->{});assertEquals(3,new File(dir,"GeoSite.dat").length());
    }
    @Test public void hiddenDatabaseDependenciesRemainOffline() {
        assertTrue(Geodata.required(Map.of("dns",Map.of("fallback",List.of("1.1.1.1"))),"mmdb"));
        assertFalse(Geodata.required(Map.of("dns",Map.of("fallback",List.of("1.1.1.1"),"fallback-filter",Map.of("geoip",false))),"mmdb"));
        assertTrue(Geodata.required(Map.of("rule-providers",Map.of("remote",Map.of("behavior","classical"))),"geosite"));
        assertTrue(Geodata.required(Map.of("dns",Map.of("nameserver-policy",Map.of("geosite:cn","1.1.1.1"))),"geosite"));
        assertFalse(Geodata.required(Map.of("rule-providers",Map.of("geosite-cn",Map.of("behavior","domain"))),"geosite"));
    }
    @Test public void seedRepairAndKeepCompleteFiles()throws Exception {
        File dir=temp.newFolder();File mmdb=new File(dir,"Country.mmdb"),site=new File(dir,"GeoSite.dat");
        Files.write(mmdb.toPath(),new byte[100]);Files.write(site.toPath(),new byte[300]);
        Geodata.prepare(dir,Map.of(),source,s->{});
        assertTrue(Geodata.looksComplete(mmdb,true));assertTrue(Geodata.looksComplete(site,false));
        assertFalse(new File(dir,"GeoIP.dat").exists());
        long modified=mmdb.lastModified();Geodata.prepare(dir,Map.of(),source,s->fail("Should preserve existing data"));assertEquals(modified,mmdb.lastModified());
        Geodata.prepare(dir,Map.of("geodata-mode",true),source,s->{});assertTrue(Geodata.looksComplete(new File(dir,"GeoIP.dat"),false));
    }
    @Test public void customUrlsAreNotReplaced()throws Exception {
        File dir=temp.newFolder();Geodata.prepare(dir,Map.of("geox-url",Map.of("mmdb","https://example.test/mmdb","geosite","https://example.test/site")),source,s->fail());
        assertEquals(0,dir.list().length);
    }
    @Test public void truncatedOrTamperedAssetsNeverReplaceOriginalFile()throws Exception {
        File dir=temp.newFolder();File target=new File(dir,"geoip.metadb");Files.write(target.toPath(),new byte[]{1,2,3});
        try {Geodata.prepare(dir,Map.of(),name->name.equals("manifest.json")?source.open(name):new ByteArrayInputStream(new byte[200]),s->{});fail();}
        catch(IOException e){assertTrue(e.getMessage().contains("校验失败"));}
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(target.toPath()));assertFalse(new File(dir,"geoip.metadb.wbox-tmp").exists());
    }
}

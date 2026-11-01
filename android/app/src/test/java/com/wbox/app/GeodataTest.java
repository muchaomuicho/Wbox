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

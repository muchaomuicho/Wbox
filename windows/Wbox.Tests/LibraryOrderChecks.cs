using System.Text.Json;
using Wbox;

static class LibraryOrderChecks
{
    public static void Run(Action<bool,string> check)
    {
        var now=DateTime.UtcNow;
        ConfigProfile Profile(string id,string name,int age)=>new(){Id=id,Name=name,Path=id+".yaml",Core="mihomo",Updated=now.AddHours(-age)};
        var a=Profile("a","Atlas",3);var b=Profile("b","Beta",2);var c=Profile("c","Cygnus",1);
        var p=new Preferences{Configs=[a,b,c],ConfigPath=b.Path,Core="mihomo",SystemProxy=false};
        string Order()=>string.Join(",",p.Configs.Select(profile=>profile.Id));
        check(LibraryOrder.Move(p,"config","a","c",true)&&Order()=="b,c,a","Card move supports forward insertion after the anchor");
        check(LibraryOrder.Move(p,"config","a","b",false)&&Order()=="a,b,c","Card move supports backward insertion before the anchor");
        check(p.ConfigPath==b.Path&&p.Core=="mihomo"&&p.Configs[1]==b,"Card move preserves selected path and profile objects");
        check(!LibraryOrder.Move(p,"config","a","a",true)&&!LibraryOrder.Move(p,"config","removed","a",false)&&!LibraryOrder.Move(p,"config","a","removed",true)&&Order()=="a,b,c","Stale or identical drag anchors do not recreate or remove profiles");
        p.LibrarySort="updated";
        check(string.Join(",",LibraryOrder.Configs(p).Select(profile=>profile.Id))=="c,b,a"&&Order()=="a,b,c","Automatic sort leaves the saved manual order intact");
        check(LibraryOrder.Move(p,"config","a","c",false)&&p.LibrarySort=="default"&&Order()=="a,c,b","Dragging under automatic sort materializes its visible order and returns to manual");
        var added=Profile("new","New",0);p.Configs.Add(added);b.Name="Updated Beta";p.Configs.Remove(c);
        check(LibraryOrder.Move(p,"config","a","b",true)&&Order()=="b,a,new"&&p.Configs[0].Name=="Updated Beta","Incremental drag retains concurrently added and edited profiles and respects deletion");
        LibraryOrder.MergeConfigs(p,["a","deleted","a","b"]);
        check(Order()=="a,b,new"&&p.Configs.Distinct().Count()==3,"Older overview reorder IDs merge without deleting new profiles or introducing duplicates");
        var restored=JsonSerializer.Deserialize<Preferences>(JsonSerializer.Serialize(p))!;
        check(string.Join(",",LibraryOrder.Configs(restored).Select(profile=>profile.Id))=="a,b,new"&&restored.LibrarySort=="default","Manual order survives preferences serialization");
        p.LibrarySort="name";
        check(string.Join(",",LibraryOrder.Configs(p).Select(profile=>profile.Id))=="a,new,b","Overview and library can share the same automatic order");
        p.Subscriptions=["https://example.org/a","https://example.org/b","https://example.org/c"];
        foreach(var url in p.Subscriptions)p.SubscriptionSettings[url]=new(){Name=url};
        p.LibrarySort="default";var selected=p.SelectedNode;
        check(LibraryOrder.Move(p,"subscription",p.Subscriptions[0],p.Subscriptions[2],true)&&p.Subscriptions[2].EndsWith("/a")&&p.SelectedNode==selected,"Subscription cards reorder without changing node selection or settings");
    }
}

using System.Text.Json;
using Wbox;

static class SubscriptionChecks
{
    public static void Run(Action<bool,string> check)
    {
        var now=DateTime.UtcNow;var p=new Preferences{SubscriptionAutoUpdate=true,SubscriptionUpdateMinutes=60,SubscriptionLastAttempt=now.AddMinutes(-59)};
        check(!SubscriptionRefresh.Due(p,now)&&SubscriptionRefresh.Due(p,now.AddMinutes(1)),"Subscription interval uses the persisted last attempt");
        var manual=new NodeRecord{Name="manual",Protocol="http",Server="localhost",Port=1};
        var old=new NodeRecord{Name="old",Protocol="http",Server="localhost",Port=2,SourceSubscription="https://example.org/sub"};
        p.Nodes=[manual,old];p.Subscriptions=[old.SourceSubscription];p.SelectedNode=1;
        var revision=SubscriptionRefresh.NodeRevision(p,old.SourceSubscription);
        var bad=new SubscriptionResult("",old.SourceSubscription,revision,"invalid content",null);
        check(SubscriptionRefresh.Apply(p,[bad],true).Length>0&&p.Nodes.Count==2&&p.Nodes[1]==old,"Invalid auto update preserves the old source");
        var good=bad with {Content="proxies: [{name: old, type: http, server: localhost, port: 3}]"};
        check(SubscriptionRefresh.Apply(p,[good],true).Length==0&&p.Nodes[0]==manual&&p.Nodes[1].Port==3&&p.SelectedNode==1,"Auto update preserves manual nodes and selected node identity");
        p.Nodes[1].Port=4;SubscriptionRefresh.Apply(p,[good],true);
        check(p.Nodes[1].Port==4,"A concurrent node edit wins over a stale download");
        p.Subscriptions.Clear();SubscriptionRefresh.Apply(p,[good],true);
        check(p.Subscriptions.Count==0,"Removed subscriptions are not recreated by pending downloads");
    }
}

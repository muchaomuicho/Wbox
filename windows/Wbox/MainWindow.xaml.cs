using System.ComponentModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Forms = System.Windows.Forms;
using Color = System.Windows.Media.Color;
using ColorConverter = System.Windows.Media.ColorConverter;
using RadioButton = System.Windows.Controls.RadioButton;
namespace Wbox;

public partial class MainWindow : Window, IDisposable
{
    readonly Preferences preferences;
    readonly CoreManager core = new();
    readonly ObservableCollection<string> logs = [];
    readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(1) };
    readonly Forms.NotifyIcon tray;
    bool initialized, busy, polling, updatingGroups, exiting;
    int ticks;
    public MainWindow(Preferences preferences)
    {
        this.preferences = preferences; InitializeComponent();
        LogsList.ItemsSource = logs;
        CoreBox.SelectedIndex = preferences.Core == "sing-box" ? 1 : 0;
        ConfigMode.IsChecked = preferences.Mode == "config"; NodeMode.IsChecked = preferences.Mode != "config";
        ConfigPathBox.Text = preferences.ConfigPath; DelayUrlBox.Text = preferences.DelayUrl;
        AutoStartCheck.IsChecked = preferences.AutoStart; SilentCheck.IsChecked = preferences.Silent;
        AutoConnectCheck.IsChecked = preferences.AutoConnect; SystemProxyCheck.IsChecked = preferences.SystemProxy;
        RefreshNodes(); SavedSubscriptionsBox.ItemsSource = preferences.Subscriptions.ToArray();
        var iconPath = Path.Combine(AppContext.BaseDirectory, "wbox.ico");
        tray = new Forms.NotifyIcon { Text = "Wbox · 未连接", Visible = true, Icon = File.Exists(iconPath) ? new System.Drawing.Icon(iconPath) : System.Drawing.SystemIcons.Application };
        tray.DoubleClick += (_, _) => Dispatcher.Invoke(ShowFromTray);
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("打开 Wbox", null, (_, _) => Dispatcher.Invoke(ShowFromTray));
        menu.Items.Add("连接 / 停止", null, (_, _) => Dispatcher.BeginInvoke(async () => await ToggleAsync()));
        menu.Items.Add(new Forms.ToolStripSeparator()); menu.Items.Add("退出", null, (_, _) => Dispatcher.Invoke(ExitApp)); tray.ContextMenuStrip = menu;
        core.Log += line => Dispatcher.BeginInvoke(() => AddLog(line));
        core.Exited += () => Dispatcher.BeginInvoke(() => { AddLog("内核已异常退出，系统代理已恢复。"); core.Stop(); SetState(false); });
        timer.Tick += async (_, _) => await PollAsync(); timer.Start();
        Closing += OnClosing; initialized = true; UpdateSummary();
    }
    public void ShowFromTray() { Show(); WindowState = WindowState.Normal; Activate(); }
    void OnClosing(object? sender, CancelEventArgs e) { if (!exiting) { e.Cancel = true; SaveSettings(false); Hide(); } }
    public void ExitApp() { if (exiting) return; exiting = true; SaveSettings(false); Dispose(); System.Windows.Application.Current.Shutdown(); }
    public void Dispose() { timer.Stop(); core.Dispose(); tray.Visible = false; tray.Dispose(); }
    void AddLog(string text)
    {
        // Remove ANSI color sequences from command-line output.
        text = System.Text.RegularExpressions.Regex.Replace(text, "\u001b\\[[0-9;]*m", "");
        if (text.Length > 4096) text = text[..4096] + "…";
        logs.Add(text); while (logs.Count > 1000) logs.RemoveAt(0);
        if (logs.Count > 0 && IsVisible) LogsList.ScrollIntoView(logs[^1]);
    }
    void SetState(bool running)
    {
        StartButton.Content = running ? "⏻  断开连接" : "⏻  连接代理";
        StartButton.Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString(running ? "#202227" : "#15845B"));
        StateLabel.Text = running ? "连接已就绪" : "尚未连接";
        StateGlyph.Foreground = running ? new SolidColorBrush(Color.FromRgb(21, 132, 91)) : (SolidColorBrush)FindResource("Ink");
        tray.Text = running ? "Wbox · 已连接" : "Wbox · 未连接";
        if (!running) { ConnectionCount.Text = "0 / 0"; UploadRate.Text = "0 B/s"; DownloadRate.Text = "0 B/s"; ConnectionsGrid.ItemsSource = null; GroupsBox.ItemsSource = null; GroupNodesBox.ItemsSource = null; }
        UpdateSummary();
    }
    void SaveSettings(bool show)
    {
        if (!initialized) return;
        preferences.Core = CoreBox.SelectedIndex == 1 ? "sing-box" : "mihomo";
        preferences.Mode = ConfigMode.IsChecked == true ? "config" : "nodes";
        preferences.DelayUrl = string.IsNullOrWhiteSpace(DelayUrlBox.Text) ? "https://www.apple.com/library/test/success.html" : DelayUrlBox.Text.Trim();
        preferences.AutoStart = AutoStartCheck.IsChecked == true; preferences.Silent = SilentCheck.IsChecked == true;
        preferences.AutoConnect = AutoConnectCheck.IsChecked == true; preferences.SystemProxy = SystemProxyCheck.IsChecked == true;
        preferences.ConfigPath = ConfigPathBox.Text;
        Store.Save(preferences); UpdateSummary();
        try { if (Environment.GetEnvironmentVariable("WBOX_DATA_DIR") == null) SystemProxy.SetAutoStart(preferences.AutoStart, preferences.Silent); if (show) Notice.Text = "设置已保存"; }
        catch (Exception ex) { Notice.Text = "开机启动设置失败：" + ex.Message; AddLog(Notice.Text); }
    }
    async Task ToggleAsync() { if (busy) return; if (core.Running) { core.Stop(); SetState(false); Notice.Text = "已停止，系统代理已恢复"; } else await ConnectAsync(); }
    public async Task ConnectAsync()
    {
        if (busy || core.Running) return; busy = true; StartButton.IsEnabled = false; SaveSettings(false); StateLabel.Text = "正在连接…";
        try { await core.StartAsync(preferences); Chart.Clear(); timer.Interval = TimeSpan.FromSeconds(1); SetState(true); Notice.Text = "连接成功"; await RefreshGroupsAsync(); }
        catch (Exception ex) { AddLog(ex.Message); Notice.Text = ex.Message.Replace('\n', ' '); SetState(false); }
        finally { busy = false; StartButton.IsEnabled = true; }
    }
    async Task PollAsync()
    {
        if (polling || busy || !core.Running) { if (!core.Running) { timer.Interval = TimeSpan.FromSeconds(5); if (IsVisible) { using var current = Process.GetCurrentProcess(); MemoryLabel.Text = Bytes(current.WorkingSet64); } } return; }
        polling = true;
        try
        {
            var stats = await core.StatsAsync();
            UploadTotal.Text = Bytes(stats.Uploaded); DownloadTotal.Text = Bytes(stats.Downloaded);
            UploadRate.Text = Bytes(stats.UploadRate) + "/s"; DownloadRate.Text = Bytes(stats.DownloadRate) + "/s";
            ConnectionCount.Text = $"{stats.Inbound} / {stats.Outbound}"; MemoryLabel.Text = Bytes(stats.Memory);
            Chart.Add(stats.UploadRate, stats.DownloadRate);
            if (IsVisible) ConnectionsGrid.ItemsSource = stats.Connections;
            if (++ticks % 10 == 0) await RefreshGroupsAsync();
            // A hidden window samples every 3 s to reduce background UI/controller work.
            timer.Interval = TimeSpan.FromSeconds(IsVisible ? 1 : 3);
        }
        catch (Exception ex) { if (core.Running && ticks % 10 == 0) AddLog("状态读取：" + ex.Message); }
        finally { polling = false; }
    }
    static string Bytes(long bytes)
    {
        string[] units = ["B", "KiB", "MiB", "GiB", "TiB"]; double value = bytes; int unit = 0;
        while (value >= 1024 && unit < units.Length - 1) { value /= 1024; unit++; }
        return $"{value:0.##} {units[unit]}";
    }
    async Task RefreshGroupsAsync()
    {
        if (!core.Running) return;
        var groups = await core.GroupsAsync(); var previous = (GroupsBox.SelectedItem as GroupItem)?.Name;
        updatingGroups = true;
        try { GroupsBox.ItemsSource = groups; GroupsBox.SelectedItem = groups.FirstOrDefault(g => g.Name == previous) ?? groups.FirstOrDefault(); UpdateGroupNodes(); }
        finally { updatingGroups = false; }
    }
    void UpdateGroupNodes()
    {
        if (GroupsBox.SelectedItem is not GroupItem group) return;
        GroupNodesBox.ItemsSource = group.Nodes; GroupNodesBox.SelectedItem = group.Selected;
        GroupNodesBox.IsEnabled = group.Selectable;
    }
    void RefreshNodes() { NodesList.ItemsSource = preferences.Nodes.ToArray(); NodesList.SelectedIndex = preferences.Nodes.Count == 0 ? -1 : Math.Clamp(preferences.SelectedNode, 0, preferences.Nodes.Count - 1); NodesHeading.Text = $"我的节点 · {preferences.Nodes.Count}"; }
    void MergeNodes(List<NodeRecord> nodes)
    {
        if (core.Running) throw new InvalidOperationException("请停止连接后再导入或更新节点。");
        // Replace identical credentials/server entries without collapsing multiple accounts on the same endpoint.
        foreach (var node in nodes)
        {
            var old = preferences.Nodes.FindIndex(n => n.Protocol == node.Protocol && n.Server == node.Server && n.Port == node.Port && n.Uuid == node.Uuid && n.Password == node.Password);
            if (old >= 0) preferences.Nodes[old] = node; else preferences.Nodes.Add(node);
        }
        RefreshNodes(); SaveSettings(false); Notice.Text = $"导入 {nodes.Count} 个节点，共 {preferences.Nodes.Count} 个";
    }
    async Task TestNodeAsync(string? name, bool allowTemporary)
    {
        if (busy) return; if (name == null) { Notice.Text = "请选择节点"; return; }
        busy = true; StartButton.IsEnabled = false; var temporary = !core.Running;
        try
        {
            SaveSettings(false);
            if (temporary)
            {
                if (!allowTemporary) throw new InvalidOperationException("请先连接，再测试代理组节点。");
                var testPrefs = JsonSerializer.Deserialize<Preferences>(JsonSerializer.Serialize(preferences))!;
                testPrefs.Mode = "nodes"; testPrefs.SystemProxy = false;
                Notice.Text = "临时启动内核测试节点…"; await core.StartAsync(testPrefs);
            }
            var delay = await core.DelayAsync(name, preferences.DelayUrl); Notice.Text = $"{name} · {delay} ms"; AddLog("延迟测试：" + Notice.Text);
            var index = preferences.Nodes.FindIndex(n => ConfigBuilder.Tag(n, preferences.Nodes.IndexOf(n)) == name);
            if (index >= 0) { preferences.Nodes[index].DelayText = $"{delay} ms"; NodesList.Items.Refresh(); }
        }
        catch (Exception ex) { Notice.Text = "测试失败：" + ex.Message; AddLog(Notice.Text); }
        finally { if (temporary) core.Stop(); busy = false; StartButton.IsEnabled = true; }
    }
    async void StartClick(object sender, RoutedEventArgs e) => await ToggleAsync();
    void CoreChanged(object sender, SelectionChangedEventArgs e) { if (initialized) SaveSettings(false); }
    void ModeChanged(object sender, RoutedEventArgs e) { if (initialized) SaveSettings(false); }
    void GroupChanged(object sender, SelectionChangedEventArgs e) { if (updatingGroups) return; updatingGroups = true; try { UpdateGroupNodes(); } finally { updatingGroups = false; } }
    async void GroupNodeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (updatingGroups || !core.Running || GroupsBox.SelectedItem is not GroupItem group || !group.Selectable || GroupNodesBox.SelectedItem is not string node) return;
        try { await core.SelectAsync(group.Name, node); Notice.Text = $"{group.Name} → {node}"; }
        catch (Exception ex) { Notice.Text = "切换失败：" + ex.Message; await RefreshGroupsAsync(); }
    }
    async void NodeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!initialized || NodesList.SelectedIndex < 0) return; preferences.SelectedNode = NodesList.SelectedIndex; SaveSettings(false);
        if (core.Running && preferences.Mode == "nodes")
        {
            try { await core.SelectAsync("Wbox", ConfigBuilder.Tag(preferences.Nodes[preferences.SelectedNode], preferences.SelectedNode)); await RefreshGroupsAsync(); }
            catch (Exception ex) { Notice.Text = ex.Message; }
        }
    }
    async void DelayClick(object sender, RoutedEventArgs e) => await TestNodeAsync(GroupNodesBox.SelectedItem as string, false);
    async void DelayNodeClick(object sender, RoutedEventArgs e) => await TestNodeAsync(NodesList.SelectedItem is NodeRecord n ? ConfigBuilder.Tag(n, NodesList.SelectedIndex) : null, true);
    void ImportLinksClick(object sender, RoutedEventArgs e) { try { MergeNodes(NodeParser.ParseSubscription(ImportText.Text)); ImportText.Clear(); } catch (Exception ex) { Notice.Text = ex.Message; } }
    void ImportNodesFileClick(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFileDialog { Filter = "订阅 / 节点文件|*.txt;*.json;*.yaml;*.yml|所有文件|*.*" };
        if (dialog.ShowDialog() == true) { try { MergeNodes(NodeParser.ParseSubscription(File.ReadAllText(dialog.FileName))); } catch (Exception ex) { Notice.Text = ex.Message; } }
    }
    void DeleteNodeClick(object sender, RoutedEventArgs e)
    {
        if (core.Running) { Notice.Text = "请停止连接后删除节点"; return; }
        if (NodesList.SelectedItem is NodeRecord node) { preferences.Nodes.Remove(node); preferences.SelectedNode = 0; RefreshNodes(); SaveSettings(false); }
    }
    void ChooseConfigClick(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFileDialog { Filter = "代理配置|*.json;*.yaml;*.yml|所有文件|*.*" };
        if (dialog.ShowDialog() == true) { ConfigPathBox.Text = dialog.FileName; CoreBox.SelectedIndex = Path.GetExtension(dialog.FileName).Equals(".json", StringComparison.OrdinalIgnoreCase) ? 1 : 0; ConfigMode.IsChecked = true; SaveSettings(false); Notice.Text = "配置已选择，连接时会先通过内核检查"; }
    }
    async void SubscriptionClick(object sender, RoutedEventArgs e)
    {
        if (busy || core.Running) { Notice.Text = "请停止连接后导入订阅"; return; } busy = true;
        try
        {
            var url = SubscriptionBox.Text.Trim();
            if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https")) throw new ArgumentException("请输入 HTTP 或 HTTPS 订阅链接。");
            Notice.Text = "正在下载订阅…";
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(30) }; client.DefaultRequestHeaders.UserAgent.ParseAdd("Wbox/0.1.0 sing-box mihomo");
            using var response = await client.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead); response.EnsureSuccessStatusCode();
            if (response.Content.Headers.ContentLength > 8 * 1024 * 1024) throw new IOException("订阅文件超过 8 MiB。");
            using var input = await response.Content.ReadAsStreamAsync(); using var output = new MemoryStream(); var buffer = new byte[8192];
            int read; while ((read = await input.ReadAsync(buffer)) > 0) { if (output.Length + read > 8 * 1024 * 1024) throw new IOException("订阅文件超过 8 MiB。"); output.Write(buffer, 0, read); }
            var nodes = NodeParser.ParseSubscription(Encoding.UTF8.GetString(output.ToArray()));
            // Only replace the old entries of this subscription; independently imported nodes remain.
            preferences.Nodes.RemoveAll(n => n.SourceSubscription == url);
            foreach (var node in nodes) node.SourceSubscription = url;
            MergeNodes(nodes);
            if (!preferences.Subscriptions.Contains(url)) preferences.Subscriptions.Add(url); SavedSubscriptionsBox.ItemsSource = preferences.Subscriptions.ToArray(); SaveSettings(false);
        }
        catch (Exception ex) { Notice.Text = "订阅导入失败：" + ex.Message; AddLog(Notice.Text); }
        finally { busy = false; }
    }
    void SubscriptionSelected(object sender, SelectionChangedEventArgs e) { if (SavedSubscriptionsBox.SelectedItem is string url) SubscriptionBox.Text = url; }
    void SaveSettingsClick(object sender, RoutedEventArgs e) => SaveSettings(true);
    void ClearLogsClick(object sender, RoutedEventArgs e) => logs.Clear();
    void CopyLogsClick(object sender, RoutedEventArgs e) { System.Windows.Clipboard.SetText(string.Join(Environment.NewLine, logs)); Notice.Text = "日志已复制"; }
    void OpenDataClick(object sender, RoutedEventArgs e) => Process.Start(new ProcessStartInfo("explorer.exe", Store.DirectoryPath) { UseShellExecute = true });
    void ExitClick(object sender, RoutedEventArgs e) => ExitApp();
    void NavigateClick(object sender, RoutedEventArgs e) => MainTabs.SelectedIndex = NavigationPanel.Children.IndexOf((UIElement)sender);
    void OpenProfilesClick(object sender, RoutedEventArgs e) => MainTabs.SelectedIndex = 2;
    void PageChanged(object sender, SelectionChangedEventArgs e)
    {
        if (e.Source != MainTabs || MainTabs.SelectedIndex < 0) return;
        var index = MainTabs.SelectedIndex;
        string[] captions = ["连接状态与网络活动，一目了然。", "管理你的节点，找到适合的连接。", "以你习惯的方式，组织连接。", "查看每一条连接的来源与去向。", "了解内核正在做什么。", "让 Wbox 按你的习惯运行。"];
        PageTitle.Text = ((TabItem)MainTabs.SelectedItem).Header.ToString(); PageCaption.Text = captions[index];
        ((RadioButton)NavigationPanel.Children[index]).IsChecked = true;
    }
    void UpdateSummary()
    {
        SidebarCore.Text = preferences.Core;
        ModeBadge.Text = preferences.Mode == "config" ? "配置文件模式" : "单节点模式";
        ConnectionHint.Text = preferences.Mode == "config"
            ? (string.IsNullOrEmpty(preferences.ConfigPath) ? "选择一个 JSON 或 YAML 配置，即可开始。" : Path.GetFileName(preferences.ConfigPath))
            : preferences.Nodes.Count == 0 ? "导入节点或配置，开启你的第一次连接。" : preferences.Nodes[Math.Clamp(preferences.SelectedNode, 0, preferences.Nodes.Count - 1)].Name;
        OverviewFootnote.Text = preferences.Mode == "config" ? "使用原生配置中的 DNS、路由与 IPv6 设置" : (preferences.SystemProxy ? "系统代理" : "本地代理") + " · 127.0.0.1:10808 · IPv6 已启用";
    }
}

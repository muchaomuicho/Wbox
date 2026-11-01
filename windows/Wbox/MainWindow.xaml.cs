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
using TextBox = System.Windows.Controls.TextBox;
using Button = System.Windows.Controls.Button;
using Orientation = System.Windows.Controls.Orientation;
using Brush = System.Windows.Media.Brush;
namespace Wbox;

public partial class MainWindow : Window, IDisposable
{
    [System.Runtime.InteropServices.DllImport("dwmapi.dll")]
    static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
    readonly Preferences preferences;
    readonly CoreManager core = new();
    readonly ObservableCollection<string> logs = [];
    readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(1) };
    readonly Forms.NotifyIcon tray;
    bool initialized, busy, polling, exiting, refreshingNodes, logsPaused;
    List<ConnectionItem> currentConnections = [];
    readonly TextBox ImportText = new();
    int ticks;
    bool cancelTests;
    bool disposed;
    readonly System.Collections.Concurrent.ConcurrentQueue<string> pendingLogs = new();
    public MainWindow(Preferences preferences)
    {
        this.preferences = preferences; InitializeComponent();
        SourceInitialized += (_, _) => { int dark = 1; DwmSetWindowAttribute(new System.Windows.Interop.WindowInteropHelper(this).Handle, 20, ref dark, sizeof(int)); };
        if (preferences.NodeCore.Length == 0) preferences.NodeCore = preferences.Core;
        if (preferences.Configs.Count == 0 && File.Exists(preferences.ConfigPath)) preferences.Configs.Add(new ConfigProfile { Name = Path.GetFileNameWithoutExtension(preferences.ConfigPath), Path = preferences.ConfigPath, Core = preferences.Core });
        LogsList.ItemsSource = logs;
        System.Windows.Data.CollectionViewSource.GetDefaultView(logs).Filter = line => line is string text && text.Contains(LogSearch.Text, StringComparison.OrdinalIgnoreCase);
        CoreBox.SelectedIndex = preferences.NodeCore == "sing-box" ? 1 : 0;
        ConfigMode.IsChecked = preferences.Mode == "config"; NodeMode.IsChecked = preferences.Mode != "config";
        DelayUrlBox.Text = preferences.DelayUrl;
        AutoStartCheck.IsChecked = preferences.AutoStart; SilentCheck.IsChecked = preferences.Silent;
        ManagedInbound.IsChecked = !preferences.NativeInbounds; NativeInbound.IsChecked = preferences.NativeInbounds;
        AutoConnectCheck.IsChecked = preferences.AutoConnect; SystemProxyCheck.IsChecked = preferences.SystemProxy;
        RefreshNodes(); SavedSubscriptionsBox.ItemsSource = preferences.Subscriptions.ToArray();
        var iconPath = Path.Combine(AppContext.BaseDirectory, "wbox.ico");
        tray = new Forms.NotifyIcon { Text = "Wbox · 未连接", Visible = true, Icon = File.Exists(iconPath) ? new System.Drawing.Icon(iconPath) : System.Drawing.SystemIcons.Application };
        tray.DoubleClick += (_, _) => Dispatcher.Invoke(ShowFromTray);
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("打开 Wbox", null, (_, _) => Dispatcher.Invoke(ShowFromTray));
        menu.Items.Add("连接 / 停止", null, (_, _) => Dispatcher.BeginInvoke(async () => await ToggleAsync()));
        menu.Items.Add(new Forms.ToolStripSeparator()); menu.Items.Add("退出", null, (_, _) => Dispatcher.Invoke(ExitApp)); tray.ContextMenuStrip = menu;
        core.Log += line => { pendingLogs.Enqueue(line.Length > 4096 ? line[..4096] : line); while (pendingLogs.Count > 500) pendingLogs.TryDequeue(out _); };
        core.Exited += () => Dispatcher.BeginInvoke(() => { AddLog("内核已异常退出，系统代理已恢复。"); core.Stop(); SetState(false); });
        timer.Tick += async (_, _) => await PollAsync(); timer.Start();
        Closing += OnClosing; MainTabs.SelectedIndex = 0; initialized = true; RefreshProfiles(); RefreshFilter(); UpdateWorkspace(); UpdateSummary();
    }
    public void ShowFromTray() { timer.Interval = TimeSpan.FromSeconds(1); Show(); WindowState = WindowState.Normal; Activate(); }
    void OnClosing(object? sender, CancelEventArgs e) { if (!exiting) { e.Cancel = true; SaveSettings(false); Hide(); } }
    public void ExitApp() { if (exiting) return; exiting = true; SaveSettings(false); Dispose(); System.Windows.Application.Current.Shutdown(); }
    public void Dispose() { if (disposed) return; disposed = true; timer.Stop(); core.Dispose(); tray.Visible = false; tray.Dispose(); }
    void AddLog(string text)
    {
        // Remove ANSI color sequences from command-line output.
        text = System.Text.RegularExpressions.Regex.Replace(text, "\u001b\\[[0-9;]*m", "");
        if (text.Length > 4096) text = text[..4096] + "…";
        logs.Add(text); while (logs.Count > 500) logs.RemoveAt(0);
        if (!logsPaused && logs.Count > 0 && IsVisible && MainTabs.SelectedIndex == 4 && logs[^1].Contains(LogSearch.Text,StringComparison.OrdinalIgnoreCase)) LogsList.ScrollIntoView(logs[^1]);
    }
    void SetState(bool running)
    {
        StartButton.Content = running ? "0:00:00  ■" : "▶";
        StateLabel.Text = running ? "连接已就绪" : "尚未连接";
        StateGlyph.Foreground = running ? new SolidColorBrush(Color.FromRgb(136, 180, 159)) : (SolidColorBrush)FindResource("Ink");
        tray.Text = running ? "Wbox · 已连接" : "Wbox · 未连接";
        if (!running) { ConnectionCount.Text = "0 / 0"; UploadRate.Text = "0 B/s"; DownloadRate.Text = "0 B/s"; currentConnections.Clear(); ApplyConnectionFilter(); GroupCards.Children.Clear(); GroupsEmpty.Visibility = Visibility.Visible; CoreMemoryLabel.Text = "—"; }
        UpdateSummary();
    }
    void SaveSettings(bool show)
    {
        if (!initialized) return;
        preferences.NodeCore = CoreBox.SelectedIndex == 1 ? "sing-box" : "mihomo";
        if (preferences.Mode == "nodes") preferences.Core = preferences.NodeCore;
        preferences.Mode = ConfigMode.IsChecked == true ? "config" : "nodes";
        preferences.DelayUrl = string.IsNullOrWhiteSpace(DelayUrlBox.Text) ? "https://www.apple.com/library/test/success.html" : DelayUrlBox.Text.Trim();
        preferences.AutoStart = AutoStartCheck.IsChecked == true; preferences.Silent = SilentCheck.IsChecked == true;
        preferences.AutoConnect = AutoConnectCheck.IsChecked == true; preferences.SystemProxy = SystemProxyCheck.IsChecked == true; preferences.NativeInbounds = NativeInbound.IsChecked == true;
        Store.Save(preferences); UpdateSummary();
        try { if (Environment.GetEnvironmentVariable("WBOX_DATA_DIR") == null) SystemProxy.SetAutoStart(preferences.AutoStart, preferences.Silent); if (show) Notice.Text = "设置已保存"; }
        catch (Exception ex) { Notice.Text = "开机启动设置失败：" + ex.Message; AddLog(Notice.Text); }
    }
    async Task ToggleAsync() { if (busy) return; if (core.Running) { try { core.Stop(); SetState(false); Notice.Text = "已停止，系统代理已恢复"; } catch (Exception ex) { AddLog(ex.Message); Notice.Text = "系统代理未恢复，内核保持运行：" + ex.Message; } } else await ConnectAsync(); }
    public async Task ConnectAsync()
    {
        if (busy || core.Running) return; busy = true; StartButton.IsEnabled = false; SaveSettings(false); StateLabel.Text = "正在连接…";
        try { await core.StartAsync(preferences); Chart.Clear(); DownloadChart.Clear(); timer.Interval = TimeSpan.FromSeconds(1); SetState(true); Notice.Text = "连接成功"; await RefreshGroupsAsync(); }
        catch (Exception ex) { AddLog(ex.Message); Notice.Text = ex.Message.Replace('\n', ' '); SetState(false); }
        finally { busy = false; StartButton.IsEnabled = true; UpdateSummary(); }
    }
    async Task PollAsync()
    {
        for (int i = 0; i < 100 && pendingLogs.TryDequeue(out var line); i++) AddLog(line);
        if (polling || busy || !core.Running) { if (!core.Running) { timer.Interval = TimeSpan.FromSeconds(5); if (IsVisible) { using var current = Process.GetCurrentProcess(); MemoryLabel.Text = Bytes(current.WorkingSet64); } } return; }
        polling = true;
        try
        {
            StartButton.Content = (DateTime.UtcNow - core.StartedAt).ToString(@"h\:mm\:ss") + "  ■";
            var stats = await core.StatsAsync(IsVisible && MainTabs.SelectedIndex == 3);
            if (!IsVisible || WindowState == WindowState.Minimized) { timer.Interval = TimeSpan.FromSeconds(10); return; }
            UploadTotal.Text = Bytes(stats.Uploaded); DownloadTotal.Text = Bytes(stats.Downloaded);
            UploadRate.Text = Bytes(stats.UploadRate) + "/s"; DownloadRate.Text = Bytes(stats.DownloadRate) + "/s";
            ConnectionCount.Text = $"{stats.Inbound} / {stats.Outbound}"; MemoryLabel.Text = Bytes(stats.AppMemory); CoreMemoryLabel.Text = Bytes(stats.CoreMemory);
            Chart.Add(stats.UploadRate, stats.DownloadRate); DownloadChart.Add(stats.UploadRate, stats.DownloadRate);
            if (MainTabs.SelectedIndex == 3) { currentConnections = stats.Connections; ApplyConnectionFilter(); }
            if (++ticks % 10 == 0 && MainTabs.SelectedIndex == 1 && preferences.Mode == "config") await RefreshGroupsAsync();
            // The hidden-window branch above polls only once per 10 seconds.
            timer.Interval = TimeSpan.FromSeconds(IsVisible ? 1 : 10);
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
        if (core.Running) RenderGroups(await core.GroupsAsync());
    }
    void RenderGroups(List<GroupItem> groups)
    {
        var previous = GroupCards.Children.OfType<ProxyGroupCard>().ToDictionary(c => c.GroupName);
        if (!previous.Keys.SequenceEqual(groups.Select(g => g.Name))) GroupCards.Children.Clear();
        foreach (var group in groups) {
            if (previous.TryGetValue(group.Name, out var card)) card.Apply(group);
            else card = new ProxyGroupCard(group, GroupCards.Children.Count == 0, async (name,node) => {
                try { await core.SelectAsync(name,node); await RefreshGroupsAsync(); Notice.Text = name + " → " + node; }
                catch (Exception ex) { Notice.Text = ex.Message; }
            }, node => core.DelayAsync(node,preferences.DelayUrl));
            if (!GroupCards.Children.Contains(card)) GroupCards.Children.Add(card);
        }
        GroupsEmpty.Visibility = groups.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }
    void RefreshNodes()
    {
        refreshingNodes = true;
        var selected = preferences.Nodes.ElementAtOrDefault(preferences.SelectedNode);
        var search = NodeSearch.Text.Trim(); var source = NodeFilter.SelectedIndex;
        var url = source > 1 ? preferences.Subscriptions.ElementAtOrDefault(source - 2) : null;
        NodesList.ItemsSource = preferences.Nodes.Where(n => (source != 1 || n.SourceSubscription.Length == 0) && (url == null || n.SourceSubscription == url) && (search.Length == 0 || $"{n.Name} {n.Server} {n.Protocol}".Contains(search, StringComparison.OrdinalIgnoreCase))).ToArray();
        NodesList.SelectedItem = selected; NodesHeading.Text = $"节点 · {NodesList.Items.Count}";
        refreshingNodes = false;
    }
    void MergeNodes(List<NodeRecord> nodes)
    {
        if (core.Running) throw new InvalidOperationException("请停止连接后再导入或更新节点。");
        // Replace identical credentials/server entries without collapsing multiple accounts on the same endpoint.
        foreach (var node in nodes)
        {
            var old = preferences.Nodes.FindIndex(n => n.SourceSubscription == node.SourceSubscription && n.Protocol == node.Protocol && n.Name == node.Name && n.Server == node.Server && n.Port == node.Port && n.Uuid == node.Uuid && n.Password == node.Password);
            if (old >= 0) preferences.Nodes[old] = node; else preferences.Nodes.Add(node);
        }
        RefreshNodes(); SaveSettings(false); Notice.Text = $"导入 {nodes.Count} 个节点，共 {preferences.Nodes.Count} 个";
        if (preferences.Nodes.All(n => n.NativeSingbox != null)) CoreBox.SelectedIndex = 1;
        else if (preferences.Nodes.All(n => n.NativeMihomo != null)) CoreBox.SelectedIndex = 0;
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
        finally { if (temporary) core.Stop(); busy = false; StartButton.IsEnabled = true; DelayAllButton.Content = "测试全部"; }
    }
    async void StartClick(object sender, RoutedEventArgs e) => await ToggleAsync();
    void CoreChanged(object sender, SelectionChangedEventArgs e) { if (initialized) { if (core.Running) { Notice.Text = "下次连接使用新内核"; } SaveSettings(false); } }
    void ModeChanged(object sender, RoutedEventArgs e) {
        if (!initialized) return;
        if (busy || core.Running) { initialized = false; ConfigMode.IsChecked = preferences.Mode == "config"; NodeMode.IsChecked = preferences.Mode != "config"; initialized = true; Notice.Text = "断开连接后可切换工作区"; return; }
        preferences.Mode = ConfigMode.IsChecked == true ? "config" : "nodes";
        preferences.Core = preferences.Mode == "nodes" ? preferences.NodeCore : preferences.Configs.FirstOrDefault(c => c.Path == preferences.ConfigPath)?.Core ?? "sing-box";
        SaveSettings(false); UpdateWorkspace();
    }
    async void NodeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!initialized || refreshingNodes || busy || NodesList.SelectedItem is not NodeRecord selected) return; preferences.SelectedNode = preferences.Nodes.IndexOf(selected); SaveSettings(false);
        if (core.Running && preferences.Mode == "nodes")
        {
            try { await core.SelectAsync("Wbox", ConfigBuilder.Tag(preferences.Nodes[preferences.SelectedNode], preferences.SelectedNode)); await RefreshGroupsAsync(); }
            catch (Exception ex) { Notice.Text = ex.Message; }
        }
    }
    async void DelayNodeClick(object sender, RoutedEventArgs e) => await TestNodeAsync(NodesList.SelectedItem is NodeRecord n ? ConfigBuilder.Tag(n, preferences.Nodes.IndexOf(n)) : null, true);
    void ImportLinksClick(object sender, RoutedEventArgs e) { try { MergeNodes(NodeParser.ParseSubscription(ImportText.Text)); ImportText.Clear(); } catch (Exception ex) { Notice.Text = ex.Message; } }
    void ImportNodesFileClick(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFileDialog { Filter = "订阅 / 节点文件|*.txt;*.json;*.yaml;*.yml|所有文件|*.*" };
        if (dialog.ShowDialog() == true) { try { MergeNodes(NodeParser.ParseSubscription(File.ReadAllText(dialog.FileName))); } catch (Exception ex) { Notice.Text = ex.Message; } }
    }
    void DeleteNodeClick(object sender, RoutedEventArgs e)
    {
        if (busy || core.Running) { Notice.Text = "请停止连接后删除节点"; return; }
        if (NodesList.SelectedItem is NodeRecord node) { preferences.Nodes.Remove(node); preferences.SelectedNode = 0; RefreshNodes(); SaveSettings(false); }
    }
    void ChooseConfigClick(object sender, RoutedEventArgs e)
    {
        if (!Editable()) return;
        var dialog = new Microsoft.Win32.OpenFileDialog { Filter = "代理配置|*.json;*.yaml;*.yml|所有文件|*.*" };
        if (dialog.ShowDialog() == true) try {
            var text = File.ReadAllText(dialog.FileName); var engine = ProfileImport.DetectCore(text);
            var profile = new ConfigProfile { Name = Path.GetFileNameWithoutExtension(dialog.FileName), Path = dialog.FileName, Core = engine };
            preferences.Configs.Add(profile); UseConfig(profile); RefreshProfiles();
        } catch (Exception ex) { Notice.Text = ex.Message; }
    }
    async Task ImportSubscriptionAsync(string url)
    {
        var text = await ProfileImport.FetchAsync(url, "clash.meta/1.19.33");
        var nodes = NodeParser.ParseSubscription(text);
        var selected = preferences.Nodes.ElementAtOrDefault(preferences.SelectedNode);
        preferences.Nodes.RemoveAll(n => n.SourceSubscription == url);
        foreach (var node in nodes) node.SourceSubscription = url;
        MergeNodes(nodes);
        if (selected != null) { var index = preferences.Nodes.FindIndex(n => n.SourceSubscription == selected.SourceSubscription && n.Name == selected.Name && n.Server == selected.Server); if (index >= 0) preferences.SelectedNode = index; }
        if (!preferences.Subscriptions.Contains(url)) preferences.Subscriptions.Add(url);
        SavedSubscriptionsBox.ItemsSource = preferences.Subscriptions.ToArray(); RefreshFilter(); RefreshNodes(); SaveSettings(false);
    }
    async void SubscriptionClick(object sender, RoutedEventArgs e)
    {
        if (!Editable()) return; busy = true;
        try { Notice.Text = "正在获取节点订阅…"; await ImportSubscriptionAsync(SubscriptionBox.Text.Trim()); }
        catch (Exception ex) { Notice.Text = "订阅导入失败：" + ex.Message; }
        finally { busy = false; }
    }
    async void UpdateAllSubscriptionsClick(object sender, RoutedEventArgs e)
    {
        if (!Editable()) return; busy = true; int ok = 0; var errors = new List<string>();
        try { foreach (var url in preferences.Subscriptions.ToArray()) { try { await ImportSubscriptionAsync(url); ok++; } catch (Exception ex) { errors.Add(ex.Message); } } Notice.Text = $"更新完成：{ok} 成功，{errors.Count} 失败" + (errors.Count > 0 ? " · " + errors[0] : ""); }
        finally { busy = false; }
    }
    void RemoveSubscriptionClick(object sender, RoutedEventArgs e)
    {
        if (!Editable() || SavedSubscriptionsBox.SelectedItem is not string url) return;
        preferences.Subscriptions.Remove(url); preferences.Nodes.RemoveAll(n => n.SourceSubscription == url);
        SavedSubscriptionsBox.ItemsSource = preferences.Subscriptions.ToArray(); RefreshFilter(); RefreshNodes(); SaveSettings(false);
    }
    bool Editable() { if (busy || core.Running) { Notice.Text = "请先断开连接，或等待当前操作完成"; return false; } return true; }
    void UseConfig(ConfigProfile profile) { preferences.ConfigPath = profile.Path; if (preferences.Mode == "config") preferences.Core = profile.Core; SaveSettings(false); }
    void RefreshProfiles() { refreshingNodes = true; ConfigsList.ItemsSource = preferences.Configs.ToArray(); ConfigsList.SelectedItem = preferences.Configs.FirstOrDefault(c => c.Path == preferences.ConfigPath); refreshingNodes = false; }
    void ConfigSelected(object sender, SelectionChangedEventArgs e) { if (!initialized || refreshingNodes || ConfigsList.SelectedItem is not ConfigProfile p) return; if (!Editable()) { RefreshProfiles(); return; } UseConfig(p); RemoteConfigUrl.Text = p.Url; }
    async Task FetchConfigAsync(ConfigProfile? existing = null)
    {
        if (!Editable()) return; busy = true;
        try { var url = existing?.Url ?? RemoteConfigUrl.Text.Trim(); Notice.Text = "正在获取远程配置…"; var text = await ProfileImport.FetchAsync(url); var profile = ProfileImport.SaveConfig(preferences, text, existing?.Name ?? new Uri(url).Host, url, existing); UseConfig(profile); RefreshProfiles(); Notice.Text = $"已保存 {profile.Core} 远程配置 · {profile.Name}"; }
        catch (Exception ex) { Notice.Text = "配置获取失败，原配置已保留：" + ex.Message; }
        finally { busy = false; }
    }
    async void RemoteConfigClick(object sender, RoutedEventArgs e) => await FetchConfigAsync();
    async void UpdateConfigClick(object sender, RoutedEventArgs e) { if (ConfigsList.SelectedItem is ConfigProfile p && p.Url.Length > 0) await FetchConfigAsync(p); else Notice.Text = "请选择远程配置"; }
    void RemoveConfigClick(object sender, RoutedEventArgs e) { if (!Editable() || ConfigsList.SelectedItem is not ConfigProfile p) return; preferences.Configs.Remove(p); if (preferences.ConfigPath == p.Path) { preferences.ConfigPath = preferences.Configs.FirstOrDefault()?.Path ?? ""; if (preferences.Configs.Count > 0) UseConfig(preferences.Configs[0]); } RefreshProfiles(); SaveSettings(false); }
    void RefreshFilter() { refreshingNodes = true; var index = NodeFilter.SelectedIndex; NodeFilter.ItemsSource = new[] { "全部节点", "手动导入" }.Concat(preferences.Subscriptions.Select(u => new Uri(u).Host + " · " + (preferences.Subscriptions.IndexOf(u) + 1))).ToArray(); NodeFilter.SelectedIndex = Math.Clamp(index, 0, NodeFilter.Items.Count - 1); refreshingNodes = false; }
    void NodeFilterChanged(object sender, SelectionChangedEventArgs e) { if (initialized && !refreshingNodes) RefreshNodes(); }
    void NodeSearchChanged(object sender, TextChangedEventArgs e) { if (initialized) RefreshNodes(); }
    void ShowImportClick(object sender, RoutedEventArgs e)
    {
        if (!Editable()) return;
        var panel = new StackPanel { Margin = new Thickness(24) };
        panel.Children.Add(new TextBlock { Text = "导入分享链接", FontSize = 20, Margin = new Thickness(0,0,0,16) });
        panel.Children.Add(new TextBlock { Text = "SS / VMess / VLESS / Trojan / Hysteria / Hysteria2 / TUIC / AnyTLS / SOCKS / HTTP / WireGuard / NaiveProxy", TextWrapping = TextWrapping.Wrap, Foreground = (Brush)FindResource("Muted"), Margin = new Thickness(0,0,0,16) });
        ImportText.Height = 140; ImportText.AcceptsReturn = true; ImportText.VerticalScrollBarVisibility = ScrollBarVisibility.Auto; panel.Children.Add(ImportText);
        var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0,20,0,0) }; panel.Children.Add(row);
        var dialog = new Window { Title = "Wbox · 添加节点", Owner = this, Content = panel, Width = 610, SizeToContent = SizeToContent.Height, WindowStartupLocation = WindowStartupLocation.CenterOwner, ResizeMode = ResizeMode.NoResize };
        var add = new Button { Content = "导入" }; add.Click += (_, _) => { try { MergeNodes(NodeParser.ParseSubscription(ImportText.Text)); ImportText.Clear(); dialog.Close(); } catch (Exception ex) { Notice.Text = ex.Message; } }; row.Children.Add(add);
        var clipboard = new Button { Content = "粘贴" }; clipboard.Click += (_, _) => { if (System.Windows.Clipboard.ContainsText()) ImportText.Text = System.Windows.Clipboard.GetText(); }; row.Children.Add(clipboard);
        var file = new Button { Content = "从文件导入" }; file.Click += (s,e) => { ImportNodesFileClick(s,e); dialog.Close(); }; row.Children.Add(file);
        dialog.Closed += (_, _) => panel.Children.Remove(ImportText); dialog.ShowDialog();
    }
    async void DelayAllClick(object sender, RoutedEventArgs e)
    {
        if (busy) { cancelTests = true; return; } cancelTests = false; var selected = NodesList.Items.Cast<NodeRecord>().ToArray(); if (selected.Length == 0) return;
        busy = true; StartButton.IsEnabled = false; DelayAllButton.Content = "停止测试"; var temporary = !core.Running;
        try {
            SaveSettings(false); if (temporary) { var p = JsonSerializer.Deserialize<Preferences>(JsonSerializer.Serialize(preferences))!; p.SystemProxy = false; p.Mode = "nodes"; await core.StartAsync(p); }
            foreach (var n in selected) { if (cancelTests) break; if (!NodeOptions.Compatible(n, preferences.Core)) { n.DelayText = "内核不兼容"; continue; } Notice.Text = "测试 " + n.Name; try { n.DelayText = await core.DelayAsync(ConfigBuilder.Tag(n, preferences.Nodes.IndexOf(n)), preferences.DelayUrl) + " ms"; } catch { n.DelayText = "超时 / 失败"; } NodesList.Items.Refresh(); }
            Notice.Text = cancelTests ? "已停止测试" : $"已测试 {selected.Length} 个节点";
        } catch (Exception ex) { Notice.Text = ex.Message; }
        finally { if (temporary) core.Stop(); busy = false; StartButton.IsEnabled = true; DelayAllButton.Content = "测试全部"; }
    }
    void UpdateWorkspace()
    {
        bool config = preferences.Mode == "config";
        NodeWorkspace.Visibility = SubscriptionWorkspace.Visibility = config ? Visibility.Collapsed : Visibility.Visible;
        GroupWorkspace.Visibility = ConfigWorkspace.Visibility = config ? Visibility.Visible : Visibility.Collapsed;
        NodesTab.Header = NodesNav.Content = config ? "代理组" : "节点"; ProfilesTab.Header = ProfilesNav.Content = config ? "配置" : "订阅";
        PageTitle.Text = ((TabItem)MainTabs.SelectedItem).Header.ToString(); PageCaption.Text = config ? "配置工作区" : "节点工作区";
    }
    void SubscriptionSelected(object sender, SelectionChangedEventArgs e) { if (SavedSubscriptionsBox.SelectedItem is string url) SubscriptionBox.Text = url; }
    void SaveSettingsClick(object sender, RoutedEventArgs e) => SaveSettings(true);
    void ClearLogsClick(object sender, RoutedEventArgs e) => logs.Clear();
    void CopyLogsClick(object sender, RoutedEventArgs e) { System.Windows.Clipboard.SetText(string.Join(Environment.NewLine, logs)); Notice.Text = "日志已复制"; }
    void OpenDataClick(object sender, RoutedEventArgs e) => Process.Start(new ProcessStartInfo("explorer.exe", Store.DirectoryPath) { UseShellExecute = true });
    void ExitClick(object sender, RoutedEventArgs e) => ExitApp();
    void NavigateClick(object sender, RoutedEventArgs e) => MainTabs.SelectedIndex = NavigationPanel.Children.IndexOf((UIElement)sender);
    void OpenProfilesClick(object sender, RoutedEventArgs e) => MainTabs.SelectedIndex = preferences.Mode == "config" ? 2 : 1;
    void PageChanged(object sender, SelectionChangedEventArgs e)
    {
        if (e.Source != MainTabs || MainTabs.SelectedIndex < 0) return;
        var index = MainTabs.SelectedIndex;
        PageTitle.Text = ((TabItem)MainTabs.SelectedItem).Header.ToString(); PageCaption.Text = preferences.Mode == "config" ? "配置工作区" : "节点工作区";
        if (index != 3) { currentConnections.Clear(); ConnectionsList.ItemsSource = null; }
        ((RadioButton)NavigationPanel.Children[index]).IsChecked = true;
    }
    void UpdateSummary()
    {
        SidebarCore.Text = preferences.Core;
        NativeInbound.IsEnabled = preferences.Mode == "config" && !core.Running && !busy;
        ManagedInbound.IsEnabled = !core.Running && !busy;
        InboundHint.Text = preferences.NativeInbounds && preferences.Mode == "config" ? "保留原配置所有入站；TUN 需要管理员权限" : "使用本地混合入站；配置中的 DNS、路由和代理组仍生效";
        ModeBadge.Text = preferences.Mode == "config" ? "配置工作区" : "节点工作区";
        ConnectionHint.Text = preferences.Mode == "config"
            ? (string.IsNullOrEmpty(preferences.ConfigPath) ? "选择一个 JSON 或 YAML 配置，即可开始。" : preferences.Configs.FirstOrDefault(c => c.Path == preferences.ConfigPath)?.Name ?? Path.GetFileName(preferences.ConfigPath))
            : preferences.Nodes.Count == 0 ? "导入节点或配置，开启你的第一次连接。" : preferences.Nodes[Math.Clamp(preferences.SelectedNode, 0, preferences.Nodes.Count - 1)].Name;
        OverviewFootnote.Text = preferences.Mode == "config" ? "使用原生配置中的 DNS、路由与 IPv6 设置" : (preferences.SystemProxy ? "系统代理" : "本地代理") + " · 127.0.0.1:" + core.ProxyPort + " · IPv6 已启用";
    }
    void ApplyConnectionFilter() {
        var query = ConnectionSearch.Text.Trim();
        var rows = currentConnections.Where(c => query.Length == 0 || $"{c.Source} {c.Destination} {c.Inbound} {c.Outbound} {c.Rule} {c.Network}".Contains(query,StringComparison.OrdinalIgnoreCase)).ToArray();
        ConnectionsList.ItemsSource = rows; ConnectionsEmpty.Text = query.Length > 0 ? "没有匹配的连接" : "暂无活动连接"; ConnectionsEmpty.Visibility = rows.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
    }
    void ConnectionSearchChanged(object sender, TextChangedEventArgs e) { if (initialized) ApplyConnectionFilter(); }
    void LogSearchChanged(object sender, TextChangedEventArgs e) { if (initialized) System.Windows.Data.CollectionViewSource.GetDefaultView(logs).Refresh(); }
    void PauseLogsClick(object sender, RoutedEventArgs e) { logsPaused = !logsPaused; PauseLogsButton.Content = logsPaused ? "继续" : "暂停"; LogsList.ItemsSource = logsPaused ? logs.Where(l => l.Contains(LogSearch.Text,StringComparison.OrdinalIgnoreCase)).ToArray() : logs; }
    void InboundModeChanged(object sender, RoutedEventArgs e) {
        if (!initialized) return;
        if (busy || core.Running) return;
        preferences.NativeInbounds = NativeInbound.IsChecked == true;
        SaveSettings(false); UpdateSummary();
    }
    void SettingsOpenClick(object sender, RoutedEventArgs e) {
        var key = ((Button)sender).Tag?.ToString(); SettingsHome.Visibility = Visibility.Collapsed; SettingsDetail.Visibility = Visibility.Visible;
        AppSettings.Visibility = key == "app" ? Visibility.Visible : Visibility.Collapsed;
        CoreSettings.Visibility = key == "core" ? Visibility.Visible : Visibility.Collapsed;
        TestSettings.Visibility = key == "test" ? Visibility.Visible : Visibility.Collapsed;
    }
    void SettingsBackClick(object sender, RoutedEventArgs e) { SettingsHome.Visibility = Visibility.Visible; SettingsDetail.Visibility = Visibility.Collapsed; }
    void RestartElevatedClick(object sender, RoutedEventArgs e) {
        if (StartupPolicy.Elevated) { Notice.Text = "当前已具有管理员权限"; return; }
        if (busy || core.Running) { Notice.Text = "请断开后重启"; return; }
        SaveSettings(false);
        try { Process.Start(new ProcessStartInfo(Environment.ProcessPath!) { UseShellExecute = true, Verb = "runas", Arguments = "--show --wait-for " + Environment.ProcessId }); ExitApp(); }
        catch (Exception ex) { Notice.Text = "未重启：" + ex.Message; }
    }
    void TitleDrag(object sender, System.Windows.Input.MouseButtonEventArgs e) { if (e.ClickCount == 2) MaximizeClick(sender,e); else DragMove(); }
    void MinimizeClick(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    void MaximizeClick(object sender, RoutedEventArgs e) => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
    void CloseClick(object sender, RoutedEventArgs e) => Close();

}

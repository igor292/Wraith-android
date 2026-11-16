using Microsoft.Win32;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace Wraith.Windows
{
    public partial class MainWindow : Window
    {
        private readonly Store store;
        private readonly CoreHost core;
        private readonly DispatcherTimer stateTimer = new DispatcherTimer();
        private string page = "Главная";
        private string settingsPage = "";
        private string filter = "";
        private bool busy;
        private readonly SolidColorBrush MintBrush = new SolidColorBrush(Color.FromRgb(116, 228, 208));
        private readonly System.Windows.Forms.NotifyIcon trayIcon = new System.Windows.Forms.NotifyIcon();
        private bool exitRequested;

        public MainWindow()
        {
            InitializeComponent();
            store = new Store(); core = new CoreHost(store); core.Output += line => { logsDirty = true; };
            trayIcon.Icon = System.Drawing.SystemIcons.Application; trayIcon.Text = "Wraith";
            trayIcon.DoubleClick += (s, e) => Dispatcher.BeginInvoke(new Action(() => { Show(); WindowState = WindowState.Normal; Activate(); trayIcon.Visible = false; }));
            var trayMenu = new System.Windows.Forms.ContextMenuStrip();
            trayMenu.Items.Add("Открыть Wraith", null, (s, e) => Dispatcher.BeginInvoke(new Action(() => { Show(); WindowState = WindowState.Normal; Activate(); trayIcon.Visible = false; })));
            trayMenu.Items.Add("Выход", null, (s, e) => Dispatcher.BeginInvoke(new Action(() => { exitRequested = true; trayIcon.Visible = false; Close(); })));
            trayIcon.ContextMenuStrip = trayMenu;
            ApplyAutoStart(store.GetBool("autoStart", false));
            Closing += (s, e) => { if (!exitRequested && store.GetBool("minimizeToTray", true)) { e.Cancel = true; Hide(); trayIcon.Visible = true; return; } if (core.Running) { try { core.Stop(); } catch { } } trayIcon.Visible = false; trayIcon.Dispose(); };
            Navigation.Children.Add(NavButton("⌂", "Главная", () => Navigate("Главная")));
            Navigation.Children.Add(NavButton("⚙", "Настройки", () => Navigate("Настройки")));
            Navigation.Children.Add(NavButton("☷", "Логи", () => Navigate("Логи")));
            stateTimer.Interval = TimeSpan.FromSeconds(1); stateTimer.Tick += (s, e) => { UpdateConnectionState(); RefreshLogsIfDirty(); }; stateTimer.Start();
            VersionLabel.Text = "Wraith · Windows · " + System.Reflection.Assembly.GetExecutingAssembly().GetName().Version;
            Navigate("Главная");
            bool connectOnStart = Environment.GetCommandLineArgs().Contains("--connect-on-start");
            Loaded += async (s, e) => { if (store.GetBool("updateOnOpen", true) && store.State.Subscriptions.Count > 0) { if (connectOnStart) await UpdateAllAsync(); else UpdateAll(); } if (connectOnStart) await ToggleConnection(); };
        }

        private Button NavButton(string glyph, string title, Action action)
        {
            var b = new Button { Content = glyph + "   " + title, HorizontalContentAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 4, 0, 4), Background = title == page ? (Brush)FindResource("Card") : Brushes.Transparent, Foreground = title == page ? (Brush)FindResource("Text") : (Brush)FindResource("Muted"), Padding = new Thickness(15, 14, 12, 14) };
            b.Click += (s, e) => action(); return b;
        }
        private void Navigate(string target)
        {
            page = target; settingsPage = ""; PageTitle.Text = target;
            for (int i = 0; i < Navigation.Children.Count; i++)
            {
                var b = (Button)Navigation.Children[i]; bool active = ((string)b.Content).Contains(target);
                b.Background = active ? (Brush)FindResource("Card") : Brushes.Transparent; b.Foreground = active ? (Brush)FindResource("Text") : (Brush)FindResource("Muted");
            }
            PageContent.Children.Clear(); if (target == "Главная") ShowHome(); else if (target == "Настройки") ShowSettings(); else ShowLogs(); UpdateConnectionState();
        }
        private void AddPageTitle(string title, string subtitle)
        {
            PageContent.Children.Add(new TextBlock { Text = title, FontSize = 26, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 2, 0, 5) });
            PageContent.Children.Add(new TextBlock { Text = subtitle, Foreground = (Brush)FindResource("Muted"), FontSize = 13, Margin = new Thickness(0, 0, 0, 20) });
        }
        private Border Card(UIElement child, Thickness? padding = null)
        {
            var border = new Border { Background = (Brush)FindResource("Panel"), BorderBrush = (Brush)FindResource("Line"), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(20), Padding = padding ?? new Thickness(20), Margin = new Thickness(0, 0, 0, 14), Child = child };
            return border;
        }
        private StackPanel Column() { return new StackPanel(); }
        private StackPanel Row() { return new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center }; }
        private TextBlock Label(string text, double size = 14, Brush brush = null, bool bold = false)
        {
            return new TextBlock { Text = text, FontSize = size, Foreground = brush ?? (Brush)FindResource("Text"), FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center };
        }
        private Button ActionButton(string text, Action action, bool primary = false)
        {
            var b = new Button { Content = text, Background = primary ? (Brush)FindResource("Mint") : (Brush)FindResource("Card"), Foreground = primary ? new SolidColorBrush(Color.FromRgb(8, 18, 17)) : (Brush)FindResource("Text"), Margin = new Thickness(0, 0, 9, 0), MinWidth = 120 };
            b.Click += (s, e) => action(); return b;
        }
        /** Redraws the home page only when it is the visible page. */
        private void RefreshHome() { if (page == "Главная") ShowHome(); }
        private void ShowHome()
        {
            // ShowHome used to append a second copy of the page on every server click, rename,
            // favourite, ping or delete, because only Navigate() cleared the content.
            PageContent.Children.Clear(); HostCards = null;
            AddPageTitle("Главная", store.Selected == null ? "Добавьте сервер или HTTPS-подписку" : "Ваше подключение Wraith");
            var hero = Column();
            var top = Row(); var indicator = new Border { Width = 13, Height = 13, CornerRadius = new CornerRadius(8), Background = core.Running ? (Brush)FindResource("Mint") : (Brush)FindResource("Muted"), Margin = new Thickness(0, 0, 10, 0) }; top.Children.Add(indicator);
            top.Children.Add(Label(core.Running ? "Подключено" : "Готов к подключению", 19, null, true));
            var connected = store.Selected == null ? "Сначала добавьте сервер" : store.Selected.Name + " · " + ProtocolSummary(store.Selected);
            hero.Children.Add(top); hero.Children.Add(Label(connected, 13, (Brush)FindResource("Muted"))); 
            var connect = new Button { Content = core.Running ? "Отключить" : "Подключиться", Height = 54, MinWidth = 200, Margin = new Thickness(0, 22, 0, 0), HorizontalAlignment = HorizontalAlignment.Left, FontSize = 16, FontWeight = FontWeights.SemiBold, Background = core.Running ? (Brush)FindResource("Card") : (Brush)FindResource("Mint"), Foreground = core.Running ? (Brush)FindResource("Text") : new SolidColorBrush(Color.FromRgb(8, 18, 17)) };
            connect.Click += async (s, e) => await ToggleConnection(); hero.Children.Add(connect); PageContent.Children.Add(Card(hero, new Thickness(24)));
            var actions = Row(); actions.Children.Add(ActionButton("⌁  Пинг серверов", () => PingAll())); actions.Children.Add(ActionButton("⟳  Обновить подписки", () => UpdateAll())); actions.Children.Add(ActionButton("＋  Добавить подключение", AddDialog, true)); PageContent.Children.Add(actions); Space(20);
            var search = new TextBox { Text = filter, Height = 44, Margin = new Thickness(0, 0, 0, 16), ToolTip = "Поиск хостов" }; search.TextChanged += (s, e) => { filter = search.Text; RefreshHostCards(); }; PageContent.Children.Add(search);
            HostCards = new StackPanel(); PageContent.Children.Add(HostCards); DrawHostCards();
        }
        private StackPanel HostCards;
        private void RefreshHostCards() { if (HostCards != null) DrawHostCards(); }
        private void DrawHostCards()
        {
            if (HostCards == null) return; HostCards.Children.Clear();
            foreach (var sub in store.State.Subscriptions)
            {
                var profiles = store.State.Profiles.Where(p => p.Group == sub.Id).ToList(); if (!String.IsNullOrEmpty(filter)) profiles = profiles.Where(p => (p.Name + " " + p.Group).IndexOf(filter, StringComparison.OrdinalIgnoreCase) >= 0).ToList();
                if (profiles.Count == 0 && !String.IsNullOrEmpty(filter)) continue;
                var group = Column(); var head = Row(); var name = Label("⌄   " + sub.Name, 16, null, true); head.Children.Add(name); head.Children.Add(ActionButton("⟳", () => UpdateSubscription(sub))); head.Children.Add(ActionButton("⌁", () => PingAll(sub.Id))); head.Children.Add(ActionButton("⋯", () => SubscriptionMenu(sub))); group.Children.Add(head);
                group.Children.Add(Label(sub.Updated == DateTime.MinValue ? sub.Url : "Обновлено " + sub.Updated.ToString("dd.MM HH:mm"), 11, (Brush)FindResource("Muted")));
                if (!String.IsNullOrEmpty(sub.Error)) group.Children.Add(Label("Ошибка обновления: " + sub.Error, 12, Brushes.IndianRed));
                foreach (var profile in profiles) group.Children.Add(ProfileCard(profile));
                HostCards.Children.Add(Card(group, new Thickness(18)));
            }
            var standalone = store.State.Profiles.Where(p => String.IsNullOrEmpty(p.Group)).ToList();
            if (!String.IsNullOrEmpty(filter)) standalone = standalone.Where(p => p.Name.IndexOf(filter, StringComparison.OrdinalIgnoreCase) >= 0).ToList();
            if (standalone.Count > 0)
            {
                var group = Column(); group.Children.Add(Label("СЕРВЕРЫ", 12, (Brush)FindResource("Muted"), true)); foreach (var p in standalone) group.Children.Add(ProfileCard(p)); HostCards.Children.Add(Card(group, new Thickness(18)));
            }
            if (store.State.Profiles.Count == 0) { var empty = Column(); empty.Children.Add(Label("Нет серверов", 19, null, true)); empty.Children.Add(Label("Добавьте ссылку, JSON-конфигурацию или подписку. Настройки туннеля и журнал доступны в панели слева.", 13, (Brush)FindResource("Muted"))); empty.Children.Add(ActionButton("＋  Добавить сервер или подписку", AddDialog, true)); HostCards.Children.Add(Card(empty, new Thickness(24))); }
        }
        private UIElement ProfileCard(Profile profile)
        {
            var outer = new Border { CornerRadius = new CornerRadius(15), Background = profile.Id == store.State.Selected ? (Brush)FindResource("Card") : Brushes.Transparent, Padding = new Thickness(14, 12, 12, 12), Margin = new Thickness(0, 8, 0, 0), Cursor = Cursors.Hand };
            var grid = new Grid(); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(36) }); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var flag = Label(ProfileGlyph(profile.Name), 20, profile.Id == store.State.Selected ? (Brush)FindResource("Mint") : (Brush)FindResource("Muted")); Grid.SetColumn(flag, 0); grid.Children.Add(flag);
            var labels = Column(); labels.Children.Add(Label(profile.Name, 15, null, true)); string status = profile.PingError.Length > 0 ? profile.PingError : profile.Latency >= 0 ? profile.Latency + " мс" : ""; labels.Children.Add(Label(ProtocolSummary(profile) + (status.Length > 0 ? " · " + status : ""), 11, profile.PingError.Length > 0 ? Brushes.IndianRed : (Brush)FindResource("Muted"))); Grid.SetColumn(labels, 1); grid.Children.Add(labels);
            var more = new Button { Content = "⋯", Padding = new Thickness(12, 5, 12, 5), Background = Brushes.Transparent, Foreground = (Brush)FindResource("Muted"), MinWidth = 42 }; more.Click += (s, e) => { e.Handled = true; ProfileMenu(profile); }; Grid.SetColumn(more, 2); grid.Children.Add(more);
            outer.Child = grid; outer.MouseLeftButtonUp += (s, e) => { if (e.OriginalSource is Border || e.OriginalSource is TextBlock) SelectProfile(profile); }; outer.MouseEnter += (s, e) => { if (profile.Id != store.State.Selected) outer.Background = (Brush)FindResource("Card"); }; outer.MouseLeave += (s, e) => { if (profile.Id != store.State.Selected) outer.Background = Brushes.Transparent; }; return outer;
        }
        private string ProfileGlyph(string name) { string n = name.ToLowerInvariant(); if (n.Contains("german") || n.Contains("герман")) return "🇩🇪"; if (n.Contains("nether") || n.Contains("нидер")) return "🇳🇱"; if (n.Contains("finland") || n.Contains("финл")) return "🇫🇮"; if (n.Contains("belg") || n.Contains("бельг")) return "🇧🇪"; return "◆"; }
        private string ProtocolSummary(Profile p)
        {
            if (p.Full) return "Полный JSON";
            var o = p.Outbound; string type = JsonUtil.Str(o, "type", "proxy").ToUpperInvariant(); var transport = JsonUtil.Obj(o.ContainsKey("transport") ? o["transport"] : null); string net = JsonUtil.Str(transport, "type", "tcp").ToUpperInvariant(); var tls = JsonUtil.Obj(o.ContainsKey("tls") ? o["tls"] : null); string security = JsonUtil.Bool(tls, "enabled") ? (tls.ContainsKey("reality") ? "REALITY" : "TLS") : ""; return String.Join(" / ", new[] { type, net, security }.Where(x => x.Length > 0));
        }
        private async void SelectProfile(Profile p) { if (busy || p.Id == store.State.Selected) return; store.State.Selected = p.Id; store.Save(); if (core.Running) { await RestartForProfile(p); } else { RefreshHome(); } }
        private async Task RestartForProfile(Profile p)
        {
            busy = true; UpdateConnectionState();
            try { await Task.Run(() => { core.Stop(); core.Start(p); }); Notice("Сервер переключён: " + p.Name); }
            catch (Exception ex) { Notice(ex.Message, true); }
            finally { busy = false; UpdateConnectionState(); RefreshHome(); }
        }
        private async Task ToggleConnection()
        {
            if (busy) { Notice("Дождитесь окончания текущей операции"); return; }
            if (core.Running) { try { core.Stop(); Notice("Подключение отключено"); } catch (Exception ex) { Notice(ex.Message, true); } UpdateConnectionState(); RefreshHome(); return; }
            if (store.Selected == null) { AddDialog(); return; }
            if (store.State.TunnelMode == "TUN" && !IsAdministrator()) { LaunchElevatedForTun(); return; }
            busy = true; CoreState.Text = "Запускаем sing-box…";
            var selected = store.Selected;
            try { await Task.Run(() => core.Start(selected)); Notice("Подключено к «" + selected.Name + "»"); }
            catch (Exception ex) { Notice(ex.Message, true); }
            finally { busy = false; UpdateConnectionState(); RefreshHome(); }
        }
        private bool lastRunning;
        private void UpdateConnectionState()
        {
            // The core died on its own: restore the system proxy (otherwise every browser points at
            // a dead local port and "the internet is gone") and tell the user why.
            if (!busy && core.ExitedUnexpectedly)
            {
                try { core.Stop(); } catch { }
                Notice("Ядро sing-box остановилось. Подробности во вкладке «Логи».", true);
            }
            bool running = core.Running;
            CoreState.Text = busy ? "Выполняется…" : running ? "●  Подключено" : "○  Отключено"; CoreState.Foreground = running ? (Brush)FindResource("Mint") : (Brush)FindResource("Muted");
            trayIcon.Text = running && store.Selected != null ? Trim("Wraith · " + store.Selected.Name, 63) : "Wraith";
            if (running != lastRunning && !busy) { lastRunning = running; RefreshHome(); }
        }
        private static string Trim(string value, int max) { return value.Length > max ? value.Substring(0, max - 1) + "…" : value; }
        private async void PingAll(string group = null)
        {
            var targets = store.State.Profiles.Where(p => group == null || p.Group == group).ToList(); if (targets.Count == 0) { Notice("Нет серверов для проверки"); return; }
            if (busy) { Notice("Дождитесь окончания текущей операции"); return; }
            busy = true; int timeout = store.GetInt("pingTimeout", 5000); string pingType = store.GetString("pingType", "proxyGet"); string pingUrl = store.GetString("pingUrl", "https://www.gstatic.com/generate_204"); foreach (var p in targets) { p.Latency = -1; p.PingError = "Проверяем…"; } UpdateConnectionState(); RefreshHome();
            try { await Task.Run(() => Parallel.ForEach(targets, new ParallelOptions { MaxDegreeOfParallelism = 3 }, p => { try { p.Latency = core.Ping(p, timeout, pingType, pingUrl); p.PingError = ""; } catch (Exception ex) { p.Latency = -1; p.PingError = ex.Message; } })); }
            finally { store.Save(); busy = false; }
            int ok = targets.Count(p => p.Latency >= 0); RefreshHome(); Notice("Проверка завершена: отвечают " + ok + " из " + targets.Count); UpdateConnectionState();
        }
        private void PingAll() { PingAll((string)null); }

        private void ProfileMenu(Profile p)
        {
            var menu = new ContextMenu(); AddMenu(menu, "Проверить задержку", () => PingOne(p)); AddMenu(menu, p.Favorite ? "Убрать из избранного" : "В избранное", () => { p.Favorite = !p.Favorite; store.Save(); RefreshHome(); }); AddMenu(menu, "Переименовать", () => RenameProfile(p)); AddMenu(menu, "Редактировать JSON", () => EditJson(p)); AddMenu(menu, "Скопировать JSON", () => Clipboard.SetText(p.JsonSource.Length > 0 ? p.JsonSource : JsonUtil.Print(p.Outbound))); AddMenu(menu, "Удалить", () => DeleteProfile(p)); menu.IsOpen = true;
        }
        private void SubscriptionMenu(Subscription sub)
        {
            var menu = new ContextMenu(); AddMenu(menu, "Изменить", () => EditSubscription(sub)); AddMenu(menu, "Обновить", () => UpdateSubscription(sub)); AddMenu(menu, "Удалить", () => DeleteSubscription(sub)); menu.IsOpen = true;
        }
        private void AddMenu(ContextMenu menu, string title, Action action) { var item = new MenuItem { Header = title }; item.Click += (s, e) => action(); menu.Items.Add(item); }
        private async void PingOne(Profile profile)
        {
            profile.PingError = "Проверяем…"; RefreshHome(); int timeout = store.GetInt("pingTimeout", 5000); string pingType = store.GetString("pingType", "proxyGet"); string pingUrl = store.GetString("pingUrl", "https://www.gstatic.com/generate_204"); await Task.Run(() => { try { profile.Latency = core.Ping(profile, timeout, pingType, pingUrl); profile.PingError = ""; } catch (Exception ex) { profile.Latency = -1; profile.PingError = ex.Message; } }); store.Save(); RefreshHome();
        }
        private void Add_Click(object sender, RoutedEventArgs e) { AddDialog(); }
        private void AddDialog()
        {
            // The clipboard can be locked by another application; that must not crash the window.
            string clip = ""; try { if (Clipboard.ContainsText()) clip = Clipboard.GetText().Trim(); } catch { }
            bool looksImportable = clip.StartsWith("https://") || clip.Contains("://") && clip.Length < 64 * 1024 || clip.StartsWith("{");
            var box = new TextBox { AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, MinHeight = 190, Text = looksImportable ? clip : "", Margin = new Thickness(0, 12, 0, 14) };
            var pane = new StackPanel(); pane.Children.Add(Label("Вставьте vless://, ссылку подписки или sing-box / Xray JSON.", 13, (Brush)FindResource("Muted"))); pane.Children.Add(box);
            var win = Modal("Добавить подключение", pane, "Добавить", async () => await Import(box.Text), "Отмена"); win.ShowDialog();
        }
        private Window Modal(string title, UIElement content, string accept, Func<Task> callback, string cancel)
        {
            var w = new Window { Title = title, Owner = this, WindowStartupLocation = WindowStartupLocation.CenterOwner, Width = 620, Height = 490, MinWidth = 500, MinHeight = 370, Background = (Brush)FindResource("Bg"), Foreground = (Brush)FindResource("Text"), ResizeMode = ResizeMode.CanResize };
            var layout = new DockPanel { Margin = new Thickness(22) }; var header = Label(title, 21, null, true); DockPanel.SetDock(header, Dock.Top); layout.Children.Add(header);
            var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 12, 0, 0) }; var cancelButton = ActionButton(cancel, () => w.Close()); var acceptButton = ActionButton(accept, async () => { try { await callback(); if (w.IsVisible) w.Close(); } catch (Exception ex) { Notice(ex.Message, true); } }, true); buttons.Children.Add(cancelButton); buttons.Children.Add(acceptButton); DockPanel.SetDock(buttons, Dock.Bottom); layout.Children.Add(buttons); layout.Children.Add(content); w.Content = layout; return w;
        }
        private async Task Import(string text)
        {
            text = (text ?? "").Trim(); if (text.Length == 0) throw new InvalidOperationException("Вставьте ссылку или JSON.");
            if (text.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            {
                var sub = new Subscription { Url = text, Name = new Uri(text).Host }; store.State.Subscriptions.Add(sub); store.Save(); await Task.Run(() => UpdateSubscriptionWorker(sub)); Navigate("Главная"); return;
            }
            var parsed = ImportParser.Parse(text); foreach (var p in parsed) store.State.Profiles.Add(p); if (store.State.Selected.Length == 0 && parsed.Count > 0) store.State.Selected = parsed[0].Id; store.Save(); Navigate("Главная"); Notice("Добавлено серверов: " + parsed.Count);
        }
        private async void UpdateSubscription(Subscription sub)
        {
            if (busy) return; busy = true; UpdateConnectionState(); try { await Task.Run(() => UpdateSubscriptionWorker(sub)); Notice("Подписка обновлена: " + sub.Name); } catch (Exception ex) { Notice(ex.Message, true); } finally { busy = false; Navigate("Главная"); }
        }
        private void UpdateSubscriptionWorker(Subscription sub)
        {
            try
            {
                string ua = store.GetString("userAgent"); if (String.IsNullOrWhiteSpace(ua)) { string id = store.GetString("subscriptionDeviceId"); if (String.IsNullOrWhiteSpace(id)) { id = Guid.NewGuid().ToString("N").Substring(0, 20); store.Set("subscriptionDeviceId", id); } ua = "Happ/4.4.1/Android/" + id; }
                var fresh = ImportParser.Fetch(sub, ua); var previous = store.State.Profiles.Where(x => x.Group == sub.Id).ToList();
                foreach (var p in fresh)
                {
                    var match = previous.FirstOrDefault(old => ProfileIdentity(old) == ProfileIdentity(p));
                    if (match != null) { p.Id = match.Id; p.Favorite = match.Favorite; p.Name = match.Name; }
                    p.Group = sub.Id; store.State.Profiles.Add(p);
                }
                foreach (var p in previous) store.State.Profiles.Remove(p);
                sub.Updated = DateTime.Now; sub.Error = "";
                if (store.State.Profiles.All(p => p.Id != store.State.Selected) && fresh.Count > 0) store.State.Selected = fresh[0].Id; store.Save();
            }
            catch (Exception ex) { sub.Error = ex.Message; store.Save(); throw; }
        }
        private async void UpdateAll() { await UpdateAllAsync(); }
        private string ProfileIdentity(Profile p)
        {
            if (p.Full) return "full|" + p.Name + "|" + p.JsonSource;
            var o = p.Outbound; var tls = JsonUtil.Obj(o.ContainsKey("tls") ? o["tls"] : null); var reality = JsonUtil.Obj(tls.ContainsKey("reality") ? tls["reality"] : null); var transport = JsonUtil.Obj(o.ContainsKey("transport") ? o["transport"] : null);
            return String.Join("|", new[] { JsonUtil.Str(o, "type"), JsonUtil.Str(o, "server"), JsonUtil.Str(o, "server_port"), JsonUtil.Str(o, "uuid"), JsonUtil.Str(o, "password"), JsonUtil.Str(transport, "type"), JsonUtil.Str(transport, "service_name"), JsonUtil.Str(transport, "path"), JsonUtil.Str(tls, "server_name"), JsonUtil.Str(reality, "public_key"), JsonUtil.Str(reality, "short_id") });
        }
        private async Task UpdateAllAsync()
        {
            if (busy) return; busy = true; UpdateConnectionState(); var subscriptions = store.State.Subscriptions.ToList();
            await Task.Run(() => { foreach (var sub in subscriptions) try { UpdateSubscriptionWorker(sub); } catch { } }); busy = false; Navigate("Главная"); Notice("Подписки проверены");
        }
        private bool IsAdministrator() { var id = System.Security.Principal.WindowsIdentity.GetCurrent(); return new System.Security.Principal.WindowsPrincipal(id).IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator); }
        private void LaunchElevatedForTun()
        {
            try
            {
                string exe = System.Reflection.Assembly.GetExecutingAssembly().Location;
                Process.Start(new ProcessStartInfo(exe, "--connect-on-start") { UseShellExecute = true, Verb = "runas", WorkingDirectory = Path.GetDirectoryName(exe) });
                exitRequested = true; trayIcon.Visible = false; Close();
            }
            catch (System.ComponentModel.Win32Exception ex)
            {
                if (ex.NativeErrorCode == 1223) Notice("Запуск TUN отменён в окне контроля учётных записей."); else Notice("Не удалось запустить Wraith с правами администратора: " + ex.Message, true);
            }
        }
        private void EditSubscription(Subscription sub)
        {
            var form = Column(); var name = new TextBox { Text = sub.Name, Margin = new Thickness(0, 12, 0, 8) }; var url = new TextBox { Text = sub.Url, Margin = new Thickness(0, 0, 0, 10) }; form.Children.Add(Label("Название")); form.Children.Add(name); form.Children.Add(Label("HTTPS URL подписки")); form.Children.Add(url);
            Modal("Изменить подписку", form, "Сохранить", async () => { if (!url.Text.StartsWith("https://")) throw new InvalidOperationException("Нужен HTTPS URL."); sub.Name = name.Text.Trim(); sub.Url = url.Text.Trim(); store.Save(); await Task.CompletedTask; }, "Отмена").ShowDialog();
        }
        private void DeleteSubscription(Subscription sub)
        {
            if (MessageBox.Show(this, "Удалить подписку «" + sub.Name + "» и все её серверы?", "Удалить подписку", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
            store.State.Subscriptions.Remove(sub); foreach (var p in store.State.Profiles.Where(p => p.Group == sub.Id).ToList()) { if (store.State.Selected == p.Id) store.State.Selected = ""; store.State.Profiles.Remove(p); } store.Save(); RefreshHome();
        }
        private void RenameProfile(Profile p)
        {
            var tb = new TextBox { Text = p.Name, Margin = new Thickness(0, 12, 0, 0) }; Modal("Переименовать сервер", tb, "Сохранить", async () => { p.Name = tb.Text.Trim(); store.Save(); await Task.CompletedTask; RefreshHome(); }, "Отмена").ShowDialog();
        }
        private void DeleteProfile(Profile p)
        {
            if (MessageBox.Show(this, "Удалить сервер «" + p.Name + "»?", "Удалить сервер", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
            store.State.Profiles.Remove(p); if (store.State.Selected == p.Id) store.State.Selected = ""; store.Save(); RefreshHome();
        }
        private void EditJson(Profile p)
        {
            string initial = p.JsonSource.Length > 0 ? Pretty(p.JsonSource) : JsonUtil.Serializer.Serialize(p.Outbound);
            var w = new Window { Title = "Конфигурация сервера · " + p.Name, Owner = this, WindowStartupLocation = WindowStartupLocation.CenterOwner, WindowState = WindowState.Maximized, Background = (Brush)FindResource("Bg"), Foreground = (Brush)FindResource("Text") };
            var grid = new Grid { Margin = new Thickness(24) }; grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) }); grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var header = Label("‹   Конфигурация сервера · " + p.Name, 22, null, true); header.Cursor = Cursors.Hand; header.MouseLeftButtonUp += (s, e) => w.Close(); Grid.SetRow(header, 0); grid.Children.Add(header);
            var editor = new TextBox { Text = initial, AcceptsReturn = true, AcceptsTab = true, TextWrapping = TextWrapping.NoWrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, FontFamily = new FontFamily("Consolas"), FontSize = 14, Margin = new Thickness(0, 18, 0, 14), Padding = new Thickness(18), Background = (Brush)FindResource("Panel"), BorderBrush = (Brush)FindResource("Line") }; Grid.SetRow(editor, 1); grid.Children.Add(editor);
            var actions = Row(); actions.HorizontalAlignment = HorizontalAlignment.Right; var cancel = ActionButton("Отмена", () => w.Close()); var save = ActionButton("Сохранить", () => { try { var replacement = ImportParser.Parse(editor.Text); if (replacement.Count != 1) throw new InvalidOperationException("Вставьте конфигурацию ровно одного сервера."); var fresh = replacement[0]; fresh.Id = p.Id; fresh.Group = p.Group; fresh.Favorite = p.Favorite; int i = store.State.Profiles.IndexOf(p); store.State.Profiles[i] = fresh; store.Save(); w.Close(); RefreshHome(); Notice("Конфигурация сохранена"); } catch (Exception ex) { Notice(ex.Message, true); } }, true); actions.Children.Add(cancel); actions.Children.Add(save); Grid.SetRow(actions, 2); grid.Children.Add(actions); w.Content = grid; w.ShowDialog();
        }
        private string Pretty(string json) { try { return JsonUtil.Serializer.Serialize(JsonUtil.Serializer.DeserializeObject(json)); } catch { return json; } }

        private void ShowSettings()
        {
            // Opening a section, going back or flipping a toggle used to stack the new page under
            // the old one: nothing cleared PageContent here.
            if (settingsPage.Length > 0) { ShowSettingsDetail(); return; }
            PageContent.Children.Clear();
            AddPageTitle("Настройки", "Подключение, интерфейс и поведение Wraith");
            SettingCard("Настройки туннеля", "Режим подключения, DNS, IP, MTU, фрагментация, Mux", "tunnel");
            SettingCard("Маршрутизация", "Правила доменов, IP и приложений", "routing");
            SettingCard("Подписки", "User-Agent, обновление при запуске, интервалы", "subscriptions");
            SettingCard("Пинг", "Проверка доступности и тайм-аут", "ping");
            SettingCard("Интерфейс", "Тема, запуск и поведение приложения", "interface");
            SettingCard("Расширенные настройки", "Локальный прокси, LAN и диагностика", "advanced");
            SettingCard("Информация", "Версия, sing-box, FAQ", "info");
        }
        private void SettingCard(string title, string detail, string destination)
        {
            var row = new Grid(); row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); var texts = Column(); texts.Children.Add(Label(title, 16, null, true)); texts.Children.Add(Label(detail, 12, (Brush)FindResource("Muted"))); row.Children.Add(texts); var chevron = Label("›", 26, (Brush)FindResource("Muted")); Grid.SetColumn(chevron, 1); row.Children.Add(chevron); var border = Card(row, new Thickness(18)); border.Cursor = Cursors.Hand; border.MouseLeftButtonUp += (s, e) => { settingsPage = destination; ShowSettings(); }; PageContent.Children.Add(border);
        }
        private void ShowSettingsDetail()
        {
            PageContent.Children.Clear();
            var back =ActionButton("‹  Настройки", () => { settingsPage = ""; ShowSettings(); }); PageContent.Children.Add(back);
            switch (settingsPage)
            {
                case "tunnel":
                    AddPageTitle("Настройки туннеля", "Применяются при следующем подключении");
                    ComboSetting("Режим туннеля", "TUN", new[] { "TUN", "System Proxy" }, value => { store.State.TunnelMode = value; store.Save(); });
                    ToggleSetting("Строгая маршрутизация", "Всегда включена для защиты от обхода туннеля", "strictRoute", true, true);
                    ComboSetting("Предпочтительный тип IP", "ipPreference", new[] { "auto", "ipv4", "ipv6" }, s => SaveString("ipPreference", s));
                    ToggleSetting("Использовать DNS из JSON", "Сохранять DNS, присланный сервером", "dnsFromJson", true);
                    if (!store.GetBool("dnsFromJson", true)) ComboSetting("DNS туннеля", "dns", new[] { "Cloudflare DNS", "Google DNS", "Quad9" }, s => SaveString("dns", s));
                    NumberSetting("MTU туннеля", "mtu", 1280, 9000);
                    ToggleSetting("Фрагментация TLS", "Обход DPI через фрагментацию", "fragment", false);
                    if (store.GetBool("fragment", false))
                    {
                        ComboSetting("Тип фрагментации", "fragmentType", new[] { "xray", "singbox" }, s => SaveString("fragmentType", s));
                        if (store.GetString("fragmentType", "xray") == "xray") { TextSetting("Пакеты", "fragmentPackets", "tlshello или диапазон 1-3"); TextSetting("Длина (мин-макс)", "fragmentLength", "50-100"); TextSetting("Интервал (мин-макс)", "fragmentInterval", "10-20"); TextSetting("Максимальное разделение", "fragmentMaxSplit", "100-200 или пусто"); }
                        else NumberSetting("Задержка фрагментации, мс", "fragmentDelay", 0, 1000);
                        ToggleSetting("Фрагментировать TLS-записи", "Дополнительно разбивать TLS-записи", "recordFragment", false);
                    }
                    ToggleSetting("Шумы", "Отправлять маскирующие пакеты перед данными", "noise", false);
                    if (store.GetBool("noise", false)) { ComboSetting("Тип шума", "noiseType", new[] { "array", "rand", "str", "base64" }, s => SaveString("noiseType", s)); if (store.GetString("noiseType", "array") == "rand") { TextSetting("Размер случайного пакета", "noiseRand", "1-8192"); TextSetting("Диапазон байтов", "noiseRange", "0-255"); } else TextSetting("Пакет", "noisePacket", "hex-байты через запятую"); TextSetting("Задержка шума", "noiseDelay", "10-16"); }
                    ToggleSetting("Мультиплексирование (Mux)", "Объединять соединения в один канал", "mux", false);
                    if (store.GetBool("mux", false)) { ComboSetting("Протокол Mux", "muxProtocol", new[] { "h2mux", "smux", "yamux" }, s => SaveString("muxProtocol", s)); ComboSetting("Ограничение", "muxLimit", new[] { "connections", "streams" }, s => SaveString("muxLimit", s)); if (store.GetString("muxLimit", "connections") == "streams") NumberSetting("Максимум потоков", "muxStreams", 1, 1024); else NumberSetting("TCP-соединения", "muxConnections", 1, 1024); ToggleSetting("Заполнение", "Маскировать размеры пакетов", "muxPadding", true); }
                    break;
                case "routing": AddPageTitle("Маршрутизация", "Режим и правила маршрута"); ComboSetting("Режим", "mode", new[] { "rules", "global", "direct" }, s => SaveString("mode", s)); ToggleSetting("Локальная сеть напрямую", "Частные IP обходят прокси", "bypassLan", true); ToggleSetting("Анализ протокола", "Определять домен и протокол по TLS/HTTP", "sniff", true); ComboSetting("Обработка QUIC", "quic", new[] { "allow", "reject" }, s => SaveString("quic", s)); PageContent.Children.Add(ActionButton("Редактировать правила JSON", () => EditRouting())); break;
                case "subscriptions": AddPageTitle("Подписки", "Заголовки и автообновление"); TextSetting("User-Agent", "userAgent", "пусто — Happ Android по умолчанию"); ToggleSetting("Обновлять при запуске", "Перечитывать подписки при старте Wraith", "updateOnOpen", true); ToggleSetting("Пинг при запуске", "Проверить все хосты при открытии", "pingOnOpen", false); NumberSetting("Интервал обновления, часы", "interval", 1, 168); break;
                case "ping": AddPageTitle("Пинг", "TCP проверяет доступность порта; proxy ping нужен работающий туннель"); ComboSetting("Тип пинга", "pingType", new[] { "proxyGet", "proxyHead", "tcp", "icmp" }, s => SaveString("pingType", s)); TextSetting("Проверочный URL", "pingUrl", "https://www.gstatic.com/generate_204"); NumberSetting("Тайм-аут, мс", "pingTimeout", 1000, 30000); PageContent.Children.Add(ActionButton("Пинг всех серверов", () => PingAll())); break;
                case "interface": AddPageTitle("Интерфейс", "Внешний вид и поведение"); ComboSetting("Тема", "theme", new[] { "dark", "light", "system" }, s => SaveString("theme", s)); ToggleSetting("Свернуть в трей при закрытии", "Подключение продолжит работать в фоне", "minimizeToTray", true); ToggleSetting("Автозапуск Windows", "Открывать Wraith при входе в Windows", "autoStart", false); break;
                case "advanced": AddPageTitle("Расширенные настройки", "Локальный прокси и диагностика"); NumberSetting("Порт локального прокси", "proxyPort", 1024, 65535); ToggleSetting("Разрешить подключения из LAN", "Открывает локальный прокси в сети", "allowLan", false); ToggleSetting("SOCKS5 авторизация", "Защитить локальный SOCKS5 логином и паролем", "socksAuth", false); TextSetting("Логин", "proxyUser", "wraith"); TextSetting("Пароль", "proxyPassword", "пароль локального прокси"); PageContent.Children.Add(ActionButton("Проверить конфигурацию sing-box", ValidateSelected)); break;
                case "info": AddPageTitle("Информация", "Wraith Windows"); PageContent.Children.Add(Card(Label("Wraith · Windows\nsing-box 1.14.1\nПрофили хранятся в Windows DPAPI.\nСерверы предоставляет ваша подписка.", 14))); PageContent.Children.Add(ActionButton("Открыть журнал", () => Navigate("Логи"))); break;
            }
        }
        private void AddSetting(string name, string description, UIElement control)
        {
            var row = new Grid(); row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); var labels = Column(); labels.Children.Add(Label(name, 14, null, true)); if (description.Length > 0) labels.Children.Add(Label(description, 11, (Brush)FindResource("Muted"))); row.Children.Add(labels); Grid.SetColumn(control, 1); row.Children.Add(control); PageContent.Children.Add(Card(row, new Thickness(18)));
        }
        private void ToggleSetting(string title, string description, string key, bool defaultValue, bool forced = false)
        {
            var control = new CheckBox { IsChecked = forced || store.GetBool(key, defaultValue), IsEnabled = !forced, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(18, 0, 8, 0) };
            control.Checked += (s, e) => SaveBool(key, true); control.Unchecked += (s, e) => SaveBool(key, false); AddSetting(title, description, control);
        }
        private void ComboSetting(string title, string key, string[] items, Action<string> save)
        {
            string selected = key == "TUN" ? store.State.TunnelMode : store.GetString(key, items[0]); var combo = new ComboBox { ItemsSource = items, SelectedItem = items.Contains(selected) ? selected : items[0], Margin = new Thickness(14, 0, 8, 0), MinWidth = 180 };
            combo.SelectionChanged += (s, e) => { if (combo.SelectedItem != null) save(Convert.ToString(combo.SelectedItem)); }; AddSetting(title, "", combo);
        }
        private void TextSetting(string title, string key, string hint)
        {
            var text = new TextBox { Text = store.GetString(key), Width = 250, Margin = new Thickness(14, 0, 8, 0), ToolTip = hint }; text.LostFocus += (s, e) => store.Set(key, text.Text); text.KeyDown += (s, e) => { if (e.Key == Key.Enter) { store.Set(key, text.Text); Keyboard.ClearFocus(); } }; AddSetting(title, "", text);
        }
        private void NumberSetting(string title, string key, int min, int max)
        {
            int value = store.GetInt(key, key == "mtu" ? 1500 : key == "proxyPort" ? 2081 : key == "pingTimeout" ? 5000 : 24);
            var text = new TextBox { Text = value.ToString(), Width = 110, Margin = new Thickness(14, 0, 8, 0) }; text.LostFocus += (s, e) => { int n; if (Int32.TryParse(text.Text, out n) && n >= min && n <= max) store.Set(key, n); else text.Text = store.GetInt(key, value).ToString(); }; AddSetting(title, min + "–" + max, text);
        }
        private void SaveBool(string key, bool value) { store.Set(key, value); if (key == "autoStart") ApplyAutoStart(value); if (settingsPage == "tunnel" && new[] { "dnsFromJson", "fragment", "noise", "mux" }.Contains(key)) ShowSettingsDetail(); }
        private void SaveString(string key, string value) { if (key == "TunnelMode") store.State.TunnelMode = value; else store.Set(key, value); store.Save(); if (settingsPage == "tunnel" && new[] { "fragmentType", "noiseType", "muxLimit" }.Contains(key)) ShowSettingsDetail(); }
        private void ApplyAutoStart(bool enabled)
        {
            const string path = @"Software\Microsoft\Windows\CurrentVersion\Run";
            using (var key = Registry.CurrentUser.OpenSubKey(path, true) ?? Registry.CurrentUser.CreateSubKey(path))
            {
                if (enabled) key.SetValue("Wraith", "\"" + System.Reflection.Assembly.GetExecutingAssembly().Location + "\"", RegistryValueKind.String);
                else key.DeleteValue("Wraith", false);
            }
        }
        private void EditRouting()
        {
            Profile p = store.Selected; if (p == null || p.Routing.Count == 0) { Notice("У выбранного сервера нет импортированных правил."); return; }
            var win = new Window { Owner = this, Title = "Маршрутизация JSON", Width = 920, Height = 700, WindowStartupLocation = WindowStartupLocation.CenterOwner, Background = (Brush)FindResource("Bg") }; var box = new TextBox { Text = JsonUtil.Print(p.Routing), AcceptsReturn = true, FontFamily = new FontFamily("Consolas"), VerticalScrollBarVisibility = ScrollBarVisibility.Auto }; win.Content = box; win.ShowDialog();
        }
        private async void ValidateSelected()
        {
            if (store.Selected == null) { Notice("Сначала выберите сервер", true); return; }
            try { await Task.Run(() => { var config = TunnelConfigBuilder.Build(store.State, store.Selected, core.LogPath, "validate"); string path = Path.Combine(store.DataDirectory, "validate.json"); File.WriteAllText(path, JsonUtil.Print(config)); using (var proc = Process.Start(new ProcessStartInfo(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "sing-box.exe"), "check -c \"" + path + "\"") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true })) { var errors = proc.StandardError.ReadToEndAsync(); string output = proc.StandardOutput.ReadToEnd() + errors.Result; proc.WaitForExit(); if (proc.ExitCode != 0) throw new InvalidOperationException(output); } }); Notice("Конфигурация принята sing-box"); } catch (Exception ex) { Notice(ex.Message, true); }
        }
        private void ShowLogs()
        {
            PageContent.Children.Clear(); AddPageTitle("Логи", "События Wraith и сообщения ядра sing-box"); var row = Row(); row.Children.Add(ActionButton("⟳  Обновить", ShowLogs)); row.Children.Add(ActionButton("Скопировать", () => Clipboard.SetText(ReadLogs()))); row.Children.Add(ActionButton("Открыть папку", () => Process.Start("explorer.exe", store.DataDirectory))); PageContent.Children.Add(row);
            var view = new TextBox { Text = ReadLogs(), IsReadOnly = true, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, FontFamily = new FontFamily("Consolas"), FontSize = 12, Background = (Brush)FindResource("Panel"), Foreground = (Brush)FindResource("Text"), BorderThickness = new Thickness(0), Padding = new Thickness(15), MinHeight = 430, MaxHeight = 640, Margin = new Thickness(0, 18, 0, 0) }; PageContent.Children.Add(view);
            logView = view; logsDirty = false; view.Loaded += (s, e) => view.ScrollToEnd();
        }
        private TextBox logView; private bool logsDirty;
        /** Called once a second: new core output refreshes the open log in place instead of rebuilding the page per line. */
        private void RefreshLogsIfDirty()
        {
            if (!logsDirty || page != "Логи" || logView == null) return; logsDirty = false;
            bool atEnd = logView.VerticalOffset + logView.ViewportHeight >= logView.ExtentHeight - 4; logView.Text = ReadLogs(); if (atEnd) logView.ScrollToEnd();
        }
        /**
         * Last 256 KB of the log, opened with shared access: sing-box holds the file open while
         * connected, and File.ReadAllText then failed with "used by another process".
         */
        private string ReadLogs()
        {
            try
            {
                if (!File.Exists(core.LogPath)) return "Журнал пока пуст.";
                using (var stream = new FileStream(core.LogPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                {
                    const long limit = 256 * 1024; bool cut = stream.Length > limit; if (cut) stream.Seek(-limit, SeekOrigin.End);
                    using (var reader = new StreamReader(stream, Encoding.UTF8)) { string text = reader.ReadToEnd(); if (cut) { int nl = text.IndexOf('\n'); if (nl >= 0) text = "…\n" + text.Substring(nl + 1); } return text.Length == 0 ? "Журнал пока пуст." : text; }
                }
            }
            catch (Exception ex) { return "Не удалось прочитать журнал: " + ex.Message; }
        }
        private void Space(double height) { PageContent.Children.Add(new Border { Height = height }); }
        private int noticeSerial;
        /** Short status in the header. Non-error notices used to go only into an invisible tooltip. */
        private async void Notice(string message, bool error = false)
        {
            if (!Dispatcher.CheckAccess()) { await Dispatcher.BeginInvoke(new Action(() => Notice(message, error))); return; }
            CoreState.ToolTip = message; NoticeText.Text = message; NoticeText.ToolTip = message; NoticeText.Foreground = error ? Brushes.IndianRed : (Brush)FindResource("Muted");
            int serial = ++noticeSerial; logsDirty = true;
            if (error) MessageBox.Show(this, message, "Wraith", MessageBoxButton.OK, MessageBoxImage.Warning);
            await Task.Delay(error ? 12000 : 6000); if (serial == noticeSerial) NoticeText.Text = "";
        }
    }
}

using System.Diagnostics;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;

namespace AlmasFoxPC;

public sealed class MainFormV05 : Form
{
    private readonly TextBox log = new();
    private readonly Label state = new();
    private readonly Label usbState = new();
    private readonly Button permanentButton;

    private string? adbPath;
    private string? fastbootPath;
    private string? selectedBoot;
    private string? patchedBoot;
    private string? adbSerial;

    private bool bootCandidateVerified;
    private bool usbAuthorized;
    private bool deviceVerified;
    private bool patchedVerified;
    private bool temporaryBootPassed;

    private const string PlatformToolsUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip";

    public MainFormV05()
    {
        Text = "AlmasFox PC v0.5";
        Width = 860;
        Height = 860;
        MinimumSize = new Size(760, 700);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(13, 15, 18);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10f);

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 5,
            Padding = new Padding(22),
            BackColor = BackColor,
            AutoScroll = true
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        root.Controls.Add(new Label
        {
            Text = "ALMAS FOX PC",
            Font = new Font("Segoe UI", 27f, FontStyle.Bold),
            ForeColor = Color.White,
            AutoSize = true,
            Margin = new Padding(0, 0, 0, 2)
        });

        root.Controls.Add(new Label
        {
            Text = "boot.img ноутбуктан → USB рұқсаты → Magisk patch → Fastboot тест",
            Font = new Font("Segoe UI", 11f),
            ForeColor = Color.FromArgb(190, 194, 200),
            AutoSize = true,
            Margin = new Padding(0, 0, 0, 16)
        });

        var statusPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 1,
            BackColor = Color.FromArgb(29, 32, 37),
            Padding = new Padding(14),
            Margin = new Padding(0, 0, 0, 14)
        };
        state.Text = "Күйі: алдымен ноутбуктан boot.img таңда";
        state.AutoSize = true;
        state.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        state.ForeColor = Color.White;
        usbState.Text = "USB: тексерілмеді";
        usbState.AutoSize = true;
        usbState.ForeColor = Color.FromArgb(255, 190, 95);
        statusPanel.Controls.Add(state);
        statusPanel.Controls.Add(usbState);
        root.Controls.Add(statusPanel);

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            WrapContents = true,
            FlowDirection = FlowDirection.LeftToRight,
            Margin = new Padding(0, 0, 0, 14)
        };

        var bootButton = MakeButton("1. НОУТБУКТАН BOOT.IMG ТАҢДАУ");
        bootButton.Click += (_, _) => ChooseBootFirst();
        buttons.Controls.Add(bootButton);

        var toolsButton = MakeButton("2. ADB / FASTBOOT ДАЙЫНДАУ");
        toolsButton.Click += async (_, _) => await InstallPlatformToolsAsync();
        buttons.Controls.Add(toolsButton);

        var usbButton = MakeButton("3. USB КАБЕЛЬ + РҰҚСАТ ТЕКСЕРУ");
        usbButton.Click += async (_, _) => await AuthorizeUsbAsync(showIntro: true);
        buttons.Controls.Add(usbButton);

        var deviceButton = MakeButton("4. ТЕЛЕФОН МОДЕЛІН ТЕКСЕРУ");
        deviceButton.Click += async (_, _) => await CheckDeviceAsync();
        buttons.Controls.Add(deviceButton);

        var magiskButton = MakeButton("5. BOOT-ТЫ ТЕЛЕФОНҒА → MAGISK");
        magiskButton.Click += async (_, _) => await PushBootAndOpenMagiskAsync();
        buttons.Controls.Add(magiskButton);

        var pullButton = MakeButton("6. PATCHED ФАЙЛДЫ НОУТБУККЕ АЛУ");
        pullButton.Click += async (_, _) => await PullPatchedAsync();
        buttons.Controls.Add(pullButton);

        var testButton = MakeButton("7. FASTBOOT-ҚА ӨТІП УАҚЫТША СЫНАУ");
        testButton.Click += async (_, _) => await TemporaryBootTestAsync();
        buttons.Controls.Add(testButton);

        permanentButton = MakeButton("8. ТҰРАҚТЫ ROOT ОРНАТУ");
        permanentButton.Enabled = false;
        permanentButton.Click += async (_, _) => await PermanentInstallAsync();
        buttons.Controls.Add(permanentButton);

        root.Controls.Add(buttons);

        var panel = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(29, 32, 37),
            Padding = new Padding(16),
            Margin = new Padding(0)
        };

        log.Multiline = true;
        log.ReadOnly = true;
        log.ScrollBars = ScrollBars.Vertical;
        log.BackColor = Color.FromArgb(20, 23, 27);
        log.ForeColor = Color.FromArgb(220, 224, 229);
        log.BorderStyle = BorderStyle.FixedSingle;
        log.Dock = DockStyle.Fill;
        log.Font = new Font("Consolas", 9.5f);

        var warning = new Label
        {
            Text = "USB рұқсаты телефон экранында беріледі: «Allow USB debugging?» → «Always allow from this computer» → Allow. AlmasFox unlock / erase / format / userdata / -w қолданбайды.",
            Dock = DockStyle.Bottom,
            Height = 56,
            ForeColor = Color.FromArgb(255, 190, 95),
            Padding = new Padding(0, 8, 0, 0)
        };

        panel.Controls.Add(log);
        panel.Controls.Add(warning);
        root.Controls.Add(panel);

        Controls.Add(root);
        ResolveTools();
        Append("AlmasFox PC v0.5 іске қосылды.");
        Append("1-қадам: USB/Fastboot-қа кірмей тұрып ноутбуктан boot.img таңдаңыз.");
        Append("USB рұқсаты Windows-та емес, телефондағы ADB RSA терезесі арқылы беріледі.");
    }

    private Button MakeButton(string text) => new()
    {
        Text = text,
        Width = 375,
        Height = 54,
        FlatStyle = FlatStyle.Flat,
        BackColor = Color.FromArgb(43, 47, 54),
        ForeColor = Color.White,
        FlatAppearance = { BorderColor = Color.FromArgb(67, 72, 80), BorderSize = 1 },
        Margin = new Padding(0, 0, 10, 10)
    };

    private void Append(string text)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => Append(text));
            return;
        }
        log.AppendText($"[{DateTime.Now:HH:mm:ss}] {text}{Environment.NewLine}");
        log.SelectionStart = log.TextLength;
        log.ScrollToCaret();
    }

    private void SetState(string text, bool ok = false)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => SetState(text, ok));
            return;
        }
        state.Text = "Күйі: " + text;
        state.ForeColor = ok ? Color.FromArgb(90, 220, 130) : Color.White;
    }

    private void SetUsb(string text, bool ok = false)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => SetUsb(text, ok));
            return;
        }
        usbState.Text = "USB: " + text;
        usbState.ForeColor = ok ? Color.FromArgb(90, 220, 130) : Color.FromArgb(255, 190, 95);
    }

    private void ChooseBootFirst()
    {
        using var dlg = new OpenFileDialog
        {
            Title = "Mi A3 үшін boot.img файлын таңда",
            Filter = "Android boot image (*.img)|*.img|Барлық файл (*.*)|*.*",
            Multiselect = false,
            CheckFileExists = true,
            InitialDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads")
        };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;

        try
        {
            var info = ValidateBootFile(dlg.FileName);
            if (!info.MagicOk || !info.SizeOk)
            {
                selectedBoot = null;
                bootCandidateVerified = false;
                MessageBox.Show(
                    "Бұл файл Android boot.img тексерісінен өтпеді.\n\nANDROID! header және файл көлемі дұрыс болуы керек.",
                    "boot.img жарамсыз",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Stop);
                SetState("boot.img жарамсыз");
                return;
            }

            var confirm = MessageBox.Show(
                $"Осы boot.img қолданылсын ба?\n\nФайл: {Path.GetFileName(dlg.FileName)}\nКөлемі: {info.Size / 1024 / 1024} МБ\nSHA-256:\n{info.Sha256}\n\nБұл кезеңде телефонға ештеңе жазылмайды.",
                "boot.img таңдау",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Question);
            if (confirm != DialogResult.Yes) return;

            selectedBoot = dlg.FileName;
            bootCandidateVerified = true;
            patchedVerified = false;
            temporaryBootPassed = false;
            permanentButton.Enabled = false;
            Append("Ноутбуктан boot.img таңдалды: " + selectedBoot);
            Append("Boot SHA-256: " + info.Sha256);
            SetState("boot.img таңдалды — енді USB рұқсатын тексер", true);
        }
        catch (Exception ex)
        {
            bootCandidateVerified = false;
            selectedBoot = null;
            Append("boot.img қатесі: " + ex.Message);
            SetState("boot.img таңдау қатесі");
        }
    }

    private void ResolveTools()
    {
        var tools = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AlmasFox", "Tools", "platform-tools");
        var a = Path.Combine(tools, "adb.exe");
        var f = Path.Combine(tools, "fastboot.exe");
        if (File.Exists(a) && File.Exists(f))
        {
            adbPath = a;
            fastbootPath = f;
            return;
        }
        adbPath = FindOnPath("adb.exe");
        fastbootPath = FindOnPath("fastboot.exe");
    }

    private static string? FindOnPath(string name)
    {
        foreach (var part in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var p = Path.Combine(part.Trim(), name);
                if (File.Exists(p)) return p;
            }
            catch { }
        }
        return null;
    }

    private async Task InstallPlatformToolsAsync()
    {
        try
        {
            SetState("Google Platform Tools дайындалуда");
            var toolsRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AlmasFox", "Tools");
            Directory.CreateDirectory(toolsRoot);
            var zip = Path.Combine(toolsRoot, "platform-tools.zip");
            Append("Google ресми Platform Tools жүктелуде…");
            using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(3) };
            await File.WriteAllBytesAsync(zip, await http.GetByteArrayAsync(PlatformToolsUrl));
            var platformDir = Path.Combine(toolsRoot, "platform-tools");
            if (Directory.Exists(platformDir)) Directory.Delete(platformDir, true);
            ZipFile.ExtractToDirectory(zip, toolsRoot, true);
            File.Delete(zip);
            ResolveTools();
            if (adbPath is null || fastbootPath is null) throw new InvalidOperationException("adb.exe/fastboot.exe табылмады");
            Append("ADB: " + adbPath);
            Append("Fastboot: " + fastbootPath);
            SetState("ADB/Fastboot дайын", true);
        }
        catch (Exception ex)
        {
            Append("Platform Tools қатесі: " + ex.Message);
            SetState("ADB/Fastboot дайындалмады");
        }
    }

    private async Task<bool> EnsureToolsAsync()
    {
        ResolveTools();
        if (adbPath is not null && fastbootPath is not null) return true;
        var yes = MessageBox.Show(
            "ADB/Fastboot табылмады. Google ресми Platform Tools автоматты жүктелсін бе?",
            "ADB/Fastboot керек",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Question);
        if (yes != DialogResult.Yes) return false;
        await InstallPlatformToolsAsync();
        ResolveTools();
        return adbPath is not null && fastbootPath is not null;
    }

    private async Task<bool> AuthorizeUsbAsync(bool showIntro)
    {
        if (!await EnsureToolsAsync()) return false;

        if (showIntro)
        {
            var go = MessageBox.Show(
                "USB рұқсатын дұрыс беру:\n\n1) Телефон Android-та ашық тұрсын. Fastboot-қа ӘЛІ кірме.\n2) DATA тасымалдайтын USB кабельмен ноутбукке жалға. Тек зарядтайтын кабель жарамайды.\n3) Developer options → USB debugging қос.\n4) Телефон құлпын ашық ұста.\n5) «Allow USB debugging?» шыққанда «Always allow from this computer» белгіле де Allow бас.\n\nAlmasFox қазір ADB сұранысын жіберіп, телефондағы рұқсат терезесін күтеді.",
                "USB кабель және ADB рұқсаты",
                MessageBoxButtons.OKCancel,
                MessageBoxIcon.Information);
            if (go != DialogResult.OK) return false;
        }

        SetUsb("ADB рұқсаты күтілуде…");
        SetState("USB кабель / ADB рұқсаты тексерілуде");
        usbAuthorized = false;
        adbSerial = null;

        await RunAsync(adbPath!, new[] { "start-server" }, 15000);
        var deadline = DateTime.UtcNow.AddSeconds(75);
        bool unauthorizedShown = false;
        bool noDeviceShown = false;
        bool restartedForOffline = false;

        while (DateTime.UtcNow < deadline)
        {
            var r = await RunAsync(adbPath!, new[] { "devices", "-l" }, 10000);
            var entries = ParseAdbDevices(r.StdOut);

            if (entries.Count > 1)
            {
                SetUsb("бірнеше ADB құрылғы табылды");
                MessageBox.Show("Бірнеше Android құрылғы қосулы. Қауіпсіздік үшін тек бір телефонды қалдыр.", "USB", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return false;
            }

            if (entries.Count == 1)
            {
                var e = entries[0];
                if (e.Status.Equals("device", StringComparison.OrdinalIgnoreCase))
                {
                    adbSerial = e.Serial;
                    usbAuthorized = true;
                    SetUsb("ADB рұқсаты БЕРІЛДІ ✓", true);
                    SetState("USB дайын — телефонды тексеруге болады", true);
                    Append("USB/ADB рұқсаты расталды. Serial: " + e.Serial);
                    return true;
                }

                if (e.Status.Equals("unauthorized", StringComparison.OrdinalIgnoreCase))
                {
                    SetUsb("телефон рұқсат күтуде (unauthorized)");
                    if (!unauthorizedShown)
                    {
                        unauthorizedShown = true;
                        MessageBox.Show(
                            "Телефон табылды, бірақ рұқсат берілмеген.\n\nТелефон экранын ашып:\n«Allow USB debugging?» → «Always allow from this computer» → ALLOW.\n\nТерезе шықпаса: USB debugging-ті өшіріп-қос немесе кабельді қайта жалға. AlmasFox күте береді.",
                            "Телефоннан рұқсат бер",
                            MessageBoxButtons.OK,
                            MessageBoxIcon.Warning);
                    }
                }
                else if (e.Status.Equals("offline", StringComparison.OrdinalIgnoreCase))
                {
                    SetUsb("ADB offline — қайта қосылуда");
                    if (!restartedForOffline)
                    {
                        restartedForOffline = true;
                        Append("ADB offline. Server қайта іске қосылуда…");
                        await RunAsync(adbPath!, new[] { "kill-server" }, 10000);
                        await RunAsync(adbPath!, new[] { "start-server" }, 15000);
                    }
                }
                else
                {
                    SetUsb("ADB күйі: " + e.Status);
                }
            }
            else
            {
                SetUsb("телефон ADB арқылы көрінбейді");
                if (!noDeviceShown && DateTime.UtcNow > deadline.AddSeconds(-65))
                {
                    noDeviceShown = true;
                    MessageBox.Show(
                        "ADB телефонды көрмеді.\n\nТексер:\n• USB кабель DATA тасымалдай ма?\n• Телефонда USB debugging қосулы ма?\n• USB режимі сұралса File transfer таңда.\n• Windows Device Manager-де Android/ADB драйвері қатесіз бе?\n\nКабельді қайта жалға — AlmasFox әлі күтіп тұр.",
                        "USB кабель тексеру",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Information);
                }
            }

            await Task.Delay(2000);
        }

        SetUsb("рұқсат алынбады");
        SetState("USB рұқсаты жоқ — келесі қадам бұғатталды");
        MessageBox.Show(
            "75 секунд ішінде ADB рұқсаты алынбады. Boot.img телефонға жіберілген жоқ.\n\nDATA кабель, USB debugging және телефондағы RSA рұқсатын қайта тексер.",
            "USB рұқсаты жоқ",
            MessageBoxButtons.OK,
            MessageBoxIcon.Warning);
        return false;
    }

    private static List<AdbEntry> ParseAdbDevices(string stdout)
    {
        var list = new List<AdbEntry>();
        foreach (var raw in stdout.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries))
        {
            var line = raw.Trim();
            if (line.StartsWith("List of devices", StringComparison.OrdinalIgnoreCase) || line.StartsWith("* daemon", StringComparison.OrdinalIgnoreCase)) continue;
            var parts = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length >= 2) list.Add(new AdbEntry(parts[0], parts[1]));
        }
        return list;
    }

    private async Task<bool> EnsureAuthorizedUsbAsync()
    {
        if (usbAuthorized)
        {
            var r = await RunAsync(adbPath!, new[] { "devices" }, 8000);
            var entries = ParseAdbDevices(r.StdOut);
            if (entries.Count == 1 && entries[0].Status.Equals("device", StringComparison.OrdinalIgnoreCase)) return true;
            usbAuthorized = false;
        }
        return await AuthorizeUsbAsync(showIntro: true);
    }

    private async Task CheckDeviceAsync()
    {
        if (!await EnsureAuthorizedUsbAsync()) return;
        try
        {
            SetState("телефон моделі тексерілуде");
            var device = await GetPropAsync("ro.product.device");
            var model = await GetPropAsync("ro.product.model");
            var android = await GetPropAsync("ro.build.version.release");
            var buildId = await GetPropAsync("ro.build.id");
            var display = await GetPropAsync("ro.build.display.id");
            var patch = await GetPropAsync("ro.build.version.security_patch");
            var fingerprint = await GetPropAsync("ro.build.fingerprint");

            Append($"Модель: {model}");
            Append($"Device: {device}");
            Append($"Android: {android}");
            Append($"Build ID: {buildId}");
            Append($"Display: {display}");
            Append($"Security patch: {patch}");
            Append($"Fingerprint: {fingerprint}");

            deviceVerified = device.Equals("laurel_sprout", StringComparison.OrdinalIgnoreCase);
            patchedVerified = false;
            temporaryBootPassed = false;
            permanentButton.Enabled = false;

            if (!deviceVerified)
            {
                SetState("бұл нұсқа Mi A3 / laurel_sprout үшін ғана");
                MessageBox.Show("Құрылғы laurel_sprout емес. Flash және Magisk кезеңі бұғатталды.", "Қауіпсіздік", MessageBoxButtons.OK, MessageBoxIcon.Stop);
                return;
            }

            SetState("Mi A3 / laurel_sprout расталды", true);
        }
        catch (Exception ex)
        {
            deviceVerified = false;
            Append("Телефон тексеру қатесі: " + ex.Message);
            SetState("телефон тексерілмеді");
        }
    }

    private async Task PushBootAndOpenMagiskAsync()
    {
        if (!bootCandidateVerified || selectedBoot is null)
        {
            MessageBox.Show("Алдымен ноутбуктан boot.img таңда.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (!deviceVerified)
        {
            MessageBox.Show("Алдымен USB рұқсатын беріп, телефон моделін тексер.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (!await EnsureAuthorizedUsbAsync()) return;

        try
        {
            var confirm = MessageBox.Show(
                $"Таңдалған boot.img телефонның Download/AlmasFox қалтасына көшіріледі.\n\n{Path.GetFileName(selectedBoot)}\n\nБұл әлі flash емес және /data өзгермейді. Жалғастырамыз ба?",
                "boot.img телефонға жіберу",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Question);
            if (confirm != DialogResult.Yes) return;

            SetState("boot.img телефонға жіберілуде");
            await RunRequiredAsync(adbPath!, new[] { "shell", "mkdir", "-p", "/sdcard/Download/AlmasFox" });
            await RunRequiredAsync(adbPath!, new[] { "push", selectedBoot, "/sdcard/Download/AlmasFox/AlmasFox-input-boot.img" }, 120000);
            Append("boot.img телефонға жіберілді: Download/AlmasFox/AlmasFox-input-boot.img");

            var open = await RunAsync(adbPath!, new[] { "shell", "monkey", "-p", "com.topjohnwu.magisk", "-c", "android.intent.category.LAUNCHER", "1" }, 15000);
            if (open.ExitCode != 0)
            {
                Append("Magisk автоматты ашылмады. Телефоннан Magisk-ті қолмен ашыңыз.");
            }

            SetState("Magisk patch күтілуде");
            MessageBox.Show(
                "Телефондағы Magisk-та:\n\nInstall → Select and Patch a File → Download/AlmasFox/AlmasFox-input-boot.img таңда.\n\nPatch толық біткенше USB кабельді ажыратпа.\n\nСосын PC-де «PATCHED ФАЙЛДЫ НОУТБУККЕ АЛУ» бас.\n\nMagisk root жоқ кезде сыртқы қолданбаға толық автомат patch API бермейді, сондықтан файлды таңдау бір рет қолмен жасалады.",
                "Magisk patch",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            Append("Magisk кезеңі қатесі: " + ex.Message);
            SetState("Magisk кезеңі тоқтады");
        }
    }

    private async Task PullPatchedAsync()
    {
        if (!await EnsureAuthorizedUsbAsync()) return;
        try
        {
            SetState("Magisk patched файл ізделуде");
            var ls = await RunAsync(adbPath!, new[] { "shell", "sh", "-c", "ls -t /sdcard/Download/magisk_patched*.img /sdcard/Download/AlmasFox/magisk_patched*.img 2>/dev/null | head -n 1" }, 15000);
            var remote = ls.StdOut.Trim();
            if (string.IsNullOrWhiteSpace(remote))
            {
                MessageBox.Show("magisk_patched*.img табылмады. Телефондағы Magisk patch аяқталғанын тексер.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            var outDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "AlmasFox");
            Directory.CreateDirectory(outDir);
            var local = Path.Combine(outDir, "AlmasFox-patched-ready.img");
            await RunRequiredAsync(adbPath!, new[] { "pull", remote, local }, 120000);

            var info = ValidateBootFile(local);
            if (!info.MagicOk || !info.SizeOk) throw new InvalidOperationException("Patched файл Android boot image емес");
            if (selectedBoot is not null)
            {
                var originalSize = new FileInfo(selectedBoot).Length;
                if (Math.Abs(info.Size - originalSize) > 12L * 1024 * 1024)
                    throw new InvalidOperationException("Patched image көлемі бастапқы boot.img-ден күмәнді деңгейде өзгеше");
            }

            patchedBoot = local;
            patchedVerified = true;
            temporaryBootPassed = false;
            permanentButton.Enabled = false;
            Append("Patched image ноутбукке алынды: " + local);
            Append("Patched SHA-256: " + info.Sha256);
            SetState("patched boot дайын — енді ғана Fastboot кезеңі", true);
            MessageBox.Show("Patched файл ноутбукке алынды. Енді Fastboot кезеңіне өтуге болады. Осы уақытқа дейін boot partition-ға ештеңе жазылған жоқ.", "Дайын", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            patchedVerified = false;
            Append("Patched файл қатесі: " + ex.Message);
            SetState("patched файл өтпеді");
        }
    }

    private async Task TemporaryBootTestAsync()
    {
        if (!patchedVerified || patchedBoot is null)
        {
            MessageBox.Show("Алдымен Magisk patched image-ті ноутбукке ал.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (!await EnsureAuthorizedUsbAsync()) return;
        if (!await EnsureToolsAsync()) return;

        var confirm = MessageBox.Show(
            "Енді ғана телефон Fastboot/Bootloader режиміне қайта жүктеледі.\n\nAlmasFox алдымен bootloader unlocked екенін тексереді. LOCKED болса ештеңе жазбай тоқтайды.\n\nЖалғастырамыз ба?",
            "Fastboot кезеңі",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning);
        if (confirm != DialogResult.Yes) return;

        try
        {
            SetState("Fastboot режиміне өтуде");
            await RunRequiredAsync(adbPath!, new[] { "reboot", "bootloader" });
            usbAuthorized = false;
            SetUsb("Fastboot режимі — ADB уақытша өшірулі");
            if (!await WaitForFastbootAsync(TimeSpan.FromSeconds(40))) throw new InvalidOperationException("Fastboot құрылғы табылмады. DATA кабель/драйверді тексер.");

            if (!await IsBootloaderUnlockedAsync())
            {
                temporaryBootPassed = false;
                permanentButton.Enabled = false;
                SetState("bootloader LOCKED — процесс тоқтады");
                MessageBox.Show("Bootloader құлыптаулы. AlmasFox оны автоматты ашпайды, себебі unlock деректерді өшіруі мүмкін.", "Деректер қорғалды", MessageBoxButtons.OK, MessageBoxIcon.Stop);
                return;
            }

            var product = await GetFastbootVarAsync("product");
            Append("Fastboot product: " + product);
            if (!string.IsNullOrWhiteSpace(product) && !product.Contains("laurel", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Fastboot product Mi A3-ке ұқсамайды: " + product);

            SetState("patched boot уақытша жүктелуде");
            Append("fastboot boot — уақытша сынақ; boot_a/boot_b-ға жазбайды.");
            var boot = await RunAsync(fastbootPath!, new[] { "boot", patchedBoot }, 90000);
            Append(boot.All.Trim());
            if (boot.ExitCode != 0) throw new InvalidOperationException("fastboot boot сәтсіз болды. Тұрақты flash бұғатталды.");

            SetState("Android қайта ашылуда");
            if (!await WaitForAuthorizedAdbAfterBootAsync(TimeSpan.FromMinutes(3)))
                throw new InvalidOperationException("Android/ADB қайта ашылмады немесе USB рұқсаты берілмеді. Тұрақты flash жасалмайды.");

            MessageBox.Show(
                "Android ашылғанда Magisk «Shell» үшін Superuser рұқсатын сұрауы мүмкін. Телефон экранын ашып, Allow бас. AlmasFox root-ты тексереді.",
                "Root рұқсаты",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);

            var root = await RunAsync(adbPath!, new[] { "shell", "su", "-c", "id" }, 30000);
            Append("Root test: " + root.All.Trim());
            if (!root.All.Contains("uid=0", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Уақытша boot ашылды, бірақ uid=0 расталмады. Тұрақты flash бұғатталды.");

            temporaryBootPassed = true;
            permanentButton.Enabled = true;
            SetUsb("ADB қайта қосылды ✓", true);
            SetState("УАҚЫТША ROOT ТЕСТ ӨТТІ ✓", true);
            MessageBox.Show("Patched boot уақытша жүктелді және root uid=0 расталды. Енді ғана тұрақты Root батырмасы ашылды.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            temporaryBootPassed = false;
            permanentButton.Enabled = false;
            Append("Уақытша тест тоқтады: " + ex.Message);
            SetState("тұрақты flash бұғатталды");
            MessageBox.Show(ex.Message + "\n\nAlmasFox тұрақты boot partition-ға ештеңе жазған жоқ.", "Қауіпсіз тоқтау", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private async Task PermanentInstallAsync()
    {
        if (!temporaryBootPassed || !patchedVerified || patchedBoot is null)
        {
            MessageBox.Show("Тұрақты орнату үшін уақытша boot + uid=0 тесті өтуі керек.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Stop);
            return;
        }

        var confirm = MessageBox.Show(
            "Уақытша Root тесті өтті.\n\nAlmasFox тек белсенді boot_a/boot_b бөліміне patched image жазады. userdata/data/format/unlock командалары қолданылмайды.\n\nТұрақты орнатуды бастаймыз ба?",
            "Тұрақты Root орнату",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning);
        if (confirm != DialogResult.Yes) return;

        if (!await EnsureAuthorizedUsbAsync()) return;
        try
        {
            await RunRequiredAsync(adbPath!, new[] { "reboot", "bootloader" });
            usbAuthorized = false;
            if (!await WaitForFastbootAsync(TimeSpan.FromSeconds(40))) throw new InvalidOperationException("Fastboot табылмады");
            if (!await IsBootloaderUnlockedAsync()) throw new InvalidOperationException("Bootloader unlocked емес. Flash жасалмайды.");

            var slot = (await GetFastbootVarAsync("current-slot")).Trim().ToLowerInvariant();
            if (slot != "a" && slot != "b") throw new InvalidOperationException("Белсенді A/B слот анықталмады: " + slot);
            var partition = "boot_" + slot;
            Append("Белсенді слот: " + slot.ToUpperInvariant());
            Append("Flash мақсаты: " + partition);

            SetState(partition + " жазылуда");
            var flash = await RunAsync(fastbootPath!, new[] { "flash", partition, patchedBoot }, 120000);
            Append(flash.All.Trim());
            if (flash.ExitCode != 0) throw new InvalidOperationException("Fastboot flash сәтсіз аяқталды");

            await RunRequiredAsync(fastbootPath!, new[] { "reboot" }, 30000);
            SetState("Root орнатылды — Android жүктелуде", true);
            Append("Дайын. userdata/data бөлігіне команда жіберілген жоқ.");
        }
        catch (Exception ex)
        {
            Append("Тұрақты орнату қатесі: " + ex.Message);
            SetState("орнату тоқтады");
            MessageBox.Show(ex.Message, "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private async Task<string> GetPropAsync(string name)
    {
        var r = await RunAsync(adbPath!, new[] { "shell", "getprop", name });
        return r.StdOut.Trim();
    }

    private async Task<bool> WaitForAuthorizedAdbAfterBootAsync(TimeSpan timeout)
    {
        var end = DateTime.UtcNow + timeout;
        bool unauthorizedShown = false;
        while (DateTime.UtcNow < end)
        {
            await Task.Delay(2500);
            var r = await RunAsync(adbPath!, new[] { "devices", "-l" }, 8000);
            var entries = ParseAdbDevices(r.StdOut);
            if (entries.Count == 1 && entries[0].Status.Equals("device", StringComparison.OrdinalIgnoreCase))
            {
                adbSerial = entries[0].Serial;
                usbAuthorized = true;
                return true;
            }
            if (entries.Count == 1 && entries[0].Status.Equals("unauthorized", StringComparison.OrdinalIgnoreCase) && !unauthorizedShown)
            {
                unauthorizedShown = true;
                MessageBox.Show("Телефон Android-қа ашылды, бірақ ADB қайта рұқсат сұрап тұр. Телефондағы «Allow USB debugging?» терезесінде Allow бас.", "USB рұқсаты", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }
        return false;
    }

    private async Task<bool> WaitForFastbootAsync(TimeSpan timeout)
    {
        var end = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < end)
        {
            await Task.Delay(1500);
            var r = await RunAsync(fastbootPath!, new[] { "devices" }, 8000);
            var lines = r.StdOut.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
            if (lines.Length == 1) return true;
            if (lines.Length > 1) throw new InvalidOperationException("Бірнеше Fastboot құрылғы табылды. Тек бір телефонды қалдыр.");
        }
        return false;
    }

    private async Task<bool> IsBootloaderUnlockedAsync()
    {
        var unlocked = await GetFastbootVarAsync("unlocked");
        if (unlocked.Equals("yes", StringComparison.OrdinalIgnoreCase) || unlocked.Equals("true", StringComparison.OrdinalIgnoreCase))
        {
            Append("Bootloader: UNLOCKED ✓");
            return true;
        }
        var fallback = await RunAsync(fastbootPath!, new[] { "oem", "device-info" }, 15000);
        if (fallback.All.Contains("Device unlocked: true", StringComparison.OrdinalIgnoreCase))
        {
            Append("Bootloader: UNLOCKED ✓");
            return true;
        }
        Append("Bootloader: LOCKED немесе күйі анықталмады");
        return false;
    }

    private async Task<string> GetFastbootVarAsync(string name)
    {
        var r = await RunAsync(fastbootPath!, new[] { "getvar", name }, 15000);
        foreach (var raw in r.All.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries))
        {
            var line = raw.Trim();
            var key = name + ":";
            var pos = line.IndexOf(key, StringComparison.OrdinalIgnoreCase);
            if (pos >= 0) return line[(pos + key.Length)..].Trim();
        }
        return "";
    }

    private static BootInfo ValidateBootFile(string file)
    {
        var fi = new FileInfo(file);
        if (!fi.Exists) throw new FileNotFoundException("Файл табылмады", file);
        var sizeOk = fi.Length >= 4L * 1024 * 1024 && fi.Length <= 96L * 1024 * 1024;
        var head = new byte[8];
        using (var fs = File.OpenRead(file))
        {
            if (fs.Read(head, 0, 8) != 8) throw new InvalidDataException("Файл тым қысқа");
        }
        var magicOk = Encoding.ASCII.GetString(head) == "ANDROID!";
        using var sha = SHA256.Create();
        using var input = File.OpenRead(file);
        var digest = Convert.ToHexString(sha.ComputeHash(input)).ToLowerInvariant();
        return new BootInfo(magicOk, sizeOk, fi.Length, digest);
    }

    private static async Task<RunResult> RunAsync(string exe, IEnumerable<string> args, int timeoutMs = 30000)
    {
        var psi = new ProcessStartInfo
        {
            FileName = exe,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = new Process { StartInfo = psi };
        p.Start();
        var stdoutTask = p.StandardOutput.ReadToEndAsync();
        var stderrTask = p.StandardError.ReadToEndAsync();
        using var cts = new CancellationTokenSource(timeoutMs);
        try
        {
            await p.WaitForExitAsync(cts.Token);
        }
        catch (OperationCanceledException)
        {
            try { p.Kill(true); } catch { }
            throw new TimeoutException(Path.GetFileName(exe) + " timeout");
        }
        var stdout = await stdoutTask;
        var stderr = await stderrTask;
        return new RunResult(p.ExitCode, stdout, stderr);
    }

    private static async Task RunRequiredAsync(string exe, IEnumerable<string> args, int timeoutMs = 30000)
    {
        var r = await RunAsync(exe, args, timeoutMs);
        if (r.ExitCode != 0)
            throw new InvalidOperationException(string.IsNullOrWhiteSpace(r.All) ? Path.GetFileName(exe) + " қате коды: " + r.ExitCode : r.All.Trim());
    }

    private readonly record struct BootInfo(bool MagicOk, bool SizeOk, long Size, string Sha256);
    private readonly record struct RunResult(int ExitCode, string StdOut, string StdErr)
    {
        public string All => (StdOut + Environment.NewLine + StdErr).Trim();
    }
    private readonly record struct AdbEntry(string Serial, string Status);
}

using System.Diagnostics;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;

namespace AlmasFoxPC;

public sealed class MainFormV06 : Form
{
    private readonly TextBox log = new();
    private readonly Label state = new();
    private readonly Label usbState = new();
    private readonly Button bootButton;
    private readonly Button toolsButton;
    private readonly Button usbButton;
    private readonly Button deviceButton;
    private readonly Button magiskButton;
    private readonly Button pullButton;
    private readonly Button testButton;
    private readonly Button permanentButton;

    private string? adbPath;
    private string? fastbootPath;
    private string? selectedBoot;
    private string? patchedBoot;
    private string? adbSerial;

    private bool bootCandidateVerified;
    private bool toolsReady;
    private bool usbAuthorized;
    private bool deviceVerified;
    private bool bootSent;
    private bool patchedVerified;
    private bool temporaryBootPassed;

    private const string PlatformToolsUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip";

    public MainFormV06()
    {
        Text = "AlmasFox PC v0.6";
        Width = 900;
        Height = 880;
        MinimumSize = new Size(780, 720);
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
            Text = "boot.img ноутбуктан → USB/RSA рұқсаты → Magisk patch → Fastboot тест → Root",
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

        bootButton = MakeButton("1. НОУТБУКТАН BOOT.IMG ТАҢДАУ");
        bootButton.Click += (_, _) => ChooseBootFirst();
        buttons.Controls.Add(bootButton);

        toolsButton = MakeButton("2. ADB / FASTBOOT ДАЙЫНДАУ");
        toolsButton.Click += async (_, _) => await InstallPlatformToolsAsync();
        buttons.Controls.Add(toolsButton);

        usbButton = MakeButton("3. USB КАБЕЛЬ + RSA РҰҚСАТ");
        usbButton.Click += async (_, _) => await AuthorizeUsbAsync(showIntro: true);
        buttons.Controls.Add(usbButton);

        deviceButton = MakeButton("4. ТЕЛЕФОН МОДЕЛІН ТЕКСЕРУ");
        deviceButton.Click += async (_, _) => await CheckDeviceAsync();
        buttons.Controls.Add(deviceButton);

        magiskButton = MakeButton("5. BOOT-ТЫ ТЕЛЕФОНҒА → MAGISK");
        magiskButton.Click += async (_, _) => await PushBootAndOpenMagiskAsync();
        buttons.Controls.Add(magiskButton);

        pullButton = MakeButton("6. PATCHED ФАЙЛДЫ НОУТБУККЕ АЛУ");
        pullButton.Click += async (_, _) => await PullPatchedAsync();
        buttons.Controls.Add(pullButton);

        testButton = MakeButton("7. FASTBOOT-ҚА ӨТІП УАҚЫТША СЫНАУ");
        testButton.Click += async (_, _) => await TemporaryBootTestAsync();
        buttons.Controls.Add(testButton);

        permanentButton = MakeButton("8. ТҰРАҚТЫ ROOT ОРНАТУ");
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
            Text = "USB рұқсаты телефонда беріледі: Allow USB debugging? → Always allow from this computer → Allow. AlmasFox unlock / erase / format / userdata / -w қолданбайды.",
            Dock = DockStyle.Bottom,
            Height = 58,
            ForeColor = Color.FromArgb(255, 190, 95),
            Padding = new Padding(0, 8, 0, 0)
        };

        panel.Controls.Add(log);
        panel.Controls.Add(warning);
        root.Controls.Add(panel);

        Controls.Add(root);
        ResolveTools();
        RefreshStepButtons();
        Append("AlmasFox PC v0.6 іске қосылды.");
        Append("1-қадам: телефонды Fastboot-қа кіргізбей тұрып ноутбуктан boot.img таңда.");
        Append("v0.6 Platform Tools-ты ескі adb.exe үстінен жазбайды — әр орнату жаңа бөлек папкаға түседі.");
    }

    private Button MakeButton(string text) => new()
    {
        Text = text,
        Width = 400,
        Height = 54,
        FlatStyle = FlatStyle.Flat,
        BackColor = Color.FromArgb(43, 47, 54),
        ForeColor = Color.White,
        FlatAppearance = { BorderColor = Color.FromArgb(67, 72, 80), BorderSize = 1 },
        Margin = new Padding(0, 0, 10, 10)
    };

    private void RefreshStepButtons()
    {
        if (InvokeRequired)
        {
            BeginInvoke(RefreshStepButtons);
            return;
        }
        bootButton.Enabled = true;
        toolsButton.Enabled = true;
        usbButton.Enabled = bootCandidateVerified;
        deviceButton.Enabled = usbAuthorized;
        magiskButton.Enabled = bootCandidateVerified && usbAuthorized && deviceVerified;
        pullButton.Enabled = bootSent && usbAuthorized;
        testButton.Enabled = patchedVerified;
        permanentButton.Enabled = temporaryBootPassed && patchedVerified;
    }

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

    private void ResetAfterBootChange()
    {
        usbAuthorized = false;
        deviceVerified = false;
        bootSent = false;
        patchedVerified = false;
        temporaryBootPassed = false;
        adbSerial = null;
        patchedBoot = null;
        SetUsb("қайта тексеру керек");
        RefreshStepButtons();
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
                ResetAfterBootChange();
                MessageBox.Show(
                    "Бұл файл Android boot.img тексерісінен өтпеді.\n\nANDROID! header болуы және көлемі 4–96 МБ аралығында болуы керек.",
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
            ResetAfterBootChange();
            Append("Ноутбуктан boot.img таңдалды: " + selectedBoot);
            Append("Boot SHA-256: " + info.Sha256);
            SetState("boot.img таңдалды — ADB/Fastboot дайында", true);
            RefreshStepButtons();
        }
        catch (Exception ex)
        {
            selectedBoot = null;
            bootCandidateVerified = false;
            ResetAfterBootChange();
            Append("boot.img қатесі: " + ex.Message);
            SetState("boot.img таңдау қатесі");
        }
    }

    private void ResolveTools()
    {
        adbPath = null;
        fastbootPath = null;

        foreach (var root in CandidateToolsRoots())
        {
            try
            {
                var pointer = Path.Combine(root, "current.txt");
                if (File.Exists(pointer))
                {
                    var dir = File.ReadAllText(pointer).Trim();
                    var a = Path.Combine(dir, "adb.exe");
                    var f = Path.Combine(dir, "fastboot.exe");
                    if (File.Exists(a) && File.Exists(f))
                    {
                        adbPath = a;
                        fastbootPath = f;
                        toolsReady = true;
                        return;
                    }
                }
            }
            catch { }
        }

        var pathAdb = FindOnPath("adb.exe");
        var pathFastboot = FindOnPath("fastboot.exe");
        if (pathAdb is not null && pathFastboot is not null)
        {
            adbPath = pathAdb;
            fastbootPath = pathFastboot;
            toolsReady = true;
            return;
        }

        toolsReady = false;
    }

    private static IEnumerable<string> CandidateToolsRoots()
    {
        yield return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AlmasFox", "Tools");
        yield return Path.Combine(Path.GetTempPath(), "AlmasFox", "Tools");
    }

    private static string? FindOnPath(string name)
    {
        foreach (var part in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var p = Path.Combine(part.Trim().Trim('"'), name);
                if (File.Exists(p)) return p;
            }
            catch { }
        }
        return null;
    }

    private static bool CanWriteDirectory(string dir)
    {
        try
        {
            Directory.CreateDirectory(dir);
            var test = Path.Combine(dir, ".write-test-" + Guid.NewGuid().ToString("N") + ".tmp");
            File.WriteAllText(test, "ok");
            File.Delete(test);
            return true;
        }
        catch { return false; }
    }

    private static string GetWritableToolsRoot()
    {
        foreach (var root in CandidateToolsRoots())
            if (CanWriteDirectory(root)) return root;
        throw new UnauthorizedAccessException("AlmasFox Platform Tools үшін жазуға болатын папка таба алмады.");
    }

    private async Task StopExistingAdbAsync()
    {
        try
        {
            ResolveTools();
            if (adbPath is not null && File.Exists(adbPath))
            {
                try { await RunAsync(adbPath, ["kill-server"], 7000); } catch { }
            }
        }
        catch { }

        try
        {
            foreach (var p in Process.GetProcessesByName("adb"))
            {
                try
                {
                    p.Kill(true);
                    await p.WaitForExitAsync();
                }
                catch { }
                finally { p.Dispose(); }
            }
        }
        catch { }
    }

    private async Task InstallPlatformToolsAsync()
    {
        try
        {
            SetState("Google Platform Tools дайындалуда");
            Append("Platform Tools v0.6 қауіпсіз орнатуы басталды.");
            Append("Ескі platform-tools папкасы өшірілмейді және adb.exe үстінен жазылмайды.");

            await StopExistingAdbAsync();

            var toolsRoot = GetWritableToolsRoot();
            var downloadDir = Path.Combine(toolsRoot, "downloads");
            Directory.CreateDirectory(downloadDir);
            var id = DateTime.Now.ToString("yyyyMMdd-HHmmss") + "-" + Guid.NewGuid().ToString("N")[..8];
            var zip = Path.Combine(downloadDir, "platform-tools-" + id + ".zip");
            var staging = Path.Combine(toolsRoot, "versions", id);
            Directory.CreateDirectory(staging);

            Append("Google ресми Platform Tools жүктелуде…");
            using (var http = new HttpClient { Timeout = TimeSpan.FromMinutes(3) })
            {
                using var response = await http.GetAsync(PlatformToolsUrl, HttpCompletionOption.ResponseHeadersRead);
                response.EnsureSuccessStatusCode();
                await using var input = await response.Content.ReadAsStreamAsync();
                await using var output = new FileStream(zip, FileMode.CreateNew, FileAccess.Write, FileShare.None);
                await input.CopyToAsync(output);
            }

            if (new FileInfo(zip).Length < 2 * 1024 * 1024)
                throw new InvalidDataException("Platform Tools архиві тым кішкентай — жүктеу толық емес.");

            ZipFile.ExtractToDirectory(zip, staging, overwriteFiles: false);
            var platformDir = Path.Combine(staging, "platform-tools");
            var newAdb = Path.Combine(platformDir, "adb.exe");
            var newFastboot = Path.Combine(platformDir, "fastboot.exe");
            if (!File.Exists(newAdb) || !File.Exists(newFastboot))
                throw new FileNotFoundException("Архивтен adb.exe/fastboot.exe табылмады.");

            var adbTest = await RunAsync(newAdb, ["version"], 15000);
            var fastbootTest = await RunAsync(newFastboot, ["--version"], 15000);
            if (adbTest.ExitCode != 0 || fastbootTest.ExitCode != 0)
                throw new InvalidOperationException("Жаңа adb/fastboot іске қосылмады.");

            var pointer = Path.Combine(toolsRoot, "current.txt");
            var pointerTmp = pointer + ".tmp";
            File.WriteAllText(pointerTmp, platformDir, Encoding.UTF8);
            File.Move(pointerTmp, pointer, true);

            try { File.Delete(zip); } catch { }

            adbPath = newAdb;
            fastbootPath = newFastboot;
            toolsReady = true;
            Append("ADB: " + adbPath);
            Append("Fastboot: " + fastbootPath);
            Append("Platform Tools жаңа бөлек папкаға орнатылды ✓");
            SetState("ADB/Fastboot дайын", true);
            RefreshStepButtons();
        }
        catch (UnauthorizedAccessException ex)
        {
            toolsReady = false;
            Append("Platform Tools рұқсат қатесі: " + ex.Message);
            SetState("Windows файл рұқсаты кедергі жасады");
            MessageBox.Show(
                "Windows Platform Tools папкасына жазуға рұқсат бермеді.\n\nAlmasFox v0.6 ескі adb.exe үстінен жазбайды. Егер бұл хабар шықса, Windows Defender/антивирус AlmasFox-ты блоктамағанын тексеріп, қолданбаны қайта аш.\n\nҚате: " + ex.Message,
                "Файл рұқсаты",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
        }
        catch (Exception ex)
        {
            toolsReady = false;
            Append("Platform Tools қатесі: " + ex.Message);
            SetState("ADB/Fastboot дайындалмады");
            MessageBox.Show(ex.Message, "Platform Tools қатесі", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private async Task<bool> EnsureToolsAsync()
    {
        ResolveTools();
        if (adbPath is not null && fastbootPath is not null)
        {
            try
            {
                var a = await RunAsync(adbPath, ["version"], 10000);
                var f = await RunAsync(fastbootPath, ["--version"], 10000);
                if (a.ExitCode == 0 && f.ExitCode == 0)
                {
                    toolsReady = true;
                    return true;
                }
            }
            catch { }
        }

        var yes = MessageBox.Show(
            "ADB/Fastboot дайын емес. Google ресми Platform Tools автоматты жүктелсін бе?",
            "ADB/Fastboot керек",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Question);
        if (yes != DialogResult.Yes) return false;
        await InstallPlatformToolsAsync();
        ResolveTools();
        return toolsReady && adbPath is not null && fastbootPath is not null;
    }

    private async Task<List<(string Serial, string State)>> GetAdbDevicesAsync()
    {
        if (adbPath is null) return [];
        var r = await RunAsync(adbPath, ["devices"], 10000);
        var rows = new List<(string Serial, string State)>();
        foreach (var raw in r.StdOut.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries))
        {
            var line = raw.Trim();
            if (line.StartsWith("List of devices", StringComparison.OrdinalIgnoreCase) || line.StartsWith("* daemon", StringComparison.OrdinalIgnoreCase)) continue;
            var parts = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length >= 2) rows.Add((parts[0], parts[1]));
        }
        return rows;
    }

    private async Task<bool> AuthorizeUsbAsync(bool showIntro)
    {
        if (!bootCandidateVerified)
        {
            MessageBox.Show("Алдымен ноутбуктан boot.img таңда.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return false;
        }
        if (!await EnsureToolsAsync()) return false;

        if (showIntro)
        {
            var go = MessageBox.Show(
                "USB рұқсатын дұрыс беру:\n\n1) Телефон Android-та ашық тұрсын. Fastboot-қа ӘЛІ кірме.\n2) DATA тасымалдайтын USB кабель қолдан. Тек зарядтайтын кабель жарамайды.\n3) Developer options → USB debugging қос.\n4) Телефон құлпын ашық ұста.\n5) «Allow USB debugging?» шыққанда «Always allow from this computer» белгіле де Allow бас.\n\nAlmasFox 75 секундқа дейін телефондағы RSA рұқсатын күтеді.",
                "USB кабель және RSA рұқсаты",
                MessageBoxButtons.OKCancel,
                MessageBoxIcon.Information);
            if (go != DialogResult.OK) return false;
        }

        usbAuthorized = false;
        deviceVerified = false;
        bootSent = false;
        patchedVerified = false;
        temporaryBootPassed = false;
        adbSerial = null;
        RefreshStepButtons();

        SetUsb("ADB/RSA рұқсаты күтілуде…");
        SetState("USB кабель мен телефон рұқсатын тексеруде");

        try
        {
            await RunAsync(adbPath!, ["start-server"], 10000);
            var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(75);
            bool offlineRestarted = false;
            bool unauthorizedSeen = false;

            while (DateTime.UtcNow < deadline)
            {
                var rows = await GetAdbDevicesAsync();

                var authorized = rows.Where(x => x.State.Equals("device", StringComparison.OrdinalIgnoreCase)).ToList();
                if (authorized.Count > 1)
                {
                    SetUsb("бірнеше телефон табылды");
                    MessageBox.Show("Бірнеше ADB құрылғы табылды. Басқа телефон/эмуляторларды ажыратып, қайта тексер.", "USB", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return false;
                }
                if (authorized.Count == 1)
                {
                    adbSerial = authorized[0].Serial;
                    usbAuthorized = true;
                    SetUsb("рұқсат берілді ✓", true);
                    SetState("USB/RSA дайын — телефон моделін тексер", true);
                    Append("ADB/RSA рұқсаты расталды: " + adbSerial);
                    RefreshStepButtons();
                    return true;
                }

                if (rows.Any(x => x.State.Equals("unauthorized", StringComparison.OrdinalIgnoreCase)))
                {
                    if (!unauthorizedSeen)
                    {
                        unauthorizedSeen = true;
                        Append("ADB күйі: unauthorized — телефон экранындағы RSA терезесінен Allow бас.");
                    }
                    SetUsb("unauthorized — телефоннан Allow бас");
                }
                else if (rows.Any(x => x.State.Equals("offline", StringComparison.OrdinalIgnoreCase)))
                {
                    SetUsb("offline — ADB қайта іске қосылуда");
                    if (!offlineRestarted)
                    {
                        offlineRestarted = true;
                        Append("ADB offline: server қайта іске қосылады.");
                        await RunAsync(adbPath!, ["kill-server"], 7000);
                        await Task.Delay(800);
                        await RunAsync(adbPath!, ["start-server"], 10000);
                    }
                }
                else
                {
                    SetUsb("құрылғы көрінбейді — DATA кабель/USB debugging тексер");
                }

                await Task.Delay(2000);
            }

            var finalRows = await GetAdbDevicesAsync();
            if (finalRows.Any(x => x.State.Equals("unauthorized", StringComparison.OrdinalIgnoreCase)))
            {
                MessageBox.Show(
                    "Телефон көрінді, бірақ RSA рұқсаты берілмеді.\n\nТелефон экранын аш → «Allow USB debugging?» → «Always allow from this computer» → Allow.\n\nЕгер терезе шықпаса: Developer options → Revoke USB debugging authorizations жасап, кабельді қайта жалға.",
                    "ADB рұқсаты керек",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
            }
            else
            {
                MessageBox.Show(
                    "Телефон ADB арқылы көрінбеді.\n\n• DATA тасымалдайтын USB кабель қолдан\n• USB debugging қос\n• Телефон құлпын аш\n• Windows-та Android/Google USB driver керек болуы мүмкін\n• Басқа USB портты байқап көр\n\nТек зарядтайтын кабельмен ADB жұмыс істемейді.",
                    "USB құрылғы табылмады",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
            }

            SetState("USB рұқсаты жоқ");
            RefreshStepButtons();
            return false;
        }
        catch (Exception ex)
        {
            Append("USB тексеру қатесі: " + ex.Message);
            SetUsb("қате");
            SetState("USB тексерілмеді");
            RefreshStepButtons();
            return false;
        }
    }

    private async Task CheckDeviceAsync()
    {
        if (!usbAuthorized || string.IsNullOrWhiteSpace(adbSerial))
        {
            if (!await AuthorizeUsbAsync(showIntro: false)) return;
        }

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
            bootSent = false;
            patchedVerified = false;
            temporaryBootPassed = false;

            if (!deviceVerified)
            {
                SetState("құрылғы Mi A3 / laurel_sprout емес");
                MessageBox.Show("Бұл жинақ Mi A3 (laurel_sprout) үшін. Басқа құрылғыда flash бұғатталды.", "Қауіпсіздік", MessageBoxButtons.OK, MessageBoxIcon.Stop);
                RefreshStepButtons();
                return;
            }

            SetState("Mi A3 / laurel_sprout расталды — Magisk кезеңіне дайын", true);
            Append("Mi A3 / laurel_sprout расталды ✓");
            RefreshStepButtons();
        }
        catch (Exception ex)
        {
            deviceVerified = false;
            Append("Модель тексеру қатесі: " + ex.Message);
            SetState("телефон тексерілмеді");
            RefreshStepButtons();
        }
    }

    private async Task PushBootAndOpenMagiskAsync()
    {
        if (!bootCandidateVerified || selectedBoot is null)
        {
            MessageBox.Show("Алдымен boot.img таңда.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (!deviceVerified)
        {
            MessageBox.Show("Алдымен USB рұқсатын және телефон моделін тексер.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (!await EnsureAuthorizedConnectionAsync()) return;

        try
        {
            SetState("boot.img телефонға жіберілуде");
            await RunRequiredAsync(adbPath!, AdbArgs("shell", "mkdir", "-p", "/sdcard/Download/AlmasFox"));
            await RunRequiredAsync(adbPath!, AdbArgs("push", selectedBoot, "/sdcard/Download/AlmasFox/AlmasFox-input-boot.img"), 120000);
            Append("boot.img телефонға жіберілді: Download/AlmasFox/AlmasFox-input-boot.img");
            bootSent = true;

            var open = await RunAsync(adbPath!, AdbArgs("shell", "monkey", "-p", "com.topjohnwu.magisk", "-c", "android.intent.category.LAUNCHER", "1"), 20000);
            if (open.ExitCode != 0 || !open.All.Contains("Events injected", StringComparison.OrdinalIgnoreCase))
            {
                Append("Magisk автоматты ашылмады. Телефоннан Magisk-ті қолмен ашуға болады.");
                var installed = await RunAsync(adbPath!, AdbArgs("shell", "pm", "path", "com.topjohnwu.magisk"), 10000);
                if (installed.ExitCode != 0 || !installed.StdOut.Contains("package:", StringComparison.OrdinalIgnoreCase))
                {
                    MessageBox.Show("Телефоннан ресми Magisk қолданбасын орнат. Одан кейін осы кезеңді қайта бас.", "Magisk табылмады", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
            }

            SetState("Magisk patch күтілуде");
            RefreshStepButtons();
            MessageBox.Show(
                "Телефондағы Magisk-та:\n\nInstall → Select and Patch a File → Download/AlmasFox/AlmasFox-input-boot.img таңда.\n\nPatch аяқталсын. Содан кейін ноутбуктегі «6. PATCHED ФАЙЛДЫ НОУТБУККЕ АЛУ» батырмасын бас.\n\nFastboot-қа ӘЛІ кірме.",
                "Magisk patch",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            bootSent = false;
            Append("Magisk кезеңі қатесі: " + ex.Message);
            SetState("Magisk кезеңі тоқтады");
            RefreshStepButtons();
        }
    }

    private async Task PullPatchedAsync()
    {
        if (!bootSent)
        {
            MessageBox.Show("Алдымен boot.img телефонға жіберіп, Magisk-пен patch жаса.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (!await EnsureAuthorizedConnectionAsync()) return;

        try
        {
            SetState("Magisk patched файл ізделуде");
            var ls = await RunAsync(adbPath!, AdbArgs("shell", "sh", "-c", "ls -t /sdcard/Download/magisk_patched*.img /sdcard/Download/AlmasFox/magisk_patched*.img 2>/dev/null | head -n 1"), 15000);
            var remote = ls.StdOut.Trim().Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries).FirstOrDefault()?.Trim() ?? "";
            if (string.IsNullOrWhiteSpace(remote))
            {
                MessageBox.Show("magisk_patched*.img табылмады. Magisk patch аяқталғанын тексер.", "Patched файл жоқ", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            var outDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "AlmasFox");
            Directory.CreateDirectory(outDir);
            var local = Path.Combine(outDir, "AlmasFox-patched-ready.img");
            await RunRequiredAsync(adbPath!, AdbArgs("pull", remote, local), 120000);

            var info = ValidateBootFile(local);
            if (!info.MagicOk || !info.SizeOk)
                throw new InvalidOperationException("Patched файл Android boot image ретінде өтпеді.");

            if (selectedBoot is not null)
            {
                var originalSize = new FileInfo(selectedBoot).Length;
                if (Math.Abs(info.Size - originalSize) > 16L * 1024 * 1024)
                    throw new InvalidOperationException("Patched image көлемі бастапқы boot.img-ден күмәнді деңгейде өзгеше.");
            }

            patchedBoot = local;
            patchedVerified = true;
            temporaryBootPassed = false;
            Append("Patched image ноутбукке алынды: " + local);
            Append("Patched SHA-256: " + info.Sha256);
            SetState("patched boot дайын — енді ғана Fastboot тестке өтуге болады", true);
            RefreshStepButtons();
        }
        catch (Exception ex)
        {
            patchedVerified = false;
            patchedBoot = null;
            Append("Patched файл қатесі: " + ex.Message);
            SetState("patched файл өтпеді");
            RefreshStepButtons();
        }
    }

    private async Task TemporaryBootTestAsync()
    {
        if (!patchedVerified || patchedBoot is null)
        {
            MessageBox.Show("Алдымен patched image-ті ноутбукке ал.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (!await EnsureAuthorizedConnectionAsync()) return;
        if (!await EnsureToolsAsync() || fastbootPath is null) return;

        var confirm = MessageBox.Show(
            "Қазір ғана телефон Fastboot режиміне өтеді.\n\nAlmasFox алдымен bootloader unlocked екенін тексереді. Locked болса ТОҚТАЙДЫ және unlock жасамайды.\n\nСодан кейін patched boot уақытша fastboot boot арқылы сыналады. Partition-ға әзірге жазылмайды.\n\nЖалғастырамыз ба?",
            "Fastboot уақытша тест",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning);
        if (confirm != DialogResult.Yes) return;

        try
        {
            SetState("bootloader режиміне өтуде");
            await RunRequiredAsync(adbPath!, AdbArgs("reboot", "bootloader"), 20000);
            if (!await WaitForFastbootAsync(TimeSpan.FromSeconds(45)))
                throw new InvalidOperationException("Fastboot құрылғы табылмады. USB data кабель/driver тексер.");

            if (!await IsBootloaderUnlockedAsync())
            {
                temporaryBootPassed = false;
                SetState("bootloader LOCKED — процесс тоқтады");
                MessageBox.Show("Bootloader құлыптаулы. AlmasFox unlock жасамайды, өйткені unlock деректерді өшіруі мүмкін.", "Деректер қорғалды", MessageBoxButtons.OK, MessageBoxIcon.Stop);
                RefreshStepButtons();
                return;
            }

            var product = await GetFastbootVarAsync("product");
            Append("Fastboot product: " + product);
            if (!string.IsNullOrWhiteSpace(product) && !product.Contains("laurel", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Fastboot product Mi A3-ке сәйкес емес: " + product);

            SetState("patched boot уақытша жүктелуде");
            Append("fastboot boot: partition-ға жазбайтын уақытша тест.");
            var boot = await RunAsync(fastbootPath, ["boot", patchedBoot], 120000);
            Append(boot.All.Trim());
            if (boot.ExitCode != 0)
                throw new InvalidOperationException("fastboot boot сәтсіз. Тұрақты flash бұғатталды.");

            SetState("Android қайта ашылғанын күту");
            var serial = await WaitForSingleAdbDeviceAsync(TimeSpan.FromMinutes(4));
            if (serial is null)
                throw new InvalidOperationException("Android/ADB уақытында қайта ашылмады. Тұрақты flash жасалмайды.");

            adbSerial = serial;
            usbAuthorized = true;
            SetUsb("қайта қосылды ✓", true);

            MessageBox.Show("Телефон Android-қа уақытша patched boot-пен ашылды. Егер Magisk root рұқсатын сұраса Allow бас.", "Root тест", MessageBoxButtons.OK, MessageBoxIcon.Information);

            var rootOk = await WaitForRootAsync(TimeSpan.FromSeconds(55));
            if (!rootOk)
                throw new InvalidOperationException("Уақытша boot ашылды, бірақ uid=0 root расталмады. Тұрақты flash бұғатталды.");

            temporaryBootPassed = true;
            SetState("УАҚЫТША ROOT ТЕСТ ӨТТІ ✓", true);
            Append("Уақытша patched boot + uid=0 расталды ✓");
            RefreshStepButtons();
            MessageBox.Show("Уақытша boot және root тест өтті. Енді ғана тұрақты орнату батырмасы ашылды.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            temporaryBootPassed = false;
            Append("Уақытша тест тоқтады: " + ex.Message);
            SetState("тұрақты flash бұғатталды");
            RefreshStepButtons();
            MessageBox.Show(ex.Message + "\n\nAlmasFox boot partition-ға тұрақты ештеңе жазған жоқ.", "Қауіпсіз тоқтау", MessageBoxButtons.OK, MessageBoxIcon.Warning);
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
            "Patched boot уақытша тесттен сәтті өтті.\n\nAlmasFox тек белсенді boot_a немесе boot_b бөліміне жазады.\nunlock / erase / format / userdata / -w қолданылмайды.\n\nТұрақты Root орнатылсын ба?",
            "Тұрақты Root орнату",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning);
        if (confirm != DialogResult.Yes) return;

        if (!await EnsureAuthorizedConnectionAsync()) return;
        if (!await EnsureToolsAsync() || fastbootPath is null) return;

        try
        {
            await RunRequiredAsync(adbPath!, AdbArgs("reboot", "bootloader"), 20000);
            if (!await WaitForFastbootAsync(TimeSpan.FromSeconds(45)))
                throw new InvalidOperationException("Fastboot табылмады.");
            if (!await IsBootloaderUnlockedAsync())
                throw new InvalidOperationException("Bootloader unlocked емес. Flash жасалмайды.");

            var slot = (await GetFastbootVarAsync("current-slot")).Trim().ToLowerInvariant();
            if (slot != "a" && slot != "b")
                throw new InvalidOperationException("Белсенді A/B слот анықталмады: " + slot);

            var partition = "boot_" + slot;
            Append("Белсенді слот: " + slot.ToUpperInvariant());
            Append("Flash мақсаты: " + partition);
            SetState(partition + " жазылуда");

            var flash = await RunAsync(fastbootPath, ["flash", partition, patchedBoot], 120000);
            Append(flash.All.Trim());
            if (flash.ExitCode != 0)
                throw new InvalidOperationException("Fastboot flash сәтсіз аяқталды.");

            await RunRequiredAsync(fastbootPath, ["reboot"], 30000);
            SetState("Root орнатылды — Android жүктелуде", true);
            Append("Дайын. userdata/data бөліміне команда жіберілген жоқ.");
        }
        catch (Exception ex)
        {
            Append("Тұрақты орнату қатесі: " + ex.Message);
            SetState("орнату тоқтады");
            MessageBox.Show(ex.Message, "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private async Task<bool> EnsureAuthorizedConnectionAsync()
    {
        if (!await EnsureToolsAsync() || adbPath is null) return false;
        if (!usbAuthorized || string.IsNullOrWhiteSpace(adbSerial))
            return await AuthorizeUsbAsync(showIntro: false);

        var rows = await GetAdbDevicesAsync();
        if (rows.Any(x => x.Serial.Equals(adbSerial, StringComparison.OrdinalIgnoreCase) && x.State.Equals("device", StringComparison.OrdinalIgnoreCase)))
            return true;

        usbAuthorized = false;
        deviceVerified = false;
        bootSent = false;
        RefreshStepButtons();
        SetUsb("байланыс үзілді — қайта рұқсат керек");
        return await AuthorizeUsbAsync(showIntro: false);
    }

    private IEnumerable<string> AdbArgs(params string[] args)
    {
        if (!string.IsNullOrWhiteSpace(adbSerial))
        {
            yield return "-s";
            yield return adbSerial!;
        }
        foreach (var a in args) yield return a;
    }

    private async Task<string> GetPropAsync(string name)
    {
        var r = await RunAsync(adbPath!, AdbArgs("shell", "getprop", name), 12000);
        if (r.ExitCode != 0) throw new InvalidOperationException(r.All);
        return r.StdOut.Trim();
    }

    private async Task<string?> WaitForSingleAdbDeviceAsync(TimeSpan timeout)
    {
        var end = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < end)
        {
            await Task.Delay(2500);
            try
            {
                var rows = await GetAdbDevicesAsync();
                var devices = rows.Where(x => x.State.Equals("device", StringComparison.OrdinalIgnoreCase)).ToList();
                if (devices.Count == 1) return devices[0].Serial;
            }
            catch { }
        }
        return null;
    }

    private async Task<bool> WaitForRootAsync(TimeSpan timeout)
    {
        var end = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < end)
        {
            try
            {
                var r = await RunAsync(adbPath!, AdbArgs("shell", "su", "-c", "id"), 9000);
                Append("Root test: " + r.All.Trim());
                if (r.All.Contains("uid=0", StringComparison.OrdinalIgnoreCase)) return true;
            }
            catch (TimeoutException)
            {
                Append("Root рұқсаты күтілуде… телефондағы Magisk Allow терезесін тексер.");
            }
            catch { }
            await Task.Delay(1800);
        }
        return false;
    }

    private async Task<bool> WaitForFastbootAsync(TimeSpan timeout)
    {
        var end = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < end)
        {
            await Task.Delay(1800);
            var r = await RunAsync(fastbootPath!, ["devices"], 8000);
            var lines = r.StdOut.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries);
            if (lines.Length == 1 && lines[0].Contains("fastboot", StringComparison.OrdinalIgnoreCase)) return true;
            if (lines.Length > 1)
                throw new InvalidOperationException("Бірнеше Fastboot құрылғы табылды. Басқаларын ажырат.");
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

        var fallback = await RunAsync(fastbootPath!, ["oem", "device-info"], 15000);
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
        var r = await RunAsync(fastbootPath!, ["getvar", name], 15000);
        foreach (var raw in r.All.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries))
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
        if (!File.Exists(exe)) throw new FileNotFoundException(Path.GetFileName(exe) + " табылмады", exe);
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
            throw new InvalidOperationException(r.All.Trim().Length == 0 ? Path.GetFileName(exe) + " қате коды: " + r.ExitCode : r.All.Trim());
    }

    private readonly record struct BootInfo(bool MagicOk, bool SizeOk, long Size, string Sha256);
    private readonly record struct RunResult(int ExitCode, string StdOut, string StdErr)
    {
        public string All => (StdOut + Environment.NewLine + StdErr).Trim();
    }
}

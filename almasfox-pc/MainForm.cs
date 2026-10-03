using System.Diagnostics;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;

namespace AlmasFoxPC;

public sealed class MainForm : Form
{
    private readonly TextBox log = new();
    private readonly Label state = new();
    private readonly Button permanentButton;

    private string? adbPath;
    private string? fastbootPath;
    private string? selectedBoot;
    private string? patchedBoot;
    private bool deviceVerified;
    private bool bootCandidateVerified;
    private bool patchedVerified;
    private bool temporaryBootPassed;

    private const string PlatformToolsUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip";

    public MainForm()
    {
        Text = "AlmasFox PC v0.4";
        Width = 820;
        Height = 820;
        MinimumSize = new Size(720, 680);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(13, 15, 18);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10f);

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 4,
            Padding = new Padding(22),
            BackColor = BackColor,
            AutoScroll = true
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        var title = new Label
        {
            Text = "ALMAS FOX PC",
            Font = new Font("Segoe UI", 27f, FontStyle.Bold),
            ForeColor = Color.White,
            AutoSize = true,
            Margin = new Padding(0, 0, 0, 2)
        };
        root.Controls.Add(title);

        var subtitle = new Label
        {
            Text = "Mi A3 (laurel_sprout) • boot.img тексеру • Magisk • қауіпсіз Fastboot",
            Font = new Font("Segoe UI", 11f),
            ForeColor = Color.FromArgb(190, 194, 200),
            AutoSize = true,
            Margin = new Padding(0, 0, 0, 18)
        };
        root.Controls.Add(subtitle);

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            WrapContents = true,
            FlowDirection = FlowDirection.LeftToRight,
            Margin = new Padding(0, 0, 0, 14)
        };

        var toolsButton = MakeButton("1. ADB / FASTBOOT ДАЙЫНДАУ");
        toolsButton.Click += async (_, _) => await InstallPlatformToolsAsync();
        buttons.Controls.Add(toolsButton);

        var deviceButton = MakeButton("2. ТЕЛЕФОНДЫ ТЕКСЕРУ");
        deviceButton.Click += async (_, _) => await CheckDeviceAsync();
        buttons.Controls.Add(deviceButton);

        var bootButton = MakeButton("3. BOOT.IMG ТАҢДАУ");
        bootButton.Click += (_, _) => ChooseBoot();
        buttons.Controls.Add(bootButton);

        var magiskButton = MakeButton("4. MAGISK PATCH КЕЗЕҢІ");
        magiskButton.Click += async (_, _) => await PushBootAndOpenMagiskAsync();
        buttons.Controls.Add(magiskButton);

        var pullButton = MakeButton("5. PATCHED ФАЙЛДЫ АЛУ");
        pullButton.Click += async (_, _) => await PullPatchedAsync();
        buttons.Controls.Add(pullButton);

        var testButton = MakeButton("6. УАҚЫТША BOOT СЫНАУ");
        testButton.Click += async (_, _) => await TemporaryBootTestAsync();
        buttons.Controls.Add(testButton);

        permanentButton = MakeButton("7. ТҰРАҚТЫ ROOT ОРНАТУ");
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

        state.Text = "Күйі: бастауға дайын";
        state.Dock = DockStyle.Top;
        state.Height = 34;
        state.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        state.ForeColor = Color.White;

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
            Text = "Қорғаныс: AlmasFox PC bootloader-ді АШПАЙДЫ және erase / format / userdata / -w командаларын қолданбайды. Bootloader locked болса процесс тоқтайды.",
            Dock = DockStyle.Bottom,
            Height = 48,
            ForeColor = Color.FromArgb(255, 190, 95),
            Padding = new Padding(0, 8, 0, 0)
        };

        panel.Controls.Add(log);
        panel.Controls.Add(warning);
        panel.Controls.Add(state);
        root.Controls.Add(panel);

        Controls.Add(root);
        ResolveTools();
        Append("AlmasFox PC v0.4 іске қосылды.");
        Append("Алдымен ADB/Fastboot дайындап, телефонда USB debugging қосыңыз.");
    }

    private Button MakeButton(string text)
    {
        return new Button
        {
            Text = text,
            Width = 350,
            Height = 54,
            FlatStyle = FlatStyle.Flat,
            BackColor = Color.FromArgb(43, 47, 54),
            ForeColor = Color.White,
            FlatAppearance = { BorderColor = Color.FromArgb(67, 72, 80), BorderSize = 1 },
            Margin = new Padding(0, 0, 10, 10)
        };
    }

    private void Append(string text)
    {
        var line = $"[{DateTime.Now:HH:mm:ss}] {text}{Environment.NewLine}";
        if (InvokeRequired)
        {
            BeginInvoke(() => Append(text));
            return;
        }
        log.AppendText(line);
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

    private void ResolveTools()
    {
        var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AlmasFox", "Tools", "platform-tools");
        var localAdb = Path.Combine(root, "adb.exe");
        var localFastboot = Path.Combine(root, "fastboot.exe");
        if (File.Exists(localAdb) && File.Exists(localFastboot))
        {
            adbPath = localAdb;
            fastbootPath = localFastboot;
            return;
        }
        adbPath = FindOnPath("adb.exe");
        fastbootPath = FindOnPath("fastboot.exe");
    }

    private static string? FindOnPath(string name)
    {
        var path = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (var part in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
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
            var data = await http.GetByteArrayAsync(PlatformToolsUrl);
            await File.WriteAllBytesAsync(zip, data);

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
            Append("Қате: " + ex.Message);
            SetState("Platform Tools қатесі");
        }
    }

    private async Task<bool> EnsureAdbAsync()
    {
        ResolveTools();
        if (adbPath is null)
        {
            MessageBox.Show("Алдымен «ADB / FASTBOOT ДАЙЫНДАУ» батырмасын бас.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return false;
        }
        var r = await RunAsync(adbPath, ["devices"]);
        var lines = r.All.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries);
        if (lines.Any(x => x.EndsWith("\tunauthorized", StringComparison.OrdinalIgnoreCase)))
        {
            MessageBox.Show("Телефон экранындағы USB debugging рұқсатын қабылда.", "USB рұқсаты", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return false;
        }
        if (!lines.Any(x => x.EndsWith("\tdevice", StringComparison.OrdinalIgnoreCase)))
        {
            MessageBox.Show("Телефон ADB арқылы табылмады. USB debugging қосып, кабельді қайта жалға.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return false;
        }
        return true;
    }

    private async Task CheckDeviceAsync()
    {
        if (!await EnsureAdbAsync()) return;
        try
        {
            SetState("құрылғы тексерілуде");
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
            bootCandidateVerified = false;
            patchedVerified = false;
            temporaryBootPassed = false;
            permanentButton.Enabled = false;

            if (!deviceVerified)
            {
                SetState("бұл v0.4 тек Mi A3 үшін");
                MessageBox.Show("Құрылғы laurel_sprout емес. Қауіпсіздік үшін flash бұғатталды.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Stop);
                return;
            }
            SetState("Mi A3 / laurel_sprout расталды", true);
        }
        catch (Exception ex)
        {
            Append("Қате: " + ex.Message);
            SetState("құрылғы тексерілмеді");
        }
    }

    private void ChooseBoot()
    {
        if (!deviceVerified)
        {
            MessageBox.Show("Алдымен телефонды USB арқылы тексер.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        using var dlg = new OpenFileDialog
        {
            Title = "Mi A3 үшін boot.img таңда",
            Filter = "Android boot image (*.img)|*.img|Барлық файл (*.*)|*.*"
        };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;

        try
        {
            var info = ValidateBootFile(dlg.FileName);
            selectedBoot = dlg.FileName;
            bootCandidateVerified = info.MagicOk && info.SizeOk;
            patchedVerified = false;
            temporaryBootPassed = false;
            permanentButton.Enabled = false;

            Append("boot.img: " + selectedBoot);
            Append($"Көлемі: {info.Size / 1024 / 1024} МБ");
            Append("ANDROID! header: " + (info.MagicOk ? "OK" : "ҚАТЕ"));
            Append("SHA-256: " + info.Sha256);

            if (!bootCandidateVerified)
            {
                SetState("boot.img жарамсыз");
                MessageBox.Show("Файл Android boot image ретінде өтпеді. Flash бұғатталды.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Stop);
                return;
            }

            SetState("boot candidate өтті — уақытша сынақ міндетті", true);
            Append("Ескерту: header/көлем тексеруі ROM сәйкестігіне 100% кепіл емес. Сондықтан тұрақты flash тек уақытша boot + root тесттен кейін ғана ашылады.");
        }
        catch (Exception ex)
        {
            Append("boot.img қатесі: " + ex.Message);
            SetState("boot тексеру қатесі");
        }
    }

    private async Task PushBootAndOpenMagiskAsync()
    {
        if (!bootCandidateVerified || selectedBoot is null)
        {
            MessageBox.Show("Алдымен дұрыс boot.img таңда.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (!await EnsureAdbAsync()) return;
        try
        {
            SetState("boot.img телефонға жіберілуде");
            await RunRequiredAsync(adbPath!, ["shell", "mkdir", "-p", "/sdcard/Download/AlmasFox"]);
            await RunRequiredAsync(adbPath!, ["push", selectedBoot, "/sdcard/Download/AlmasFox/AlmasFox-input-boot.img"], 120000);
            Append("boot.img телефонға жіберілді: Download/AlmasFox/AlmasFox-input-boot.img");

            var open = await RunAsync(adbPath!, ["shell", "monkey", "-p", "com.topjohnwu.magisk", "-c", "android.intent.category.LAUNCHER", "1"]);
            if (open.ExitCode != 0)
            {
                Append("Magisk қолданбасы табылмады. Телефон браузерінде ресми Magisk беті ашылады.");
                await RunAsync(adbPath!, ["shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "https://github.com/topjohnwu/Magisk/releases/latest"]);
            }

            SetState("Magisk patch күтілуде");
            MessageBox.Show(
                "Magisk ашылған соң:\n\nInstall → Select and Patch a File → Download/AlmasFox/AlmasFox-input-boot.img таңда.\n\nPatch біткеннен кейін осы PC қолданбасында «PATCHED ФАЙЛДЫ АЛУ» бас.\n\nMagisk сыртқы қолданбаға толық автомат patch API бермейтіндіктен осы бір растау қолмен жасалады.",
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
        if (!await EnsureAdbAsync()) return;
        try
        {
            SetState("Magisk patched файл ізделуде");
            var ls = await RunAsync(adbPath!, ["shell", "sh", "-c", "ls -t /sdcard/Download/magisk_patched*.img /sdcard/Download/AlmasFox/magisk_patched*.img 2>/dev/null | head -n 1"]);
            var remote = ls.StdOut.Trim();
            if (string.IsNullOrWhiteSpace(remote))
            {
                MessageBox.Show("magisk_patched*.img табылмады. Magisk patch аяқталғанын тексер.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            var outDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "AlmasFox");
            Directory.CreateDirectory(outDir);
            var local = Path.Combine(outDir, "AlmasFox-patched-ready.img");
            await RunRequiredAsync(adbPath!, ["pull", remote, local], 120000);

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
            Append("Patched image: " + local);
            Append("Patched SHA-256: " + info.Sha256);
            SetState("patched boot дайын — уақытша сынақ керек", true);
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
            MessageBox.Show("Алдымен Magisk patched image-ті ал.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (!await EnsureAdbAsync()) return;
        ResolveTools();
        if (fastbootPath is null)
        {
            MessageBox.Show("Fastboot табылмады. Platform Tools дайында.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        try
        {
            SetState("bootloader режиміне өтуде");
            Append("Телефон bootloader режиміне қайта жүктеледі. Деректер форматталмайды.");
            await RunRequiredAsync(adbPath!, ["reboot", "bootloader"]);
            if (!await WaitForFastbootAsync(TimeSpan.FromSeconds(35))) throw new InvalidOperationException("Fastboot құрылғы табылмады");

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
            Append("fastboot boot тек уақытша RAM/boot сынағы; partition-ға жазбайды.");
            var boot = await RunAsync(fastbootPath, ["boot", patchedBoot], 90000);
            Append(boot.All.Trim());
            if (boot.ExitCode != 0) throw new InvalidOperationException("fastboot boot сәтсіз болды. Тұрақты flash бұғатталды.");

            SetState("Android қайта ашылғанын күту");
            if (!await WaitForAdbAsync(TimeSpan.FromMinutes(3)))
                throw new InvalidOperationException("Android/ADB уақытында қайта ашылмады. Тұрақты flash жасалмайды.");

            var root = await RunAsync(adbPath!, ["shell", "su", "-c", "id"], 15000);
            Append("Root test: " + root.All.Trim());
            if (!root.All.Contains("uid=0", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Уақытша boot ашылды, бірақ root расталмады. Тұрақты flash бұғатталды.");

            temporaryBootPassed = true;
            permanentButton.Enabled = true;
            SetState("УАҚЫТША ROOT ТЕСТ ӨТТІ ✓", true);
            MessageBox.Show("Телефон patched boot-пен ашылды және uid=0 расталды. Енді ғана тұрақты орнату батырмасы ашылды.", "AlmasFox", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            temporaryBootPassed = false;
            permanentButton.Enabled = false;
            Append("Уақытша тест тоқтады: " + ex.Message);
            SetState("тұрақты flash бұғатталды");
            MessageBox.Show(ex.Message + "\n\nAlmasFox boot partition-ға ештеңе жазған жоқ.", "Қауіпсіз тоқтау", MessageBoxButtons.OK, MessageBoxIcon.Warning);
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
            "Patched boot уақытша сынақтан өтті.\n\nAlmasFox тек белсенді boot_a/boot_b бөліміне жазады. userdata/data/format/unlock командалары қолданылмайды.\n\nЖалғастырамыз ба?",
            "Тұрақты Root орнату",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning);
        if (confirm != DialogResult.Yes) return;

        if (!await EnsureAdbAsync()) return;
        try
        {
            await RunRequiredAsync(adbPath!, ["reboot", "bootloader"]);
            if (!await WaitForFastbootAsync(TimeSpan.FromSeconds(35))) throw new InvalidOperationException("Fastboot табылмады");
            if (!await IsBootloaderUnlockedAsync()) throw new InvalidOperationException("Bootloader unlocked емес. Flash жасалмайды.");

            var slot = (await GetFastbootVarAsync("current-slot")).Trim().ToLowerInvariant();
            if (slot != "a" && slot != "b") throw new InvalidOperationException("Белсенді A/B слот анықталмады: " + slot);
            var partition = "boot_" + slot;
            Append("Белсенді слот: " + slot.ToUpperInvariant());
            Append("Flash мақсаты: " + partition);

            SetState(partition + " жазылуда");
            var flash = await RunAsync(fastbootPath!, ["flash", partition, patchedBoot], 120000);
            Append(flash.All.Trim());
            if (flash.ExitCode != 0) throw new InvalidOperationException("Fastboot flash сәтсіз аяқталды");

            await RunRequiredAsync(fastbootPath!, ["reboot"], 30000);
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

    private async Task<string> GetPropAsync(string name)
    {
        var r = await RunAsync(adbPath!, ["shell", "getprop", name]);
        return r.StdOut.Trim();
    }

    private async Task<bool> WaitForAdbAsync(TimeSpan timeout)
    {
        var end = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < end)
        {
            await Task.Delay(3000);
            var r = await RunAsync(adbPath!, ["devices"], 8000);
            if (r.All.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries).Any(x => x.EndsWith("\tdevice", StringComparison.OrdinalIgnoreCase)))
                return true;
        }
        return false;
    }

    private async Task<bool> WaitForFastbootAsync(TimeSpan timeout)
    {
        var end = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < end)
        {
            await Task.Delay(2000);
            var r = await RunAsync(fastbootPath!, ["devices"], 8000);
            if (!string.IsNullOrWhiteSpace(r.StdOut)) return true;
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
        var all = r.All;
        foreach (var raw in all.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries))
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
        if (r.ExitCode != 0) throw new InvalidOperationException(r.All.Trim().Length == 0 ? Path.GetFileName(exe) + " қате коды: " + r.ExitCode : r.All.Trim());
    }

    private readonly record struct BootInfo(bool MagicOk, bool SizeOk, long Size, string Sha256);
    private readonly record struct RunResult(int ExitCode, string StdOut, string StdErr)
    {
        public string All => (StdOut + Environment.NewLine + StdErr).Trim();
    }
}

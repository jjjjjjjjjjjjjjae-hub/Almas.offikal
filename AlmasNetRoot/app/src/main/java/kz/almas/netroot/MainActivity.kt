package kz.almas.netroot

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kz.almas.netroot.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val tuner = NetworkTuner()
    private val speedTest = SpeedTestDownloader()
    private var original: NetworkTuner.Snapshot? = null

    private val prefs by lazy { getSharedPreferences("almasnet", MODE_PRIVATE) }

    companion object {
        init {
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setTimeout(10)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnTest.setOnClickListener { refreshAndTest() }
        binding.btnDownloadTest.setOnClickListener { runDownloadSpeedTest() }
        binding.btnGaming.setOnClickListener { applyMode("Gaming") { tuner.applyGaming() } }
        binding.btnBalanced.setOnClickListener { applyMode("Balanced") { tuner.applyBalanced() } }
        binding.btnSpeed.setOnClickListener { applyMode("Speed") { tuner.applySpeed() } }
        binding.btnRestore.setOnClickListener { restore() }

        original = loadSnapshot()
        refreshAndTest()
    }

    private fun refreshAndTest() {
        lifecycleScope.launch {
            setBusy(true)
            val state = withContext(Dispatchers.IO) {
                val root = tuner.hasRoot()
                if (!root) return@withContext State(false, "-", "-", null, null, null, "Root рұқсаты жоқ")
                if (original == null) {
                    original = tuner.snapshot().also(::saveSnapshot)
                }
                val iface = tuner.activeInterface()
                val cc = tuner.currentCc()
                val p = tuner.ping()
                State(true, iface, cc, p.avg, p.jitter, p.loss, "Тест аяқталды")
            }
            render(state)
            setBusy(false)
        }
    }

    private fun runDownloadSpeedTest() {
        lifecycleScope.launch {
            setBusy(true)
            binding.progressDownload.progress = 0
            binding.txtDownload.text = "1 GB тесті басталды..."

            try {
                val result = withContext(Dispatchers.IO) {
                    speedTest.run { p ->
                        withContext(Dispatchers.Main) {
                            binding.progressDownload.progress = p.percent
                            binding.txtDownload.text = buildString {
                                append("Жүктелді: ${fmtGb(p.downloadedBytes)} / 1.00 GB (${p.percent}%)")
                                append("\nЖылдамдық: ${"%.1f".format(p.mbps)} Мбит/с")
                                append(" | Уақыт: ${fmtTime(p.elapsedSeconds)}")
                                p.remainingSeconds?.let { append(" | Қалды: ${fmtTime(it)}") }
                            }
                        }
                    }
                }

                binding.progressDownload.progress = if (result.complete) 100 else
                    ((result.downloadedBytes * 100L) / SpeedTestDownloader.TARGET_BYTES).toInt()
                binding.txtDownload.text = if (result.complete) {
                    "1 GB жүктелді\nОрташа жылдамдық: ${"%.1f".format(result.averageMbps)} Мбит/с | ${fmtTime(result.elapsedSeconds)}"
                } else {
                    "Тест ерте тоқтады: ${fmtGb(result.downloadedBytes)} GB\nОрташа: ${"%.1f".format(result.averageMbps)} Мбит/с"
                }
            } catch (e: Exception) {
                binding.txtDownload.text = "Жүктеу қатесі: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                setBusy(false)
            }
        }
    }

    private fun fmtGb(bytes: Long): String = "%.2f".format(bytes / 1_000_000_000.0)

    private fun fmtTime(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun applyMode(name: String, action: () -> String) {
        lifecycleScope.launch {
            setBusy(true)
            val result = withContext(Dispatchers.IO) {
                if (!tuner.hasRoot()) return@withContext "$name: Root рұқсаты жоқ"
                if (original == null) {
                    original = tuner.snapshot().also(::saveSnapshot)
                }
                val before = tuner.ping()
                val log = action()
                val after = tuner.ping()
                "$name MODE\nBefore: ${fmt(before.avg)} ms / jitter ${fmt(before.jitter)} ms / loss ${before.loss ?: -1}%\nAfter: ${fmt(after.avg)} ms / jitter ${fmt(after.jitter)} ms / loss ${after.loss ?: -1}%\n\n$log"
            }
            binding.txtLog.text = result
            refreshHeaderOnly()
            setBusy(false)
        }
    }

    private fun restore() {
        lifecycleScope.launch {
            setBusy(true)
            val msg = withContext(Dispatchers.IO) {
                val snap = original ?: return@withContext "Restore үшін бастапқы snapshot жоқ."
                tuner.restore(snap)
            }
            binding.txtLog.text = "RESTORE\n$msg"
            refreshHeaderOnly()
            setBusy(false)
        }
    }

    private fun refreshHeaderOnly() {
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                if (!tuner.hasRoot()) return@withContext State(false, "-", "-", null, null, null, "Root рұқсаты жоқ")
                val p = tuner.ping()
                State(true, tuner.activeInterface(), tuner.currentCc(), p.avg, p.jitter, p.loss, "")
            }
            render(state, keepLog = true)
        }
    }

    private fun render(state: State, keepLog: Boolean = false) {
        binding.txtRoot.text = if (state.root) "ROOT: OK" else "ROOT: ЖОҚ"
        binding.txtInterface.text = "Interface: ${state.iface}"
        binding.txtCc.text = "TCP: ${state.cc}"
        binding.txtPing.text = "Ping/Jitter: ${fmt(state.ping)} / ${fmt(state.jitter)} ms | Loss: ${state.loss ?: -1}%"
        if (!keepLog && state.message.isNotBlank()) binding.txtLog.text = state.message
    }

    private fun setBusy(busy: Boolean) {
        binding.btnTest.isEnabled = !busy
        binding.btnDownloadTest.isEnabled = !busy
        binding.btnGaming.isEnabled = !busy
        binding.btnBalanced.isEnabled = !busy
        binding.btnSpeed.isEnabled = !busy
        binding.btnRestore.isEnabled = !busy
    }

    private fun saveSnapshot(s: NetworkTuner.Snapshot) {
        prefs.edit()
            .putString("cc", s.cc)
            .putString("fastOpen", s.fastOpen)
            .putString("rmem", s.rmem)
            .putString("wmem", s.wmem)
            .putString("mtuProbing", s.mtuProbing)
            .apply()
    }

    private fun loadSnapshot(): NetworkTuner.Snapshot? {
        val cc = prefs.getString("cc", null) ?: return null
        return NetworkTuner.Snapshot(
            cc = cc,
            fastOpen = prefs.getString("fastOpen", "3") ?: "3",
            rmem = prefs.getString("rmem", "4096 87380 6291456") ?: "4096 87380 6291456",
            wmem = prefs.getString("wmem", "4096 16384 4194304") ?: "4096 16384 4194304",
            mtuProbing = prefs.getString("mtuProbing", "0") ?: "0"
        )
    }

    private fun fmt(v: Double?): String = if (v == null) "-" else "%.1f".format(v)

    data class State(
        val root: Boolean,
        val iface: String,
        val cc: String,
        val ping: Double?,
        val jitter: Double?,
        val loss: Int?,
        val message: String
    )
}

package kz.almas.netroot

import com.topjohnwu.superuser.Shell
import kotlin.math.abs
import kotlin.math.sqrt

class NetworkTuner {

    data class Snapshot(
        val cc: String,
        val fastOpen: String,
        val rmem: String,
        val wmem: String,
        val mtuProbing: String
    )

    data class PingStats(
        val avg: Double?,
        val jitter: Double?,
        val loss: Int?
    )

    fun hasRoot(): Boolean = try {
        Shell.getShell().isRoot
    } catch (_: Throwable) {
        false
    }

    fun activeInterface(): String {
        val route = run("ip route | awk '/default/ {print $5; exit}'")
        if (route.isNotBlank()) return route.trim()
        return run("cat /proc/net/route | awk '$2==\"00000000\" {print $1; exit}'").trim()
    }

    fun availableCc(): List<String> =
        run("cat /proc/sys/net/ipv4/tcp_available_congestion_control")
            .trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    fun currentCc(): String = run("cat /proc/sys/net/ipv4/tcp_congestion_control").trim()

    fun snapshot(): Snapshot = Snapshot(
        cc = currentCc(),
        fastOpen = run("cat /proc/sys/net/ipv4/tcp_fastopen").trim(),
        rmem = run("cat /proc/sys/net/ipv4/tcp_rmem").trim(),
        wmem = run("cat /proc/sys/net/ipv4/tcp_wmem").trim(),
        mtuProbing = run("cat /proc/sys/net/ipv4/tcp_mtu_probing").trim()
    )

    fun applyGaming(): String {
        val cc = chooseCc(preferBbr = true)
        val cmds = mutableListOf<String>()
        cmds += "sysctl -w net.ipv4.tcp_congestion_control=$cc"
        cmds += "sysctl -w net.ipv4.tcp_fastopen=3"
        cmds += "sysctl -w net.ipv4.tcp_rmem='4096 87380 4194304'"
        cmds += "sysctl -w net.ipv4.tcp_wmem='4096 65536 4194304'"
        cmds += "sysctl -w net.ipv4.tcp_mtu_probing=1"
        return execMany(cmds)
    }

    fun applyBalanced(): String {
        val cc = chooseCc(preferBbr = true)
        val cmds = listOf(
            "sysctl -w net.ipv4.tcp_congestion_control=$cc",
            "sysctl -w net.ipv4.tcp_fastopen=3",
            "sysctl -w net.ipv4.tcp_rmem='4096 131072 8388608'",
            "sysctl -w net.ipv4.tcp_wmem='4096 131072 8388608'",
            "sysctl -w net.ipv4.tcp_mtu_probing=1"
        )
        return execMany(cmds)
    }

    fun applySpeed(): String {
        val cc = chooseCc(preferBbr = true)
        val cmds = listOf(
            "sysctl -w net.ipv4.tcp_congestion_control=$cc",
            "sysctl -w net.ipv4.tcp_fastopen=3",
            "sysctl -w net.ipv4.tcp_rmem='4096 262144 16777216'",
            "sysctl -w net.ipv4.tcp_wmem='4096 262144 16777216'",
            "sysctl -w net.ipv4.tcp_mtu_probing=1"
        )
        return execMany(cmds)
    }

    fun restore(s: Snapshot): String {
        val cmds = listOf(
            "sysctl -w net.ipv4.tcp_congestion_control=${shellQuote(s.cc)}",
            "sysctl -w net.ipv4.tcp_fastopen=${shellQuote(s.fastOpen)}",
            "sysctl -w net.ipv4.tcp_rmem=${shellQuote(s.rmem)}",
            "sysctl -w net.ipv4.tcp_wmem=${shellQuote(s.wmem)}",
            "sysctl -w net.ipv4.tcp_mtu_probing=${shellQuote(s.mtuProbing)}"
        )
        return execMany(cmds)
    }

    fun ping(host: String = "1.1.1.1"): PingStats {
        val out = run("ping -c 6 -W 2 $host 2>/dev/null")
        val times = Regex("time[=<]([0-9.]+) ?ms")
            .findAll(out).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        val loss = Regex("(\\d+)% packet loss").find(out)?.groupValues?.get(1)?.toIntOrNull()
        if (times.isEmpty()) return PingStats(null, null, loss)
        val avg = times.average()
        val variance = times.map { (it - avg) * (it - avg) }.average()
        val std = sqrt(variance)
        val madDelta = if (times.size > 1) times.zipWithNext().map { abs(it.second - it.first) }.average() else std
        return PingStats(avg, madDelta, loss)
    }

    private fun chooseCc(preferBbr: Boolean): String {
        val available = availableCc()
        return when {
            preferBbr && "bbr" in available -> "bbr"
            "cubic" in available -> "cubic"
            available.isNotEmpty() -> available.first()
            else -> currentCc().ifBlank { "cubic" }
        }
    }

    private fun execMany(commands: List<String>): String {
        val result = Shell.cmd(*commands.toTypedArray()).exec()
        return buildString {
            append("exit=").append(result.code)
            if (result.out.isNotEmpty()) append("\n").append(result.out.joinToString("\n"))
            if (result.err.isNotEmpty()) append("\nERR:\n").append(result.err.joinToString("\n"))
        }
    }

    private fun run(command: String): String = Shell.cmd(command).exec().out.joinToString("\n")

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

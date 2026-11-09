package com.voicex.app.engine

import java.io.File

/**
 * Finds the performance cores. On the S23 Ultra (1x Cortex-X3, 2x A715, 2x A710, 3x A510) the four fastest are
 * cpu7 and three of cpu3..6. Cores are ranked by the kernel's cpu_capacity, falling back to cpuinfo_max_freq.
 */
object CpuTopology {
  data class Core(val id: Int, val capacity: Long, val maxFreqKhz: Long)

  fun cores(root: File = File("/sys/devices/system/cpu")): List<Core> {
    val dirs = root.listFiles { f -> f.isDirectory && f.name.matches(Regex("cpu\\d+")) } ?: return emptyList()
    return dirs.map { d ->
      val id = d.name.removePrefix("cpu").toInt()
      val cap = readLong(File(d, "cpu_capacity"))
      val freq = readLong(File(d, "cpufreq/cpuinfo_max_freq"))
      Core(id, cap, freq)
    }.sortedBy { it.id }
  }

  /** The [n] fastest core ids, fastest first; ties keep the higher id (big cores are numbered last on ARM). */
  fun fastest(n: Int, all: List<Core> = cores()): IntArray =
    all.sortedWith(compareByDescending<Core> { it.capacity }.thenByDescending { it.maxFreqKhz }.thenByDescending { it.id })
      .take(n.coerceAtLeast(1))
      .map { it.id }
      .toIntArray()

  private fun readLong(f: File): Long = runCatching { f.readText().trim().toLong() }.getOrDefault(0L)
}

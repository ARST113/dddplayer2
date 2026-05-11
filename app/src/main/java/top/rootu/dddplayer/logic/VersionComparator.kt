package top.rootu.dddplayer.logic

object VersionComparator {
    fun isRemoteNewer(local: String, remote: String): Boolean {
        val l = parse(local) ?: return false
        val r = parse(remote) ?: return false
        return when {
            r[0] != l[0] -> r[0] > l[0]
            r[1] != l[1] -> r[1] > l[1]
            else -> r[2] > l[2]
        }
    }

    private fun parse(value: String): List<Int>? {
        val cleaned = value.removePrefix("v")
        val nums = Regex("(\\d+)").findAll(cleaned).take(3).map { it.value.toIntOrNull() ?: 0 }.toMutableList()
        if (nums.isEmpty()) return null
        while (nums.size < 3) nums.add(0)
        return nums
    }
}

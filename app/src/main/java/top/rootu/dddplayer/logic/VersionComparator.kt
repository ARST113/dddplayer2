package top.rootu.dddplayer.logic

object VersionComparator {
    fun isRemoteNewer(local: String, remote: String): Boolean {
        val l = parse(local) ?: return false
        val r = parse(remote) ?: return false
        return r > l
    }

    private fun parse(value: String): Triple<Int, Int, Int>? {
        val cleaned = value.removePrefix("v")
        val nums = Regex("(\\d+)").findAll(cleaned).take(3).map { it.value.toIntOrNull() ?: 0 }.toList()
        if (nums.size < 3) return null
        return Triple(nums[0], nums[1], nums[2])
    }
}

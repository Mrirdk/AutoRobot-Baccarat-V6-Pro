package com.stats.baccaratoverlay

import kotlin.math.roundToInt

data class DerivedRoadSummary(
    val redCount: Int,
    val blueCount: Int,
    val statusText: String
)

data class V6HybridReport(
    val lastCapturedLabel: String,
    val patternName: String,
    val recommendedSide: String, // "BANKER", "PLAYER", or "WAIT"
    val confidence: Int,
    val probB: Int,
    val probP: Int,
    val robotCallout: String,
    val mainRoadStatusText: String,
    val derivedRoadStatusText: String
)

/**
 * Deterministic Baccarat V6 Hybrid Pattern Engine.
 * Zero random usage: strictly evaluates Big Road streaks, 1-1 ping-pong chop,
 * 2-2 double chop, 1-2/2-1 rhythmic cycles, and Derived Road consensus.
 */
object BaccaratV6HybridEngine {

    fun evaluate(fullHistory: List<Char>): V6HybridReport {
        if (fullHistory.isEmpty()) {
            return V6HybridReport(
                lastCapturedLabel = "CHƯA CÓ",
                patternName = "CHỜ DỮ LIỆU",
                recommendedSide = "WAIT",
                confidence = 50,
                probB = 50,
                probP = 50,
                robotCallout = "Đặt khung xanh khớp bảng cầu để Robot quét!",
                mainRoadStatusText = "Thế cầu: Đang chờ nhận diện hạt cầu...",
                derivedRoadStatusText = "Cầu phụ: Chưa đủ cột"
            )
        }

        val lastRaw = fullHistory.last()
        val lastCapturedLabel = when (lastRaw) {
            'B' -> "BANKER (CÁI)"
            'P' -> "PLAYER (CON)"
            'T' -> "TIE (HÒA)"
            else -> "KHÔNG RÕ"
        }

        // Filter out Tie ('T') to evaluate pure Banker/Player rhythm accurately
        val pb = fullHistory.filter { it == 'B' || it == 'P' }
        val bCount = fullHistory.count { it == 'B' }
        val pCount = fullHistory.count { it == 'P' }
        val tCount = fullHistory.count { it == 'T' }

        if (pb.size < 3) {
            return V6HybridReport(
                lastCapturedLabel = lastCapturedLabel,
                patternName = "KHỞI TẠO (${pb.size}/3)",
                recommendedSide = "WAIT",
                confidence = 52,
                probB = 50,
                probP = 50,
                robotCallout = "Đã bắt ${pb.size} tay ($bCount Cái - $pCount Con). Chờ thêm 1 tay!",
                mainRoadStatusText = "Thế cầu: Đang lấy mẫu (${pb.size} tay B/P, $tCount Hòa)",
                derivedRoadStatusText = "Cầu phụ: Cần tối thiểu 3 tay"
            )
        }

        val derived = computeDerivedRoadConsensus(pb)
        val n = pb.size
        val last = pb[n - 1]
        val oppositeOfLast = if (last == 'B') 'P' else 'B'

        // 1. Check Trailing Streak ("Cầu Bệt")
        var streakLen = 1
        for (i in n - 2 downTo 0) {
            if (pb[i] == last) streakLen++ else break
        }

        // 2. Check Trailing 1-1 Ping-Pong Alternation ("Cầu Nhảy 1-1")
        var chopLen = 1
        for (i in n - 1 downTo 1) {
            if (pb[i] != pb[i - 1]) chopLen++ else break
        }

        // 3. Check 2-2 Double Chop ("Cầu Nhảy 2-2": e.g. PPBBPP or BBPPBB)
        val isTwoTwoPattern = detectTwoTwoPattern(pb)

        // 4. Check 1-2 / 2-1 Rhythmic Pattern ("Cầu 1-2 / 2-1")
        val oneTwoSignal = detectOneTwoCycle(pb)

        // Decision Priority (Deterministic)
        if (chopLen >= 4) {
            // Strong 1-1 Ping-Pong Chop!
            val nextSide = if (oppositeOfLast == 'B') "BANKER" else "PLAYER"
            val conf = (74 + (chopLen - 4) * 4 + if (derived.blueCount >= derived.redCount) 4 else 0).coerceIn(72, 94)
            val probB = if (nextSide == "BANKER") conf else (100 - conf)
            val probP = 100 - probB
            return V6HybridReport(
                lastCapturedLabel = lastCapturedLabel,
                patternName = "CẦU NHẢY 1-1 ($chopLen nhịp)",
                recommendedSide = nextSide,
                confidence = conf,
                probB = probB,
                probP = probP,
                robotCallout = "⚡ BẮT CẦU NHẢY 1-1 → VÀO $nextSide ($conf%)",
                mainRoadStatusText = "Thế cầu: Nhảy 1-1 liên tục $chopLen nhịp (Đảo chiều)",
                derivedRoadStatusText = derived.statusText
            )
        }

        if (streakLen >= 3) {
            // Active Streak ("Cầu Bệt")
            val nextSide = if (last == 'B') "BANKER" else "PLAYER"
            val conf = (72 + (streakLen - 3) * 4 + if (derived.redCount >= derived.blueCount) 5 else 0).coerceIn(70, 93)
            val probB = if (nextSide == "BANKER") conf else (100 - conf)
            val probP = 100 - probB
            return V6HybridReport(
                lastCapturedLabel = lastCapturedLabel,
                patternName = "CẦU BỆT ($streakLen tay)",
                recommendedSide = nextSide,
                confidence = conf,
                probB = probB,
                probP = probP,
                robotCallout = "🔥 THEO CẦU BỆT ($streakLen tay) → VÀO $nextSide ($conf%)",
                mainRoadStatusText = "Thế cầu: Bệt ${if (last == 'B') "CÁI" else "CON"} $streakLen tay liên tiếp",
                derivedRoadStatusText = derived.statusText
            )
        }

        if (isTwoTwoPattern != null) {
            val (nextChar, desc) = isTwoTwoPattern
            val nextSide = if (nextChar == 'B') "BANKER" else "PLAYER"
            val conf = 78
            val probB = if (nextSide == "BANKER") conf else (100 - conf)
            val probP = 100 - probB
            return V6HybridReport(
                lastCapturedLabel = lastCapturedLabel,
                patternName = "CẦU NHẢY 2-2",
                recommendedSide = nextSide,
                confidence = conf,
                probB = probB,
                probP = probP,
                robotCallout = "🎯 BẮT CẦU NHẢY 2-2 → VÀO $nextSide ($conf%)",
                mainRoadStatusText = "Thế cầu: $desc",
                derivedRoadStatusText = derived.statusText
            )
        }

        if (oneTwoSignal != null) {
            val (nextChar, desc) = oneTwoSignal
            val nextSide = if (nextChar == 'B') "BANKER" else "PLAYER"
            val conf = 73
            val probB = if (nextSide == "BANKER") conf else (100 - conf)
            val probP = 100 - probB
            return V6HybridReport(
                lastCapturedLabel = lastCapturedLabel,
                patternName = "CẦU NHẢY 1-2 / 2-1",
                recommendedSide = nextSide,
                confidence = conf,
                probB = probB,
                probP = probP,
                robotCallout = "🔄 NHỊP CẦU 1-2 → VÀO $nextSide ($conf%)",
                mainRoadStatusText = "Thế cầu: $desc",
                derivedRoadStatusText = derived.statusText
            )
        }

        if (chopLen == 3) {
            // Early 1-1 Chop starting (3 alternations: B-P-B or P-B-P)
            val nextSide = if (oppositeOfLast == 'B') "BANKER" else "PLAYER"
            val conf = 71
            val probB = if (nextSide == "BANKER") conf else (100 - conf)
            val probP = 100 - probB
            return V6HybridReport(
                lastCapturedLabel = lastCapturedLabel,
                patternName = "CHỚM NHẢY 1-1",
                recommendedSide = nextSide,
                confidence = conf,
                probB = probB,
                probP = probP,
                robotCallout = "⚡ CHỚM CẦU NHẢY 1-1 → VÀO $nextSide ($conf%)",
                mainRoadStatusText = "Thế cầu: Đang đảo 1-1 (3 nhịp gần nhất)",
                derivedRoadStatusText = derived.statusText
            )
        }

        // 5. Fallback: Recent 10-hand window bias + Derived Road consensus
        val window = pb.takeLast(10)
        val winB = window.count { it == 'B' }
        val winP = window.count { it == 'P' }
        val nextSide = if (winB > winP) "BANKER" else if (winP > winB) "PLAYER" else (if (oppositeOfLast == 'B') "BANKER" else "PLAYER")
        val rawRatio = if (nextSide == "BANKER") (winB.toDouble() / window.size) else (winP.toDouble() / window.size)
        val conf = (58 + (rawRatio * 18).roundToInt()).coerceIn(58, 74)
        val probB = if (nextSide == "BANKER") conf else (100 - conf)
        val probP = 100 - probB

        return V6HybridReport(
            lastCapturedLabel = lastCapturedLabel,
            patternName = "CẦU BIẾN THIÊN ($bCount B - $pCount P)",
            recommendedSide = nextSide,
            confidence = conf,
            probB = probB,
            probP = probP,
            robotCallout = "📊 ƯU TIÊN $nextSide ($conf%) | Cái:$bCount Con:$pCount",
            mainRoadStatusText = "Thế cầu: Nghiêng nhẹ $nextSide (10 tay: $winB B - $winP P)",
            derivedRoadStatusText = derived.statusText
        )
    }

    private fun detectTwoTwoPattern(pb: List<Char>): Pair<Char, String>? {
        if (pb.size < 5) return null
        val tail5 = pb.takeLast(5).joinToString("")
        // e.g. BBPPB -> needs one more B to complete 2-2
        if (tail5 == "BBPPB") return 'B' to "Nhịp 2-2 (BB-PP-B) → Đánh đủ cặp BANKER"
        if (tail5 == "PPBBP") return 'P' to "Nhịp 2-2 (PP-BB-P) → Đánh đủ cặp PLAYER"
        if (pb.size >= 6) {
            val tail6 = pb.takeLast(6).joinToString("")
            // e.g. BBPPBB -> flip to P
            if (tail6 == "BBPPBB") return 'P' to "Nhịp 2-2 (BB-PP-BB) → Đảo sang PLAYER"
            if (tail6 == "PPBBPP") return 'B' to "Nhịp 2-2 (PP-BB-PP) → Đảo sang BANKER"
        }
        return null
    }

    private fun detectOneTwoCycle(pb: List<Char>): Pair<Char, String>? {
        if (pb.size < 5) return null
        val tail5 = pb.takeLast(5).joinToString("")
        // Cycle: P BB P B -> next B (1-2-1-2)
        if (tail5 == "PBBPB") return 'B' to "Nhịp 1-2 (P-BB-P-B) → Tiếp BANKER"
        if (tail5 == "BPPBP") return 'P' to "Nhịp 1-2 (B-PP-B-P) → Tiếp PLAYER"
        // Cycle: P BB P BB -> next P
        if (pb.size >= 6) {
            val tail6 = pb.takeLast(6).joinToString("")
            if (tail6 == "PBBPBB") return 'P' to "Nhịp 1-2 chuẩn (P-BB-P-BB) → Đảo PLAYER"
            if (tail6 == "BPPBPP") return 'B' to "Nhịp 1-2 chuẩn (B-PP-B-PP) → Đảo BANKER"
        }
        return null
    }

    private fun computeDerivedRoadConsensus(pb: List<Char>): DerivedRoadSummary {
        // Build Big Road columns from pb
        val columns = mutableListOf<Int>()
        var currentRun = 1
        for (i in 1 until pb.size) {
            if (pb[i] == pb[i - 1]) {
                currentRun++
            } else {
                columns.add(currentRun)
                currentRun = 1
            }
        }
        columns.add(currentRun)

        if (columns.size < 3) {
            return DerivedRoadSummary(1, 1, "Cầu phụ: Đang hình thành cột")
        }

        // Big Eye Boy & Small Road symmetry comparison
        var red = 0
        var blue = 0
        for (c in 1 until columns.size) {
            if (columns[c] == columns[c - 1]) red++ else blue++
        }
        if (columns.size >= 4) {
            for (c in 2 until columns.size) {
                if (columns[c] == columns[c - 2]) red++ else blue++
            }
        }

        val status = if (red > blue) {
            "Cầu phụ: Đồng thuận ĐỎ ($red Đỏ / $blue Xanh)"
        } else if (blue > red) {
            "Cầu phụ: Đồng thuận XANH ($blue Xanh / $red Đỏ)"
        } else {
            "Cầu phụ: Cân bằng ($red Đỏ - $blue Xanh)"
        }
        return DerivedRoadSummary(red, blue, status)
    }
}

package io.github.mzmknight.subtracker.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asserts the on-device engine against the corpus generated from the server
 * engine. This is the guard against the two implementations drifting: the same
 * cases and the same expected answers are checked by backend/test/vectors.test.js
 * and by this test, so a divergence breaks a build rather than quietly
 * mispricing a year of someone's history.
 *
 * Regenerate the corpus with `npm run vectors` in backend/.
 */
class ProjectionVectorTest {

    @Serializable
    private data class Corpus(val cases: List<Case>, val generatedCases: Int = 0)

    @Serializable
    private data class Case(
        val name: String,
        val subscription: VectorSchedule,
        val prices: List<VectorPrice>,
        val today: String,
        @SerialName("window_start") val windowStart: String,
        @SerialName("window_end") val windowEnd: String,
        val expected: List<Expected>,
    )

    @Serializable
    private data class VectorSchedule(
        @SerialName("anchor_date") override val anchorDate: String,
        @SerialName("cycle_unit") override val cycleUnit: String,
        @SerialName("cycle_count") override val cycleCount: Int,
        override val status: String,
        @SerialName("end_date") override val endDate: String? = null,
    ) : Projection.Schedule

    @Serializable
    private data class VectorPrice(
        @SerialName("amount_minor") override val amountMinor: Long,
        @SerialName("effective_from") override val effectiveFrom: String,
    ) : Projection.Price

    @Serializable
    private data class Expected(
        val due: String,
        @SerialName("amount_minor") val amountMinor: Long,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun corpus(): Corpus = json.decodeFromString(PROJECTION_VECTORS_JSON)

    @Test
    fun theCorpusIsPresentAndSubstantial() {
        val loaded = corpus()
        assertTrue(loaded.cases.size >= 60, "expected a broad corpus, got ${loaded.cases.size}")
        assertTrue(
            loaded.cases.sumOf { it.expected.size } > 500,
            "corpus should cover hundreds of charges",
        )
    }

    @Test
    fun everyCaseMatchesTheServerEngine() {
        val failures = mutableListOf<String>()

        for (case in corpus().cases) {
            val produced = Projection.generate(
                schedule = case.subscription,
                prices = case.prices,
                windowStart = PlainDate.parse(case.windowStart),
                windowEnd = PlainDate.parse(case.windowEnd),
                today = PlainDate.parse(case.today),
            )

            val gotDates = produced.map { it.due.iso }
            val wantDates = case.expected.map { it.due }
            if (gotDates != wantDates) {
                failures += "${case.name}\n    dates expected ${wantDates.take(8)}…(${wantDates.size})" +
                    "\n    dates produced ${gotDates.take(8)}…(${gotDates.size})"
                continue
            }

            val gotAmounts = produced.map { it.amountMinor }
            val wantAmounts = case.expected.map { it.amountMinor }
            if (gotAmounts != wantAmounts) {
                failures += "${case.name}\n    amounts expected ${wantAmounts.take(8)}" +
                    "\n    amounts produced ${gotAmounts.take(8)}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "on-device engine disagrees with the server engine in ${failures.size} case(s):\n" +
                failures.joinToString("\n"),
        )
    }

    @Test
    fun theHardCasesAreActuallyInTheCorpus() {
        // A corpus that quietly stopped covering the interesting cases would
        // pass forever while proving nothing.
        val names = corpus().cases.map { it.name }
        assertTrue(names.any { it.contains("31st") }, "month-end clamping must be covered")
        assertTrue(names.any { it.contains("29 February") }, "leap-year anchors must be covered")
        assertTrue(names.any { it.contains("price rise") }, "mid-history repricing must be covered")
        assertTrue(names.any { it.contains("cancellation") }, "truncation must be covered")
        assertTrue(names.any { it.contains("fast-forwards") }, "long histories must be covered")
        assertTrue(names.count { it.startsWith("randomised") } >= 30, "randomised breadth expected")
    }

    @Test
    fun theKnownAnswersAreStillRight() {
        // Independent of the corpus: if the generator itself were broken, the
        // comparison above would agree on the wrong answer. These are the values
        // verified by hand against a live PocketBase instance.
        val netflix = VectorSchedule("2026-01-31", "month", 1, "active", null)
        val dates = Projection.generateOccurrences(
            netflix,
            PlainDate.parse("2026-01-01"),
            PlainDate.parse("2026-06-30"),
            PlainDate.parse("2026-08-09"),
        ).map { it.iso }

        assertEquals(
            listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31", "2026-06-30"),
            dates,
            "31 Jan -> 28 Feb -> 31 Mar: the clamp must not become permanent",
        )

        val prime = VectorSchedule("2024-02-29", "year", 1, "active", null)
        assertEquals(
            listOf("2024-02-29", "2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"),
            Projection.generateOccurrences(
                prime,
                PlainDate.parse("2024-01-01"),
                PlainDate.parse("2028-12-31"),
                PlainDate.parse("2026-08-09"),
            ).map { it.iso },
        )
    }
}

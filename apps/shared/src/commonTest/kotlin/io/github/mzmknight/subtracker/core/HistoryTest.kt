package io.github.mzmknight.subtracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.github.mzmknight.subtracker.sync.ChargeOverrideRecord
import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

/**
 * History is what the new tab reads, and it is entirely derived — so these are
 * the only tests standing between a wrong price period and a wrong answer to
 * "what did I spend in March".
 */
class HistoryTest {

    private val today = PlainDate.parse("2026-08-10")

    private fun sub(
        id: String,
        name: String,
        anchor: String,
        colour: String = "",
        deleted: Boolean = false,
    ) = SubscriptionRecord(
        id = id, updatedAt = "1", deleted = deleted, name = name, colour = colour,
        anchorDate = anchor, cycleUnit = "month", cycleCount = 1, status = "active",
    )

    private fun price(id: String, subscriptionId: String, minor: Long, from: String) =
        PricePeriodRecord(
            id = id, updatedAt = "1", subscriptionId = subscriptionId,
            amountMinor = minor, effectiveFrom = from,
        )

    private val netflix = sub("s1", "Netflix", "2026-01-15", colour = "#E50914")
    private val spotify = sub("s2", "Spotify", "2026-03-01", colour = "#1DB954")

    private val subscriptions = listOf(netflix, spotify)
    private val prices = listOf(
        price("p1", "s1", 1099, "2026-01-15"),
        price("p2", "s2", 1199, "2026-03-01"),
    )

    private fun build(
        subs: List<SubscriptionRecord> = subscriptions,
        pricePeriods: List<PricePeriodRecord> = prices,
        overrides: List<ChargeOverrideRecord> = emptyList(),
    ) = History.months(subs, pricePeriods, overrides, today)

    @Test
    fun monthsComeBackNewestFirst() {
        val months = build().map { it.month }
        assertEquals(months.sortedDescending(), months, "the tab reads top-down from now")
    }

    @Test
    fun aMonthBeforeTheSecondSubscriptionStartedHasOneShare() {
        val february = build().first { it.month == "2026-02" }
        assertEquals(1, february.shares.size)
        assertEquals("Netflix", february.shares.single().name)
        assertEquals(1099, february.totalMinor)
    }

    @Test
    fun sharesAreLargestFirstAndCarryTheSubscriptionsOwnColour() {
        val august = build().first { it.month == "2026-08" }

        assertEquals(2298, august.totalMinor)
        assertEquals(listOf("Spotify", "Netflix"), august.shares.map { it.name })
        assertEquals("#1DB954", august.shares.first().colourHex)

        // The legend prints these next to the amounts; they have to agree.
        assertEquals(52, august.percentOf(august.shares[0]))
        assertEquals(47, august.percentOf(august.shares[1]))
    }

    @Test
    fun aTinyShareNeverRendersAsZeroPercent() {
        // A £0.10 add-on against a £10.99 subscription is 0.9%, which truncates
        // to "0%" sitting next to a non-zero amount — a row that contradicts itself.
        val addOn = sub("s3", "Add-on", "2026-01-20")
        val months = build(
            subs = listOf(netflix, addOn),
            pricePeriods = listOf(price("p1", "s1", 1099, "2026-01-15"), price("p3", "s3", 10, "2026-01-20")),
        )
        val january = months.first { it.month == "2026-01" }
        val tiny = january.shares.first { it.name == "Add-on" }

        assertTrue(january.percentOf(tiny) >= 1, "a real cost must not read as 0%")
    }

    @Test
    fun deletedSubscriptionsLeaveNoHistoryBehind() {
        val months = build(subs = listOf(netflix, spotify.copy(deleted = true)))
        assertTrue(
            months.none { month -> month.shares.any { it.name == "Spotify" } },
            "deleting is a decision that it should never have been recorded",
        )
    }

    @Test
    fun aSkippedChargeStaysInTheMonthButCostsNothing() {
        val skipped = ChargeOverrideRecord(
            id = ChargeOverrideRecord.idFor("s1", "2026-02-15"),
            updatedAt = "2",
            subscriptionId = "s1",
            dueDate = "2026-02-15",
            kind = ChargeOverrideRecord.KIND_SKIPPED,
        )
        val february = build(overrides = listOf(skipped)).first { it.month == "2026-02" }

        assertEquals(0, february.totalMinor, "a skipped charge costs nothing")
        assertEquals(1, february.chargeCount, "but it still happened, and stays visible")
    }

    @Test
    fun historyStopsAtTheCurrentMonth() {
        // A screen called History that opens on next year's forecasts buries the
        // thing it exists to show — the newest entry must be the month you are in.
        val months = build()

        assertEquals("2026-08", months.first().month)
        assertTrue(months.none { it.isFuture }, "forecasts belong on the dashboard")
        assertTrue(months.first().isCurrent)
    }

    @Test
    fun theCurrentMonthStillIncludesChargesLaterInIt() {
        // Netflix bills on the 15th and today is the 10th. Cutting the projection
        // at today would silently drop it and under-report the month.
        val august = build().first { it.month == "2026-08" }
        assertEquals(2298, august.totalMinor)
        assertEquals(2, august.chargeCount)
    }

    @Test
    fun forecastsAreAvailableOnRequestButNeverCountedAsSpent() {
        val months = History.months(subscriptions, prices, emptyList(), today, includeFuture = true)
        val september = months.first { it.month == "2026-09" }
        assertTrue(september.isFuture)

        val year = History.years(months).first { it.year == "2026" }

        // Jan and Feb are Netflix alone; March through August are both. September
        // onwards is forecast and must not be added to what was spent.
        assertEquals(1099 * 2 + 2298 * 6, year.totalMinor)
        assertTrue(year.months.any { it.isFuture }, "still listed, just not counted")
    }

    @Test
    fun everyMonthAgreesWithTheSumOfItsOwnShares() {
        // The composition bar divides the month total by these; if they ever
        // disagree the chart silently stops adding up to the headline figure.
        for (month in build()) {
            assertEquals(
                month.totalMinor,
                month.shares.sumOf { it.totalMinor },
                "${month.month} total must equal the sum of its shares",
            )
        }
    }

    @Test
    fun noSubscriptionsMeansNoHistoryRatherThanEmptyMonths() {
        assertEquals(emptyList(), History.months(emptyList(), emptyList(), emptyList(), today))
    }

    @Test
    fun aPriceRiseIsReflectedInTheMonthsAfterItAndNotBefore() {
        val months = build(
            pricePeriods = prices + price("p1b", "s1", 1499, "2026-06-01"),
        )
        assertEquals(1099, months.first { it.month == "2026-05" }.shares.first { it.name == "Netflix" }.totalMinor)
        assertEquals(1499, months.first { it.month == "2026-06" }.shares.first { it.name == "Netflix" }.totalMinor)
    }

    @Test
    fun aYearCarriesItsOwnBreakdownSoACollapsedYearStillSaysWhatItWas() {
        val year = History.years(build()).first { it.year == "2026" }

        assertEquals(listOf("Netflix", "Spotify"), year.shares.map { it.name })

        // Netflix bills Jan–Aug (8), Spotify Mar–Aug (6).
        val netflix = year.shares.first { it.name == "Netflix" }
        val spotify = year.shares.first { it.name == "Spotify" }
        assertEquals(8, netflix.chargeCount)
        assertEquals(6, spotify.chargeCount)
        assertEquals(1099 * 8, netflix.totalMinor)
        assertEquals(1199 * 6, spotify.totalMinor)

        // The bar is drawn from these; if they drift from the headline the chart
        // silently stops adding up to the number printed beside it.
        assertEquals(year.totalMinor, year.shares.sumOf { it.totalMinor })
        assertEquals(100, year.shares.sumOf { year.percentOf(it) } + 1)
    }

    @Test
    fun aYearsBreakdownIgnoresForecastMonths() {
        val withFuture = History.months(subscriptions, prices, emptyList(), today, includeFuture = true)
        val year = History.years(withFuture).first { it.year == "2026" }

        // Same answer as the settled-only view: forecasts are listed as months
        // but must not inflate what the year was spent on.
        assertEquals(1099 * 8, year.shares.first { it.name == "Netflix" }.totalMinor)
        assertEquals(year.totalMinor, year.shares.sumOf { it.totalMinor })
    }

    @Test
    fun yearsAreGroupedNewestFirst() {
        val older = sub("s4", "Old thing", "2024-05-10")
        val months = build(
            subs = listOf(netflix, older),
            pricePeriods = listOf(price("p1", "s1", 1099, "2026-01-15"), price("p4", "s4", 500, "2024-05-10")),
        )
        val years = History.years(months).map { it.year }

        assertEquals(years.sortedDescending(), years)
        assertTrue(years.contains("2024") && years.contains("2026"))
    }

    // ------------------------------------------------------------ appearance

    @Test
    fun knownBrandsGetTheirOwnColour() {
        assertEquals(BrandColours.suggest("Netflix"), BrandColours.suggest("Netflix"))
        assertEquals("#FF0033", BrandColours.suggest("YouTube Premium"))
        assertEquals("#1DB954", BrandColours.suggest("Spotify Family"))
        assertTrue(BrandColours.isKnownBrand("Claude Pro"))
    }

    @Test
    fun unknownNamesGetAStableColourRatherThanAllTheSameOne() {
        val first = BrandColours.suggest("Some Local Gym Thing")
        assertEquals(first, BrandColours.suggest("Some Local Gym Thing"))
        assertTrue(first in BrandColours.PALETTE)

        // Two devices adding the same subscription independently must agree, or a
        // sync reshuffles the colours in the history chart.
        val names = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta")
        assertTrue(
            names.map { BrandColours.suggest(it) }.toSet().size > 1,
            "hashing must actually spread names across the palette",
        )
    }

    @Test
    fun logoDomainsAreGuessedForKnownAndUnknownServices() {
        assertEquals("youtube.com", Logo.guessDomain("YouTube Premium"))
        assertEquals("netflix.com", Logo.guessDomain("Netflix"))
        assertEquals("claude.ai", Logo.guessDomain("Claude Pro"))
        // Unknown: the first word is the only honest guess available.
        assertEquals("bandcamp.com", Logo.guessDomain("Bandcamp"))
        // Too short to be a real domain, and not worth a network call.
        assertEquals(null, Logo.guessDomain("X"))
        assertEquals(null, Logo.guessDomain(""))
    }

    @Test
    fun theVendorsOwnDomainIsTriedBeforeAnyThirdParty() {
        val urls = Logo.candidateUrls("netflix.com")
        val firstThirdParty = urls.indexOfFirst { it.contains("duckduckgo") || it.contains("google") }

        assertTrue(urls.first().startsWith("https://netflix.com/"))
        assertTrue(firstThirdParty > 0, "a shared icon service must never be tried first")
        assertTrue(
            urls.take(firstThirdParty).all { it.contains("netflix.com") },
            "every request before the first leak must be to the vendor itself",
        )
        // DuckDuckGo before Google: the more privacy-respecting of the two gets
        // the first attempt, and Google is only reached when it returns an ICO
        // that no platform here can decode.
        assertTrue(
            urls.indexOfFirst { it.contains("duckduckgo") } <
                urls.indexOfFirst { it.contains("google") },
        )
    }

    @Test
    fun primeVideoResolvesToItsOwnSiteNotTheShop() {
        assertEquals("primevideo.com", Logo.guessDomain("Amazon Prime Video"))
        assertEquals("amazon.com", Logo.guessDomain("Amazon"))
        assertEquals("amazon.com", Logo.guessDomain("Amazon Prime"))
    }

    @Test
    fun logoBytesSurviveARoundTripAndJunkDecodesToNull() {
        val bytes = ByteArray(64) { (it * 7).toByte() }
        val decoded = Logo.decode(Logo.encode(bytes))

        assertNotNull(decoded)
        assertTrue(bytes.contentEquals(decoded))
        assertEquals(null, Logo.decode("not base64 at all !!"))
        assertEquals(null, Logo.decode(""))
    }
}

package com.jasonz3157.weightjournal

import org.junit.Assert.assertEquals
import org.junit.Test

class BmiReadingTest {
    @Test fun classifiesAfterRoundingToTheDisplayedPrecision() {
        val expected = mapOf(
            18.44 to BmiReading(18.4, BmiLevel.Low),
            18.45 to BmiReading(18.5, BmiLevel.Normal),
            22.94 to BmiReading(22.9, BmiLevel.Normal),
            22.95 to BmiReading(23.0, BmiLevel.Overweight),
            24.94 to BmiReading(24.9, BmiLevel.Overweight),
            24.95 to BmiReading(25.0, BmiLevel.Moderate),
            29.94 to BmiReading(29.9, BmiLevel.Moderate),
            29.95 to BmiReading(30.0, BmiLevel.Severe),
        )
        expected.forEach { (weight, reading) ->
            assertEquals(reading, bmiReading(weight, 100.0))
        }
    }
}

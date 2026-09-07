package com.rls.gps.ping.location;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RadiusUnitTest {

    @Test
    void convertsEveryUnitToKilometres() {
        assertThat(RadiusUnit.KM.toKilometres(5)).isEqualTo(5);
        assertThat(RadiusUnit.M.toKilometres(5000)).isEqualTo(5);
        assertThat(RadiusUnit.MI.toKilometres(1)).isCloseTo(1.609344, within(0.000001));
        assertThat(RadiusUnit.FT.toKilometres(3280.84)).isCloseTo(1.0, within(0.001));
    }

    @Test
    void makesALargeRadiusLargeWhateverUnitItIsWrittenIn() {
        // The reason the ceiling is checked in kilometres: these are the same distance.
        assertThat(RadiusUnit.M.toKilometres(5_000_000)).isEqualTo(RadiusUnit.KM.toKilometres(5_000));
    }
}

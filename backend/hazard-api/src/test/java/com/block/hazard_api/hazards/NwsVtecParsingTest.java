package com.block.hazard_api.hazards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** VTEC codes from NWS alerts: properties.parameters.VTEC, e.g. "/O.NEW.KSTO.WS.W.0012.../". */
class NwsVtecParsingTest {

    @Test
    void readsPhenomenonAndSignificance() {
        String[] codes = NwsAlertFetcher.vtecCodes(Map.of("VTEC",
                List.of("/O.NEW.KSTO.WS.W.0012.261012T0800Z-261013T1200Z/")));
        assertArrayEquals(new String[] {"WS", "W"}, codes);
    }

    @Test
    void usesTheFirstVtecString() {
        String[] codes = NwsAlertFetcher.vtecCodes(Map.of("VTEC", List.of(
                "/O.EXT.KHNX.FF.A.0003.000000T0000Z-261012T1800Z/", "/O.NEW.KHNX.FA.Y.0010.261012T0800Z-261012T1100Z/")));
        assertArrayEquals(new String[] {"FF", "A"}, codes);
    }

    @Test
    void missingOrOddVtecGivesNulls() {
        assertArrayEquals(new String[] {null, null}, NwsAlertFetcher.vtecCodes(null));
        assertArrayEquals(new String[] {null, null}, NwsAlertFetcher.vtecCodes(Map.of()));
        assertArrayEquals(new String[] {null, null}, NwsAlertFetcher.vtecCodes(Map.of("VTEC", List.of())));
        assertArrayEquals(new String[] {null, null}, NwsAlertFetcher.vtecCodes(Map.of("VTEC", List.of("not a code"))));
        assertArrayEquals(new String[] {null, null},
                NwsAlertFetcher.vtecCodes(Map.of("AWIPSidentifier", List.of("SPSSTO"))));
    }

    @Test
    void oldHazardConstructorLeavesVtecEmpty() {
        Hazard h = new Hazard("q", "USGS", "earthquake", HazardCategory.OTHER, "quake", 0.5, 36, -120, 10,
                null, null, null);
        assertNull(h.vtecPhenom());
        assertNull(h.vtecSig());
    }
}

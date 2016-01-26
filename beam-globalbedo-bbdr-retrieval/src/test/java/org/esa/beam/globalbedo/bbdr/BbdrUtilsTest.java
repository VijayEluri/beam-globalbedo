package org.esa.beam.globalbedo.bbdr;

import org.esa.beam.util.math.MathUtils;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * @author Olaf Danne
 * @author Marco Zuehlke
 */
public class BbdrUtilsTest {

    @Test
    public void testGetIndexBefore() {
        float[] values = {1.8f, 2.2f, 4.5f, 5.5f};
        assertEquals(0, BbdrUtils.getIndexBefore(1.2f, values));
        assertEquals(1, BbdrUtils.getIndexBefore(2.5f, values));
        assertEquals(2, BbdrUtils.getIndexBefore(4.6f, values));
        assertEquals(2, BbdrUtils.getIndexBefore(7.7f, values));
    }

    @Test
    public void testGetDoyFromYYYYMMDD() {
        String yyyymmdd = "20070101";
        int doy = BbdrUtils.getDoyFromYYYYMMDD(yyyymmdd);
        assertEquals(1, doy);

        yyyymmdd = "20071218";
        doy = BbdrUtils.getDoyFromYYYYMMDD(yyyymmdd);
        assertEquals(352, doy);
    }

    @Test
    public void testGetSzaFromUt() {
        double ut = 16.0;
        double doy = 111.0 + 4./24.;
        double lat = MathUtils.DTOR * 45;
        double sza = UtFromSzaOp.computeSzaFromUt(ut, lat, doy);

        assertEquals(31.73, sza, 1.E-2);
    }

    @Test
    public void testGetModisTileFromLatLon() {
        // Texas
        float lat = 34.2f;
        float lon = -101.71f;
        assertEquals("h09v05", BbdrUtils.getModisTileFromLatLon(lat, lon));

        // MeckPomm
        lat = 53.44f;
        lon = 10.57f;
        assertEquals("h18v03", BbdrUtils.getModisTileFromLatLon(lat, lon));

        // New Zealand
        lat = -39.5f;
        lon = 176.71f;
        assertEquals("h31v12", BbdrUtils.getModisTileFromLatLon(lat, lon));

        // Antarctica
        lat = -84.2f;
        lon = -160.71f;
        assertEquals("h16v17", BbdrUtils.getModisTileFromLatLon(lat, lon));

        // Siberia
        lat = 65.2f;
        lon = 111.71f;
        assertEquals("h22v02", BbdrUtils.getModisTileFromLatLon(lat, lon));

        // Madagascar
        lat = -28.0f;
        lon = 46.1f;
        assertEquals("h22v11", BbdrUtils.getModisTileFromLatLon(lat, lon));
    }
}

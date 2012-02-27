package org.esa.beam.dataio;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.framework.dataio.ProductSubsetDef;
import org.esa.beam.framework.datamodel.*;
import org.esa.beam.framework.dataop.maptransf.Datum;
import org.esa.beam.util.math.MathUtils;

import java.io.IOException;

/**
 * Special geocoding for Meteosat First and Second Generation Albedo products
 * This approach is using LUTs for geopos <--> pixelpos lookup.
 * Currently not used (switched back to a modified quad tree search approach)
 *
 * @author olafd
 * @author norman
 */
public class MeteosatLUTGeoCoding extends AbstractGeoCoding {

    private static final int LUT_SIZE = 10;
    public static final double MIN_DIST = 0.025;

    private final Band latBand;
    private final Band lonBand;
    private final float[] latData;
    private final float[] lonData;
    private final int width;
    private final int height;
    private final PixelBoxLut[][] latSuperLut = new PixelBoxLut[180][360];
    private final PixelBoxLut[][] lonSuperLut = new PixelBoxLut[180][360];
    private boolean initialized;

    private String areaID;

    private MeteosatQuadTreeSearch mqts;

    public MeteosatLUTGeoCoding(Band latitude, Band longitude, String areaID) throws IOException {
        this.latBand = latitude;
        this.lonBand = longitude;
        width = latBand.getSceneRasterWidth();
        height = latBand.getSceneRasterHeight();

        latData = readDataFully(latBand);
        lonData = readDataFully(lonBand);

        this.areaID = areaID;

        mqts = new MeteosatQuadTreeSearch(latData, lonData, width, height, areaID);
    }

    private float[] readDataFully(Band band) throws IOException {
        final int w = width;
        final int h = height;
        final float[] data = new float[w * h];
        band.readPixels(0, 0, w, h, data);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!band.isPixelValid(x, y)) {
                    data[y * w + x] = Float.NaN;
                }
            }
        }
        return data;
    }

    @Override
    public GeoPos getGeoPos(PixelPos pixelPos, GeoPos geoPos) {
        if (geoPos == null) {
            geoPos = new GeoPos();
        }
        final int x = (int) Math.floor(pixelPos.x);
        final int y = (int) Math.floor(pixelPos.y);
        if (x >= 0 && y >= 0 && x < width && y < height) {
            int index = width * y + x;
            geoPos.setLocation(latData[index], lonData[index]);
        } else {
            geoPos.setInvalid();
        }
        return geoPos;
    }

    @Override
    public PixelPos getPixelPos(GeoPos geoPos, PixelPos pixelPos) {
        if (pixelPos == null) {
            pixelPos = new PixelPos();
        }

        if (!initialized) {
            initialized = true;
            initialize();
        }

        // todo: discuss if we should still consider the LUT approach

        int si = getSuperLutI(geoPos.lon);
        int sj = getSuperLutJ(geoPos.lat);
        PixelBoxLut latLut = latSuperLut[sj][si];
        PixelBoxLut lonLut = lonSuperLut[sj][si];
        if (latLut == null || lonLut == null) {
            pixelPos.setInvalid();
            return pixelPos;
        }

        final PixelBox latPixelBox = latLut.getPixelBox(geoPos.lat);
        final PixelBox lonPixelBox = lonLut.getPixelBox(geoPos.lon);
        if (latPixelBox == null || lonPixelBox == null) {
            pixelPos.setInvalid();
            return pixelPos;
        }
        // System.out.println("geoPos = " + geoPos);
        // System.out.println("latPixelBox = " + latPixelBox + ", index = " + latLut.getIndex(geoPos.lat));
        // System.out.println("lonPixelBox = " + lonPixelBox + ", index = " + lonLut.getIndex(geoPos.lon));

//        // intersection of latPixelBox and lonPixelBox
//        int x1 = Math.max(latPixelBox.minX, lonPixelBox.minX);
//        int y1 = Math.max(latPixelBox.minY, lonPixelBox.minY);
//        int x2 = Math.min(latPixelBox.maxX, lonPixelBox.maxX);
//        int y2 = Math.min(latPixelBox.maxY, lonPixelBox.maxY);

        // combination of latPixelBox and lonPixelBox
        int x1 = Math.min(latPixelBox.minX, lonPixelBox.minX);
        int y1 = Math.min(latPixelBox.minY, lonPixelBox.minY);
        int x2 = Math.max(latPixelBox.maxX, lonPixelBox.maxX);
        int y2 = Math.max(latPixelBox.maxY, lonPixelBox.maxY);

        if (x1 == x2 && y1 == y2) {
            pixelPos.setLocation(x1 + 0.5F, y1 + 0.5F);
            return pixelPos;
        }

        int w = x2 - x1 + 1;
        int h = y2 - y1 + 1;
        if (w <= 0 || h <= 0) {
            pixelPos.setInvalid();
            return pixelPos;
        }

        double minDist = Double.MAX_VALUE;
        int bestX = -1;
        int bestY = -1;

        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                int index = width * y + x;
                float lat = latData[index];
                float lon = lonData[index];
                if (isValid(lat, lon)) {
                    double dist = MathUtils.sphereDistanceDeg(1.0, lon, lat, geoPos.lon, geoPos.lat) * MathUtils.RTOD;
                    if (dist < minDist && dist < MIN_DIST) {
                        minDist = dist;
                        bestX = x;
                        bestY = y;
                    }
                }
            }
        }

        if (bestX != -1 && bestY != -1) {
            pixelPos.setLocation(bestX + 0.5F, bestY + 0.5F);
        } else {
            pixelPos.setInvalid();
        }

        return pixelPos;
    }

    private boolean isValid(float lat, float lon) {
        return !Float.isNaN(lat) && !Float.isNaN(lon);
    }

    private void initialize() {

        final long t0 = System.currentTimeMillis();

        final int w = width;
        final int h = height;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int i = w * y + x;
                final float lat = latData[i];
                final float lon = lonData[i];
                fillSuperLut(w, h, y, x, lat, lon);
            }
        }
        for (int sj = 0; sj < 180; sj++) {
            for (int si = 0; si < 360; si++) {
                PixelBoxLut latLut = latSuperLut[sj][si];
                PixelBoxLut lonLut = lonSuperLut[sj][si];
                if (latLut != null) {
                    latLut.complete();
                    lonLut.complete();
                }
            }
        }

//        printMap();

        final long t1 = System.currentTimeMillis();
        System.out.println("initialize:  " + (t1 - t0) + "ms");
    }

    private void fillSuperLut(int w, int h, int y, int x, float lat, float lon) {
        if (isValid(lat, lon)) {
            int si = getSuperLutI(lon);
            int sj = getSuperLutJ(lat);

            PixelBoxLut latLut = latSuperLut[sj][si];
            PixelBoxLut lonLut = lonSuperLut[sj][si];
            if (latLut == null) {
                latLut = new PixelBoxLut(90.0 - (sj + 1), 90.0 - sj, LUT_SIZE);
                lonLut = new PixelBoxLut(si - 180.0, (si + 1) - 180.0, LUT_SIZE);
                latSuperLut[sj][si] = latLut;
                lonSuperLut[sj][si] = lonLut;
            }
            latLut.add(lat, x, y);
            lonLut.add(lon, x, y);

            if (x > 0) {
                latLut.add(lat, x - 1, y);
                lonLut.add(lon, x - 1, y);
            }
            if (x < w - 1) {
                latLut.add(lat, x + 1, y);
                lonLut.add(lon, x + 1, y);
            }
            if (y > 0) {
                latLut.add(lat, x, y - 1);
                lonLut.add(lon, x, y - 1);
            }
            if (y < h - 1) {
                latLut.add(lat, x, y + 1);
                lonLut.add(lon, x, y + 1);
            }
        }
    }

    private void printMap() {
        for (int sj = 0; sj < 180; sj++) {
            for (int si = 0; si < 360; si++) {
                PixelBoxLut latLut = latSuperLut[sj][si];
                PixelBoxLut lonLut = lonSuperLut[sj][si];
                if (latLut != null && lonLut != null) {
                    System.out.print("3");
                } else if (latLut != null) {
                    System.out.print("2");
                } else if (lonLut != null) {
                    System.out.print("1");
                } else {
                    System.out.print("-");
                }
            }
            System.out.println();
        }
    }

    private int getSuperLutJ(float lat) {
        int sj = (int) (90 - lat);
        if (sj == 180) {
            sj = 0;
        }
        return sj;
    }

    private int getSuperLutI(float lon) {
        int si = (int) (lon + 180);
        if (si == 360) {
            si = 0;
        }
        return si;
    }


    private static PixelBoxLut createLut(Band band, float[] data, boolean useWidth) {
        final Stx stx = band.getStx(true, ProgressMonitor.NULL);
        final int w = band.getSceneRasterWidth();
        final int h = band.getSceneRasterHeight();
        PixelBoxLut lut = new PixelBoxLut(band.scale(stx.getMin()), band.scale(stx.getMax()), useWidth ? w : h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float coord = data[(w * y + x)];
                if (!Float.isNaN(coord)) {
                    lut.add(coord, x, y);
                }
            }
        }
        lut.complete();

//        lut.dump();
        return lut;
    }

    static class PixelBoxLut {
        double min;
        double max;
        PixelBox[] pixelBoxes;

        PixelBoxLut(double min, double max, int size) {
            this.min = min;
            this.max = max;
            this.pixelBoxes = new PixelBox[size];
        }

        PixelBox getPixelBox(double coord) {
            final int index = getIndex(coord);
            if (index != -1) {
                final PixelBox pixelBox = pixelBoxes[index];
                if (pixelBox != null) {
                    return pixelBox;
                }
            }
            return null;
        }

        void add(double coord, int x, int y) {
            final int index = getIndex(coord);
            if (index != -1) {
                PixelBox pixelBox = pixelBoxes[index];
                if (pixelBox != null) {
                    pixelBox.add(x, y);
                } else {
                    pixelBoxes[index] = new PixelBox(x, y);
                }
            }
        }

        int getIndex(double coord) {
            if (coord >= min && coord < max) {
                return (int) ((coord - min) / (max - min) * pixelBoxes.length);
            } else if (coord == max) {
                return pixelBoxes.length - 1;
            }
            return -1;
        }

        void complete() {
            PixelBox prevValue = pixelBoxes[0];
            boolean pixelBoxSeen = prevValue != null;
            int i0 = 0;
            if (!pixelBoxSeen) {
                for (; i0 < pixelBoxes.length; i0++) {
                    prevValue = pixelBoxes[i0];
                    if (prevValue != null) {
                        pixelBoxSeen = true;
                        for (int i = 0; i < i0; i++) {
                            pixelBoxes[i] = prevValue;
                        }
                        break;
                    }
                }
            }
            if (!pixelBoxSeen) {
                throw new IllegalStateException();
            }
            for (int i = i0; i < pixelBoxes.length; i++) {
                PixelBox currentValue = pixelBoxes[i];
                if (pixelBoxSeen) {
                    if (currentValue != null) {
                        prevValue = currentValue;
                    } else {
                        prevValue = new PixelBox(prevValue.minX, prevValue.minY, prevValue.maxX, prevValue.maxY);
                        pixelBoxes[i] = prevValue;
                        pixelBoxSeen = false;
                    }
                } else {
                    if (currentValue != null) {
                        prevValue.add(currentValue);
                        prevValue = currentValue;
                        pixelBoxSeen = true;
                    } else {
                        pixelBoxes[i] = prevValue;
                    }
                }
            }
        }

        public void dump() {
            System.out.printf("min\t%s\n", min);
            System.out.printf("max\t%s\n", max);
            System.out.printf("%s\t%s\t%s\t%s\t%s\n", "i", "minX", "maxX", "minY", "maxY");
            for (int i = 0; i < pixelBoxes.length; i++) {
                PixelBox pixelBox = pixelBoxes[i];
                if (pixelBox != null) {
                    System.out.printf("%d\t%d\t%d\t%d\t%d\n", i, pixelBox.minX, pixelBox.maxX, pixelBox.minY, pixelBox.maxY);
                } else {
                    System.out.printf("%d\t%d\t%d\t%d\t%d\n", i, -1, -1, -1, -1);
                }
            }
        }
    }

    static final class PixelBox {
        int minX;
        int minY;
        int maxX;
        int maxY;

        PixelBox(int minX, int minY) {
            this(minX, minY, minX, minY);
        }

        PixelBox(int minX, int minY, int maxX, int maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        void add(int x, int y) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        public void add(PixelBox other) {
            add(other.minX, other.minY);
            add(other.maxX, other.maxY);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            PixelBox pixelBox = (PixelBox) o;

            if (maxX != pixelBox.maxX) return false;
            if (maxY != pixelBox.maxY) return false;
            if (minX != pixelBox.minX) return false;
            if (minY != pixelBox.minY) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = minX;
            result = 31 * result + minY;
            result = 31 * result + maxX;
            result = 31 * result + maxY;
            return result;
        }

        @Override
        public String toString() {
            return "PixelBox{" +
                    "minX=" + minX +
                    ", minY=" + minY +
                    ", maxX=" + maxX +
                    ", maxY=" + maxY +
                    '}';
        }
    }

    @Override
    public boolean transferGeoCoding(Scene srcScene, Scene destScene, ProductSubsetDef subsetDef) {
        // todo: implement
        return false;
    }

    @Override
    public boolean isCrossingMeridianAt180() {
        return false;
    }

    @Override
    public boolean canGetPixelPos() {
        return true;
    }

    @Override
    public boolean canGetGeoPos() {
        return true;
    }

    @Override
    public Datum getDatum() {
        return Datum.WGS_84;
    }

    @Override
    public void dispose() {
    }
}
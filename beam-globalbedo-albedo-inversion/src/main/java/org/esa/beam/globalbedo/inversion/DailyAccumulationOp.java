package org.esa.beam.globalbedo.inversion;

import Jama.Matrix;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProducts;
import org.esa.beam.globalbedo.inversion.util.AlbedoInversionUtils;
import org.esa.beam.globalbedo.inversion.util.IOUtils;
import org.esa.beam.util.logging.BeamLogManager;

import java.awt.*;
import java.io.File;
import java.util.logging.Level;

/**
 * Daily accumulation part (new implementation in QA4ECV).
 * The daily accumulations are still written to binary files.
 * This prototype only creates a 'dummy' target product which shall not be written to disk.
 * Performance is ~25% better than original version used in GA processing.
 *
 * @author Olaf Danne
 */
@OperatorMetadata(alias = "ga.inversion.dailyaccbinary",
        description = "Provides daily accumulation of single BBDR observations (new improved prototype)",
        authors = "Olaf Danne",
        version = "1.0",
        copyright = "(C) 2016 by Brockmann Consult")
public class DailyAccumulationOp extends Operator {

    @SourceProducts(description = "BBDR source product")
    private Product[] sourceProducts;

    @Parameter(defaultValue = "false", description = "Compute only snow pixels")
    private boolean computeSnow;

    @Parameter(defaultValue = "false", description = "Computation for seaice mode (polar tiles)")
    private boolean computeSeaice;

    @Parameter(defaultValue = "false", description = "Debug - run additional parts of code if needed.")
    private boolean debug;

    @Parameter(defaultValue = "true", description = "Write binary accumulator file.")
    private boolean writeBinaryFile;

    @Parameter(defaultValue = "1.0", description = "Weighting of uncertainties (test option, should be 1.0 usually!).")
    private double uncertaintyWeightingFactor;

    @Parameter(description = "Daily accumulator binary file")
    private File dailyAccumulatorBinaryFile;

    private float[][][] resultArray;

    private Tile[] bbVisTile;
    private Tile[] bbNirTile;
    private Tile[] bbSwTile;
    private Tile[] sigBbVisVisTile;
    private Tile[] sigBbVisNirTile;
    private Tile[] sigBbVisSwTile;
    private Tile[] sigBbNirNirTile;
    private Tile[] sigBbNirSwTile;
    private Tile[] sigBbSwSwTile;
    private Tile[] kvolVisTile;
    private Tile[] kvolNirTile;
    private Tile[] kvolSwTile;
    private Tile[] kgeoVisTile;
    private Tile[] kgeoNirTile;
    private Tile[] kgeoSwTile;
    private Tile[] snowMaskTile;

    private int currentSourceProductIndex;


    @Override
    public void initialize() throws OperatorException {

        final Rectangle sourceRect = new Rectangle(0, 0,
                                                   AlbedoInversionConstants.MODIS_TILE_WIDTH,
                                                   AlbedoInversionConstants.MODIS_TILE_HEIGHT);

        bbVisTile = new Tile[sourceProducts.length];
        bbNirTile = new Tile[sourceProducts.length];
        bbSwTile = new Tile[sourceProducts.length];
        sigBbVisVisTile = new Tile[sourceProducts.length];
        sigBbVisNirTile = new Tile[sourceProducts.length];
        sigBbVisSwTile = new Tile[sourceProducts.length];
        sigBbNirNirTile = new Tile[sourceProducts.length];
        sigBbNirSwTile = new Tile[sourceProducts.length];
        sigBbSwSwTile = new Tile[sourceProducts.length];
        kvolVisTile = new Tile[sourceProducts.length];
        kvolNirTile = new Tile[sourceProducts.length];
        kvolSwTile = new Tile[sourceProducts.length];
        kgeoVisTile = new Tile[sourceProducts.length];
        kgeoNirTile = new Tile[sourceProducts.length];
        kgeoSwTile = new Tile[sourceProducts.length];
        snowMaskTile = new Tile[sourceProducts.length];

        resultArray = new float[AlbedoInversionConstants.NUM_ACCUMULATOR_BANDS]
                [AlbedoInversionConstants.MODIS_TILE_WIDTH]
                [AlbedoInversionConstants.MODIS_TILE_HEIGHT];

        for (int k = 0; k < sourceProducts.length; k++) {
            bbVisTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_BB_VIS_NAME), sourceRect);
            bbNirTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_BB_NIR_NAME), sourceRect);
            bbSwTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_BB_SW_NAME), sourceRect);

            sigBbVisVisTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_SIG_BB_VIS_VIS_NAME), sourceRect);
            sigBbVisNirTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_SIG_BB_VIS_NIR_NAME), sourceRect);
            sigBbVisSwTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_SIG_BB_VIS_SW_NAME), sourceRect);
            sigBbNirNirTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_SIG_BB_NIR_NIR_NAME), sourceRect);
            sigBbNirSwTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_SIG_BB_NIR_SW_NAME), sourceRect);
            sigBbSwSwTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_SIG_BB_SW_SW_NAME), sourceRect);

            kvolVisTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_KVOL_BRDF_VIS_NAME), sourceRect);
            kvolNirTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_KVOL_BRDF_NIR_NAME), sourceRect);
            kvolSwTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_KVOL_BRDF_SW_NAME), sourceRect);
            kgeoVisTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_KGEO_BRDF_VIS_NAME), sourceRect);
            kgeoNirTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_KGEO_BRDF_NIR_NAME), sourceRect);
            kgeoSwTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_KGEO_BRDF_SW_NAME), sourceRect);
            snowMaskTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.BBDR_SNOW_MASK_NAME), sourceRect);
        }

        // per pixel, accumulate the matrices from the single products...
        // Since we loop over all source products, we need a sequential and thread-safe approach, so we do not
        // implement computeTile.
        for (int x = 0; x < AlbedoInversionConstants.MODIS_TILE_WIDTH; x++) {
            for (int y = 0; y < AlbedoInversionConstants.MODIS_TILE_HEIGHT; y++) {
                accumulate(x, y);
            }
        }

        // accumulation done for all pixels, now write result to binary file...
        BeamLogManager.getSystemLogger().log(Level.INFO, "all pixels processed.");
        BeamLogManager.getSystemLogger().log(Level.INFO, "...writing accumulator result array...");
        IOUtils.writeFloatArrayToFile(dailyAccumulatorBinaryFile, resultArray);
        BeamLogManager.getSystemLogger().log(Level.INFO, "accumulator result array written.");

        setTargetProduct(new Product("n", "d", 1, 1));
    }

    private void accumulate(int x, int y) {
        Matrix M = new Matrix(3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS,
                              3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS);
        Matrix V = new Matrix(3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS, 1);
        Matrix E = new Matrix(1, 1);
        double mask = 0.0;

        for (int k = 0; k < sourceProducts.length; k++) {
            currentSourceProductIndex = k;
            final Accumulator accumulator = getMatricesPerBBDRDataset(x, y);
            M.plusEquals(accumulator.getM());
            V.plusEquals(accumulator.getV());
            E.plusEquals(accumulator.getE());
            mask += accumulator.getMask();
        }

        Accumulator finalAccumulator = new Accumulator(M, V, E, mask);
        // write pixel result into array for binary file...
        fillBinaryResultArray(finalAccumulator, x, y);
    }

    private Accumulator getMatricesPerBBDRDataset(int x, int y) {
        if (isSnowFilter(x, y) || isBBDRFilter(x, y) || isSDFilter(x, y)) {
            // do not consider non-land pixels (BBDR = NaN), non-snowfilter pixels,
            // pixels with BBDR == 0.0 or -9999.0, SD == 0.0
            return getZeroAccumulator();
        }

        // get kernels...
        Matrix kernels = getKernels(x, y);

        // compute C matrix...
        final double[] correlation = getCorrelation(x, y);
        final double[] SD = getSD(x, y);
        Matrix C = new Matrix(
                AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS, 1);
        Matrix thisC = new Matrix(AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS,
                                  AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS);

        int count = 0;
        int cCount = 0;
        for (int j = 0; j < AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; j++) {
            for (int k = j + 1; k < AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; k++) {
                if (k == j + 1) {
                    cCount++;
                }
                C.set(cCount, 0, correlation[count] * SD[j] * SD[k]);
                count++;
                cCount++;
            }
        }

        cCount = 0;
        for (int j = 0; j < AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; j++) {
            C.set(cCount, 0, SD[j] * SD[j]);
            cCount = cCount + AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS - j;
        }

        count = 0;
        for (int j = 0; j < AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; j++) {
            for (int k = j; k < AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; k++) {
                thisC.set(j, k, C.get(count, 0));
                thisC.set(k, j, thisC.get(j, k));
                count++;
            }
        }

        // compute M, V, E matrices...
        final Matrix bbdr = getBBDR(x, y);
        final Matrix inverseC = thisC.inverse();
        final Matrix M = (kernels.transpose().times(inverseC)).times(kernels);
        final Matrix inverseCDiagFlat = AlbedoInversionUtils.getRectangularDiagonalMatrix(inverseC);
        final Matrix kernelTimesInvCDiag = kernels.transpose().times(inverseCDiagFlat);
        final Matrix V = kernelTimesInvCDiag.times(bbdr);
        final Matrix E = (bbdr.transpose().times(inverseC)).times(bbdr);

        // return result
        return new Accumulator(M, V, E, 1);
    }

    private Accumulator getZeroAccumulator() {
        final Matrix zeroM = new Matrix(3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS,
                                        3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS);
        final Matrix zeroV = new Matrix(3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS, 1);
        final Matrix zeroE = new Matrix(1, 1);

        return new Accumulator(zeroM, zeroV, zeroE, 0);
    }

    private Matrix getBBDR(int x, int y) {
        Matrix bbdr = new Matrix(AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS, 1);
        bbdr.set(0, 0, bbVisTile[currentSourceProductIndex].getSampleDouble(x, y));
        bbdr.set(1, 0, bbNirTile[currentSourceProductIndex].getSampleDouble(x, y));
        bbdr.set(2, 0, bbSwTile[currentSourceProductIndex].getSampleDouble(x, y));
        return bbdr;
    }

    private double[] getSD(int x, int y) {
        double[] SD = new double[3];
        SD[0] = sigBbVisVisTile[currentSourceProductIndex].getSampleDouble(x, y);
        SD[1] = sigBbNirNirTile[currentSourceProductIndex].getSampleDouble(x, y);
        SD[2] = sigBbSwSwTile[currentSourceProductIndex].getSampleDouble(x, y);
        return SD;
    }

    private double[] getCorrelation(int x, int y) {
        double[] correlation = new double[AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS];
        correlation[0] = sigBbVisNirTile[currentSourceProductIndex].getSampleDouble(x, y);
        correlation[1] = sigBbVisSwTile[currentSourceProductIndex].getSampleDouble(x, y);
        correlation[2] = sigBbNirSwTile[currentSourceProductIndex].getSampleDouble(x, y);
        return correlation;
    }

    private void fillBinaryResultArray(Accumulator accumulator, int x, int y) {
        int offset = 0;
        for (int i = 0; i < 3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; i++) {
            for (int j = 0; j < 3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; j++) {
                resultArray[offset++][x][y] = (float) accumulator.getM().get(i, j);
            }
        }
        for (int i = 0; i < 3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; i++) {
            resultArray[offset++][x][y] = (float) accumulator.getV().get(i, 0);
        }
        resultArray[offset++][x][y] = (float) accumulator.getE().get(0, 0);
        resultArray[offset][x][y] = (float) accumulator.getMask();
    }


    private Matrix getKernels(int x, int y) {
        Matrix kernels = new Matrix(AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS,
                                    3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS);

        kernels.set(0, 0, 1.0);
        kernels.set(1, 3, 1.0);
        kernels.set(2, 6, 1.0);
        kernels.set(0, 1, kvolVisTile[currentSourceProductIndex].getSampleDouble(x, y));
        kernels.set(1, 4, kvolNirTile[currentSourceProductIndex].getSampleDouble(x, y));
        kernels.set(2, 7, kvolSwTile[currentSourceProductIndex].getSampleDouble(x, y));
        kernels.set(0, 2, kgeoVisTile[currentSourceProductIndex].getSampleDouble(x, y));
        kernels.set(1, 5, kgeoNirTile[currentSourceProductIndex].getSampleDouble(x, y));
        kernels.set(2, 8, kgeoSwTile[currentSourceProductIndex].getSampleDouble(x, y));

        return kernels;
    }

    private boolean isSnowFilter(int x, int y) {
        return ((computeSnow && !(snowMaskTile[currentSourceProductIndex].getSampleInt(x, y) == 1)) ||
                (!computeSnow && (snowMaskTile[currentSourceProductIndex].getSampleInt(x, y) == 1)));
    }

    private boolean isBBDRFilter(int x, int y) {
        final double bbVis = bbVisTile[currentSourceProductIndex].getSampleDouble(x, y);
        final double bbNir = bbNirTile[currentSourceProductIndex].getSampleDouble(x, y);
        final double bbSw = bbSwTile[currentSourceProductIndex].getSampleDouble(x, y);

        return (bbVis == 0.0 || !AlbedoInversionUtils.isValid(bbVis) || bbVis == 9999.0 ||
                bbNir == 0.0 || !AlbedoInversionUtils.isValid(bbNir) || bbNir == 9999.0 ||
                bbSw == 0.0 || !AlbedoInversionUtils.isValid(bbSw) || bbSw == 9999.0);
    }

    private boolean isSDFilter(int x, int y) {
        final double sigBbVisVis = sigBbVisVisTile[currentSourceProductIndex].getSampleDouble(x, y);
        final double sigBbNirNir = sigBbNirNirTile[currentSourceProductIndex].getSampleDouble(x, y);
        final double sigBbSwSw = sigBbSwSwTile[currentSourceProductIndex].getSampleDouble(x, y);
        return (sigBbVisVis == 0.0 && sigBbNirNir == 0.0 && sigBbSwSw == 0.0);
    }


    public static class Spi extends OperatorSpi {

        public Spi() {
            super(DailyAccumulationOp.class);
        }
    }
}

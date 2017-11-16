package org.esa.beam.globalbedo.inversion.spectral;

import Jama.Matrix;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProducts;
import org.esa.beam.globalbedo.inversion.Accumulator;
import org.esa.beam.globalbedo.inversion.AlbedoInversionConstants;
import org.esa.beam.globalbedo.inversion.util.DailyAccumulationUtils;
import org.esa.beam.globalbedo.inversion.util.IOUtils;
import org.esa.beam.util.logging.BeamLogManager;

import java.awt.*;
import java.io.File;
import java.util.logging.Level;

/**
 * Pixel operator implementing the daily accumulation for spectral inversion.
 *
 * @author Olaf Danne
 */
@SuppressWarnings({"MismatchedReadAndWriteOfArray", "StatementWithEmptyBody"})
@OperatorMetadata(alias = "ga.inversion.dailyaccbinary.spectral",
        description = "Provides spectral daily accumulation of single SDR observations",
        authors = "Olaf Danne",
        version = "1.0",
        copyright = "(C) 2016 by Brockmann Consult")
public class SpectralDailyAccumulationOp extends Operator {


    @SourceProducts(description = "SDR source products")
    private Product[] sourceProducts;

    @Parameter(defaultValue = "3", interval = "[1,7]", description = "Band index in case only 1 SDR band is processed")
    private int singleBandIndex;    // todo: consider chemistry bands

    @Parameter(defaultValue = "false", description = "Compute only snow pixels")
    private boolean computeSnow;

    @Parameter(description = "Daily accumulator binary file")
    private File dailyAccumulatorBinaryFile;

    private static final int sourceSampleOffset = 100;  // this value must be >= number of bands in a source product

    private float[][][] resultArray;


    private Tile[][] sdrTiles;
    private Tile[][] sigmaSdrTiles;

    private Tile[] kvolTile;
    private Tile[] kgeoTile;
    private Tile[] snowMaskTile;

    private int currentSourceProductIndex;

    private int numSdrBands = 1;     // no longer a parameter


    @Override
    public void initialize() throws OperatorException {

        final Rectangle sourceRect = new Rectangle(0, 0,
                                                   AlbedoInversionConstants.MODIS_TILE_WIDTH,
                                                   AlbedoInversionConstants.MODIS_TILE_HEIGHT);

        sdrTiles = new Tile[sourceProducts.length][numSdrBands];
        // (7*7 - 7)/2  + 7 = 28 sigma bands default (UR matrix)
//        numSigmaSdrBands = (numSdrBands * numSdrBands - numSdrBands)/2 + numSdrBands;
        // we need only the stdevs, no cross correlations?!
        int numSigmaSdrBands = numSdrBands;
        sigmaSdrTiles = new Tile[sourceProducts.length][numSigmaSdrBands];

        String[] sdrBandNames = new String[numSdrBands];
        String[] sigmaSdrBandNames = new String[numSdrBands];
        if (numSdrBands == 1) {
            sdrBandNames[0] = AlbedoInversionConstants.MODIS_SPECTRAL_SDR_NAME_PREFIX + (singleBandIndex - 1);
            sigmaSdrBandNames[0] = AlbedoInversionConstants.MODIS_SPECTRAL_SDR_SIGMA_NAME_PREFIX + (singleBandIndex - 1);
        } else {
            sdrBandNames = SpectralInversionUtils.getSdrBandNames(numSdrBands);
            sigmaSdrBandNames = SpectralInversionUtils.getSigmaSdrBandNames(numSigmaSdrBands);
        }

        kvolTile = new Tile[sourceProducts.length];
        kgeoTile = new Tile[sourceProducts.length];
        snowMaskTile = new Tile[sourceProducts.length];

        // originally we had:
        // (3*7) * (3*7) + 3*7 + 1 + 1= 464 elements to store in daily acc :-(
        // that is why we now process single bands subsequently (numSdrBands = 1)
        final int resultArrayElements = (3 * numSdrBands) * (3 * numSdrBands) + 3 * numSdrBands + 1 + 1;
        resultArray = new float[resultArrayElements]
                [AlbedoInversionConstants.MODIS_TILE_WIDTH]
                [AlbedoInversionConstants.MODIS_TILE_HEIGHT];

        BeamLogManager.getSystemLogger().log(Level.INFO, "numSdrBands: " + numSdrBands);
        BeamLogManager.getSystemLogger().log(Level.INFO, "singleBandIndex: " + singleBandIndex);
        BeamLogManager.getSystemLogger().log(Level.INFO, "sdrBandNames[0]: " + sdrBandNames[0]);
        for (int k = 0; k < sourceProducts.length; k++) {
            BeamLogManager.getSystemLogger().log(Level.INFO, "sourceProducts[k]: " + sourceProducts[k].getName());
            int sdrIndex = 0;
            for (int j = 0; j < numSdrBands; j++) {
                sdrTiles[k][j] = getSourceTile(sourceProducts[k].getBand(sdrBandNames[sdrIndex++]), sourceRect);
            }
            int sigmaSdrIndex = 0;
            for (int j = 0; j < numSigmaSdrBands; j++) {
                sigmaSdrTiles[k][j] = getSourceTile(sourceProducts[k].getBand(sigmaSdrBandNames[sigmaSdrIndex++]), sourceRect);
            }
            kvolTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.CONSTANT_KERNEL_BAND_NAMES[0]), sourceRect);
            kgeoTile[k] = getSourceTile(sourceProducts[k].getBand(AlbedoInversionConstants.CONSTANT_KERNEL_BAND_NAMES[1]), sourceRect);
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
        Matrix M = new Matrix(3 * numSdrBands,
                              3 * numSdrBands);
        Matrix V = new Matrix(3 * numSdrBands, 1);
        Matrix E = new Matrix(1, 1);
        double mask = 0.0;

        for (int k = 0; k < sourceProducts.length; k++) {
            currentSourceProductIndex = k;
            final Accumulator accumulator = getMatricesPerSDRDataset(x, y);
            M.plusEquals(accumulator.getM());
            V.plusEquals(accumulator.getV());
            E.plusEquals(accumulator.getE());
            mask += accumulator.getMask();
        }

        Accumulator finalAccumulator = new Accumulator(M, V, E, mask);
        // write pixel result into array for binary file...
        fillBinaryResultArray(finalAccumulator, x, y);
    }

    private Accumulator getMatricesPerSDRDataset(int x, int y) {
        // check validity of ALL inputs used:
        final Matrix sdr = getSDR(x, y);
        final double[] stdev = getSD(x, y);
        final Matrix kernels = getKernels(x, y);
        if (isSnowFilter(x, y) || DailyAccumulationUtils.isAccumulatorInputInvalid(sdr, stdev, kernels)) {
            return getZeroAccumulator();
        }

        // compute C matrix...
        Matrix C = new Matrix(numSdrBands * numSdrBands, 1);
        Matrix thisC = new Matrix(numSdrBands, numSdrBands);

        int count = 0;
        int cCount = 0;
        for (int j = 0; j < numSdrBands; j++) {
            for (int k = j + 1; k < numSdrBands; k++) {
                if (k == j + 1) {
                    cCount++;
                }
//                C.set(cCount, 0, correlation[count] * stdev[j] * stdev[k]);
                // we neglect the cross terms...
                C.set(cCount, 0, 0.0);
                count++;
                cCount++;
            }
        }

        cCount = 0;
        for (int j = 0; j < numSdrBands; j++) {
            C.set(cCount, 0, stdev[j] * stdev[j]);
            cCount = cCount + numSdrBands - j;
        }

        count = 0;
        for (int j = 0; j < numSdrBands; j++) {
            for (int k = j; k < numSdrBands; k++) {
                thisC.set(j, k, C.get(count, 0));
                thisC.set(k, j, thisC.get(j, k));
                count++;
            }
        }

        // compute M, V, E matrices...
        if (thisC.lu().isNonsingular()) {
            final Matrix inverseC = thisC.inverse();
            final Matrix M = (kernels.transpose().times(inverseC)).times(kernels);
            final Matrix inverseCDiagFlat = DailyAccumulationUtils.getRectangularDiagonalMatrix(inverseC);
            final Matrix kernelTimesInvCDiag = kernels.transpose().times(inverseCDiagFlat);
            final Matrix V = kernelTimesInvCDiag.times(sdr);
            final Matrix E = (sdr.transpose().times(inverseC)).times(sdr);

            // return result
            return new Accumulator(M, V, E, 1);
        } else {
            return getZeroAccumulator();
        }
    }

    private Accumulator getZeroAccumulator() {
        final Matrix zeroM = new Matrix(3 * numSdrBands,
                                        3 * numSdrBands);
        final Matrix zeroV = new Matrix(3 * numSdrBands, 1);
        final Matrix zeroE = new Matrix(1, 1);

        return new Accumulator(zeroM, zeroV, zeroE, 0);
    }

    private Matrix getSDR(int x, int y) {
        Matrix sdr = new Matrix(numSdrBands, 1);
        for (int j = 0; j < numSdrBands; j++) {
            sdr.set(j, 0, sdrTiles[currentSourceProductIndex][j].getSampleDouble(x, y));
        }
        return sdr;
    }

    // we do not need the cross terms...
    private double[] getSD(int x, int y) {
        double[] SD = new double[numSdrBands];
        for (int j = 0; j < numSdrBands; j++) {
            SD[j] = sigmaSdrTiles[currentSourceProductIndex][j].getSampleDouble(x, y);
        }
        return SD;
    }

    private void fillBinaryResultArray(Accumulator accumulator, int x, int y) {
        int offset = 0;
        for (int i = 0; i < 3 * numSdrBands; i++) {
            for (int j = 0; j < 3 * numSdrBands; j++) {
                resultArray[offset++][x][y] = (float) accumulator.getM().get(i, j);
            }
        }
        for (int i = 0; i < 3 * numSdrBands; i++) {
            resultArray[offset++][x][y] = (float) accumulator.getV().get(i, 0);
        }
        resultArray[offset++][x][y] = (float) accumulator.getE().get(0, 0);
        resultArray[offset][x][y] = (float) accumulator.getMask();
    }


    private Matrix getKernels(int x, int y) {
        Matrix kernels = new Matrix(numSdrBands, 3 * numSdrBands);

        // SK, 20160527: set constant kernels like:
        // Matrix kernels = new matrix(n,m)
        //  with n=number of bands (=7 in the case of modis) and m=3*n

        //  kernels(i,j,1) if j%3==0 && j/3==i
        //  kernels(i,j,kvol_i) if (j-1)%3==0 && (j-1)/3==i
        //  kernels(i,j,kgeo_i) if (j-2)%3==0 && (j-2)/3==i

        for (int i = 0; i < numSdrBands; i++) {
            for (int j = 0; j < 3 * numSdrBands; j++) {
                if (j % 3 == 0 && j / 3 == i) {
                    kernels.set(i, j, 1.0);
                } else if ((j - 1) % 3 == 0 && (j - 1) / 3 == i) {
                    kernels.set(i, j, kvolTile[currentSourceProductIndex].getSampleDouble(x, y));
                } else if ((j - 2) % 3 == 0 && (j - 2) / 3 == i) {
                    kernels.set(i, j, kgeoTile[currentSourceProductIndex].getSampleDouble(x, y));
                }
            }
        }

        return kernels;
    }

    private boolean isSnowFilter(int x, int y) {
        return ((computeSnow && !(snowMaskTile[currentSourceProductIndex].getSampleInt(x, y) == 1)) ||
                (!computeSnow && (snowMaskTile[currentSourceProductIndex].getSampleInt(x, y) == 1)));
    }


    public static class Spi extends OperatorSpi {

        public Spi() {
            super(SpectralDailyAccumulationOp.class);
        }
    }

}

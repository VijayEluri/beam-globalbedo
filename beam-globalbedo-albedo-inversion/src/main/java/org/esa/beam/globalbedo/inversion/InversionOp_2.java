package org.esa.beam.globalbedo.inversion;


import Jama.LUDecomposition;
import Jama.Matrix;
import Jama.SingularValueDecomposition;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.pointop.*;
import org.esa.beam.globalbedo.inversion.util.AlbedoInversionUtils;
import org.esa.beam.globalbedo.inversion.util.IOUtils;

import static java.lang.Math.*;
import static org.esa.beam.globalbedo.inversion.AlbedoInversionConstants.*;

/**
 * Pixel operator implementing the inversion part of python breadboard.
 * The breadboard file is 'AlbedoInversion_multisensor_FullAccum_MultiProcessing.py' provided by Gerardo Lopez Saldana.
 *
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
@OperatorMetadata(alias = "ga.inversion.inversion",
                  description = "Performs final inversion from fully accumulated optimal estimation matrices",
                  authors = "Olaf Danne",
                  version = "1.0",
                  copyright = "(C) 2011 by Brockmann Consult")

public class InversionOp_2 extends PixelOperator {

    public static final int[][] SRC_PRIOR_MEAN = new int[NUM_ALBEDO_PARAMETERS][NUM_ALBEDO_PARAMETERS];

    public static final int[][] SRC_PRIOR_SD = new int[NUM_ALBEDO_PARAMETERS][NUM_ALBEDO_PARAMETERS];

    public static final int SOURCE_SAMPLE_OFFSET = 0;  // this value must be >= number of bands in a source product
    public static final int PRIOR_OFFSET = (int) pow(NUM_ALBEDO_PARAMETERS, 2.0);
    public static final int SRC_PRIOR_NSAMPLES = SOURCE_SAMPLE_OFFSET + 2 * PRIOR_OFFSET;

    public static final int SRC_PRIOR_MASK = SOURCE_SAMPLE_OFFSET + 2 * PRIOR_OFFSET + 1;

    private static final int NUM_TRG_PARAMETERS = 3 * NUM_BBDR_WAVE_BANDS;

    // this offset is the number of UR matrix elements + diagonale. Should be 45 for 9x9 matrix...
    private static final int NUM_TRG_UNCERTAINTIES = ((int) pow(3 * NUM_BBDR_WAVE_BANDS, 2.0) + 3 * NUM_BBDR_WAVE_BANDS) / 2;

    private static final int TRG_ENTROPY = 0;
    private static final int TRG_REL_ENTROPY = 1;
    private static final int TRG_WEIGHTED_NUM_SAMPLES = 2;
    private static final int TRG_GOODNESS_OF_FIT = 3;

    private static final String[] PARAMETER_BAND_NAMES = IOUtils.getInversionParameterBandNames();
    private static final String[][] UNCERTAINTY_BAND_NAMES = IOUtils.getInversionUncertaintyBandNames();

    static {
        for (int i = 0; i < NUM_ALBEDO_PARAMETERS; i++) {
            for (int j = 0; j < NUM_ALBEDO_PARAMETERS; j++) {
                SRC_PRIOR_MEAN[i][j] = SOURCE_SAMPLE_OFFSET + NUM_ALBEDO_PARAMETERS * i + j;
                SRC_PRIOR_SD[i][j] = SOURCE_SAMPLE_OFFSET + PRIOR_OFFSET + NUM_ALBEDO_PARAMETERS * i + j;
            }
        }
    }

    @SourceProduct(description = "Prior product")
    private Product priorProduct;

    @Parameter(description = "Full accumulator")
    private FullAccumulator fullAccumulator;

    @Parameter(description = "Year")
    private int year;

    @Parameter(description = "Tile")
    private String tile;

    @Parameter(defaultValue = "false", description = "Compute only snow pixels")
    private boolean computeSnow;

    @Parameter(defaultValue = "true", description = "Use prior information")
    private boolean usePrior;

    @Parameter(defaultValue = "30.0", description = "Prior scale factor")
    private double priorScaleFactor;

    @Override
    protected void configureTargetProduct(ProductConfigurer productConfigurer) {
        super.configureTargetProduct( productConfigurer);

        for (String parameterBandName : PARAMETER_BAND_NAMES) {
            productConfigurer.addBand(parameterBandName, ProductData.TYPE_FLOAT32, Float.NaN);
        }

        for (int i = 0; i < 3 * NUM_BBDR_WAVE_BANDS; i++) {
            // add bands only for UR triangular matrix
            for (int j = i; j < 3 * NUM_BBDR_WAVE_BANDS; j++) {
                productConfigurer.addBand(UNCERTAINTY_BAND_NAMES[i][j], ProductData.TYPE_FLOAT32, Float.NaN);
            }
        }

        productConfigurer.addBand(INV_ENTROPY_BAND_NAME, ProductData.TYPE_FLOAT32, Float.NaN);
        productConfigurer.addBand(INV_REL_ENTROPY_BAND_NAME, ProductData.TYPE_FLOAT32, Float.NaN);
        productConfigurer.addBand(INV_WEIGHTED_NUMBER_OF_SAMPLES_BAND_NAME, ProductData.TYPE_FLOAT32, Float.NaN);
        productConfigurer.copyBands(ACC_DAYS_TO_THE_CLOSEST_SAMPLE_BAND_NAME);
        productConfigurer.addBand(INV_GOODNESS_OF_FIT_BAND_NAME, ProductData.TYPE_FLOAT32, Float.NaN);

        productConfigurer.getTargetProduct().setPreferredTileSize(100, 100);
    }

    @Override
    protected void configureSourceSamples(SampleConfigurer configurator) {
        // prior product:
        // we have:
        // 3x3 mean, 3x3 SD, Nsamples, mask
        for (int i = 0; i < NUM_ALBEDO_PARAMETERS; i++) {
            for (int j = 0; j < NUM_ALBEDO_PARAMETERS; j++) {
                final String meanBandName = "MEAN__BAND________" + i + "_PARAMETER_F" + j;
                configurator.defineSample(SRC_PRIOR_MEAN[i][j], meanBandName, priorProduct);

                final String sdMeanBandName = "SD_MEAN__BAND________" + i + "_PARAMETER_F" + j;
                configurator.defineSample(SRC_PRIOR_SD[i][j], sdMeanBandName, priorProduct);
            }
        }
        configurator.defineSample(SRC_PRIOR_NSAMPLES, PRIOR_NSAMPLES_NAME, priorProduct);
        configurator.defineSample(SRC_PRIOR_MASK, PRIOR_MASK_NAME, priorProduct);
    }

    @Override
    protected void configureTargetSamples(SampleConfigurer configurator) {

        for (int i = 0; i < 3 * NUM_BBDR_WAVE_BANDS; i++) {
            configurator.defineSample(i, PARAMETER_BAND_NAMES[i]);
        }

        int index = 0;
        for (int i = 0; i < 3 * NUM_BBDR_WAVE_BANDS; i++) {
            for (int j = i; j < 3 * NUM_ALBEDO_PARAMETERS; j++) {
                configurator.defineSample(NUM_TRG_PARAMETERS + index, UNCERTAINTY_BAND_NAMES[i][j]);
                index++;
            }
        }

        int offset = NUM_TRG_PARAMETERS + NUM_TRG_UNCERTAINTIES;
        configurator.defineSample(offset + TRG_ENTROPY, INV_ENTROPY_BAND_NAME);
        configurator.defineSample(offset + TRG_REL_ENTROPY, INV_REL_ENTROPY_BAND_NAME);
        configurator.defineSample(offset + TRG_WEIGHTED_NUM_SAMPLES, INV_WEIGHTED_NUMBER_OF_SAMPLES_BAND_NAME);
        configurator.defineSample(offset + TRG_GOODNESS_OF_FIT, INV_GOODNESS_OF_FIT_BAND_NAME);
    }

    @Override
    protected void computePixel(int x, int y, Sample[] sourceSamples, WritableSample[] targetSamples) {

        Matrix parameters = new Matrix(NUM_BBDR_WAVE_BANDS * NUM_ALBEDO_PARAMETERS, 1);
        Matrix uncertainties = new Matrix(3 * NUM_BBDR_WAVE_BANDS, 3 * NUM_ALBEDO_PARAMETERS);

        double entropy = 0.0; // == det in BB
        double relEntropy = 0.0;

//        final Accumulator accumulator = Accumulator.createForInversion(sourceSamples);
//        final FullAccumulator accumulator = fullAccumulator;
        final Accumulator accumulator = Accumulator.createForInversion(fullAccumulator.getSumMatrices(), x, y);
        final Prior prior = Prior.createForInversion(sourceSamples, priorScaleFactor);

        final Matrix mAcc = accumulator.getM();
        Matrix vAcc = accumulator.getV();
        final Matrix eAcc = accumulator.getE();
        double maskAcc = (double) accumulator.getMask();

        final double maskPrior = prior.getMask();

        if (maskAcc > 0 && maskPrior > 0) {

            if (usePrior) {
                for (int i = 0; i < 3 * NUM_BBDR_WAVE_BANDS; i++) {
                    final double m_ii_accum = mAcc.get(i, i);
                    mAcc.set(i, i, m_ii_accum + prior.getM().get(i, i));
                }
                vAcc = vAcc.plus(prior.getV());
            }

            final LUDecomposition lud = new LUDecomposition(mAcc);
            if (lud.isNonsingular()) {
                Matrix tmpM = mAcc.inverse();
                if (AlbedoInversionUtils.matrixHasNanElements(tmpM) || AlbedoInversionUtils.matrixHasZerosInDiagonale(
                        tmpM)) {
                    tmpM = new Matrix(3 * NUM_BBDR_WAVE_BANDS,
                                      3 * NUM_ALBEDO_PARAMETERS,
                                      INVALID);
                }
                uncertainties = tmpM;
            } else {
                parameters = new Matrix(NUM_BBDR_WAVE_BANDS *
                                        NUM_ALBEDO_PARAMETERS, 1,
                                        INVALID);
                uncertainties = new Matrix(3 * NUM_BBDR_WAVE_BANDS,
                                           3 * NUM_ALBEDO_PARAMETERS,
                                           INVALID);
                maskAcc = 0.0;
            }

            if (maskAcc != 0.0) {
                parameters = mAcc.solve(vAcc);

                entropy = getEntropy(mAcc);
                final double entropyPrior = getEntropy(prior.getM());
                if (usePrior) {
                    relEntropy = entropyPrior - entropy;
                } else {
                    relEntropy = INVALID;
                }
            }
        } else {
            if (maskPrior > 0.0) {
                parameters = prior.getParameters();
                if (usePrior) {
                    final LUDecomposition lud = new LUDecomposition(prior.getM());
                    if (lud.isNonsingular()) {
                        uncertainties = prior.getM().inverse();
                        entropy = getEntropy(prior.getM());
                    } else {
                        uncertainties = new Matrix(
                                3 * NUM_BBDR_WAVE_BANDS,
                                3 * NUM_ALBEDO_PARAMETERS, INVALID);
                        entropy = INVALID;
                    }
                    relEntropy = 0.0;
                } else {
                    uncertainties = new Matrix(
                            3 * NUM_BBDR_WAVE_BANDS,
                            3 * NUM_ALBEDO_PARAMETERS, INVALID);
                    entropy = INVALID;
                    relEntropy = INVALID;
                }
            }
        }

        // finally we need the 'Goodness of Fit'...
        final double goodnessOfFit = getGoodnessOfFit(mAcc, vAcc, eAcc, parameters, maskAcc);

        // we have the final result - fill target samples...
        fillTargetSamples(targetSamples,
                          parameters, uncertainties, entropy, relEntropy,
                          maskAcc, goodnessOfFit);
    }

    private double getGoodnessOfFit(Matrix mAcc, Matrix vAcc, Matrix eAcc, Matrix fPars, double maskAcc) {
        Matrix goodnessOfFitMatrix = new Matrix(1, 1, 0.0);
        if (maskAcc > 0) {
            final Matrix gofTerm1 = fPars.transpose().times(mAcc).times(fPars);
            final Matrix gofTerm2 = fPars.transpose().times(vAcc);
            final Matrix m2 = new Matrix(1, 1, 2.0);
            final Matrix gofTerm3 = m2.times(eAcc);
            goodnessOfFitMatrix = gofTerm1.plus(gofTerm2).minus(gofTerm3);
        }
        return goodnessOfFitMatrix.get(0, 0);
    }

    private void fillTargetSamples(WritableSample[] targetSamples,
                                   Matrix parameters, Matrix uncertainties,  double entropy, double relEntropy,
                                   double weightedNumberOfSamples, double goodnessOfFit) {

        // parameters
        int index = 0;
        for (int i = 0; i < NUM_BBDR_WAVE_BANDS; i++) {
            for (int j = 0; j < NUM_BBDR_WAVE_BANDS; j++) {
                targetSamples[index].set(parameters.get(index, 0));
                index++;
            }
        }

        for (int i = 0; i < 3 * NUM_BBDR_WAVE_BANDS; i++) {
            for (int j = i; j < 3 * NUM_BBDR_WAVE_BANDS; j++) {
                targetSamples[index].set(uncertainties.get(i, j));
                index++;
            }
        }

        int offset = NUM_TRG_PARAMETERS + NUM_TRG_UNCERTAINTIES;
        targetSamples[offset + TRG_ENTROPY].set(entropy);
        targetSamples[offset + TRG_REL_ENTROPY].set(relEntropy);
        targetSamples[offset + TRG_WEIGHTED_NUM_SAMPLES].set(weightedNumberOfSamples);
        targetSamples[offset + TRG_GOODNESS_OF_FIT].set(goodnessOfFit);

    }

    private double getEntropy(Matrix m) {
        final SingularValueDecomposition svdM = m.svd();
        final double[] svdMSingularValues = svdM.getSingularValues();
        // see python BB equivalent at http://nullege.com/codes/search/numpy.prod
        double productSvdMSRecip = 1.0;
        for (double svdMSingularValue : svdMSingularValues) {
            productSvdMSRecip *= (1.0 / svdMSingularValue);
        }
        return 0.5 * log(productSvdMSRecip) + svdMSingularValues.length * sqrt(log(2.0 * PI * E));
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(InversionOp_2.class);
        }
    }
}
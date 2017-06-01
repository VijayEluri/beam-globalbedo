package org.esa.beam.globalbedo.bbdr;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.pointop.ProductConfigurer;
import org.esa.beam.framework.gpf.pointop.Sample;
import org.esa.beam.framework.gpf.pointop.SampleConfigurer;
import org.esa.beam.framework.gpf.pointop.WritableSample;
import org.esa.beam.globalbedo.inversion.AlbedoInversionConstants;
import org.esa.beam.globalbedo.inversion.spectral.SpectralInversionUtils;
import org.esa.beam.gpf.operators.standard.BandMathsOp;
import org.esa.beam.util.ProductUtils;

import static java.lang.StrictMath.toRadians;

/**
 * Performs spectral mapping of MERIS SDR to MODIS BRF following UCL approach.
 *
 * @author Olaf Danne
 */
@OperatorMetadata(alias = "ga.bbdr.modis.spectral",
        description = "Performs spectral mapping of MERIS SDR to MODIS BRF following UCL approach",
        authors = "Said Kharbouche, Olaf Danne",
        version = "1.0",
        copyright = "(C) 2016 by UCL/MSSL, Brockmann Consult")
public class SdrSpectralMappingToModisOp extends BbdrMasterOp {

    private static final int SRC_VZA = 50;
    private static final int SRC_SZA = 51;
    private static final int SRC_VAA = 52;
    private static final int SRC_SAA = 53;

    private static final int SRC_SNOW_MASK = 61;

    private static final int SRC_STATUS = 70;


//    @Parameter(defaultValue = "false", description = "Compute only snow pixels")
//    private boolean computeSnow;

    @Parameter(defaultValue = "1", description = "Spectral mapped SDR bands (usually the 7 MODIS channels)")
    protected int numMappedSdrBands;
    // todo: SK to provide mapping for 440nm chemistry channel (not included in the MODIS bands!)

    @Parameter(defaultValue = "3", interval = "[1,7]", description = "Band index in case only 1 SDR band is processed")
    private int singleBandIndex;    // todo: consider 440nm chemistry channel

    private String[] sdrMappedBandNames;
    private String[] sigmaSdrMappedBandNames;
    private String[] kernelBandNames;
    private MsslModisSpectralMapper sm;

    @Override
    protected void prepareInputs() throws OperatorException {
        sdrMappedBandNames = SpectralInversionUtils.getSdrBandNames(numMappedSdrBands);
//        int numSigmaSdrBands = (numMappedSdrBands * numMappedSdrBands - numMappedSdrBands) / 2 + numMappedSdrBands;
        // we do not need the cross terms...
        int numSigmaSdrBands = numMappedSdrBands;
//        sigmaSdrMappedBandNames = SpectralInversionUtils.getSigmaSdrBandNames(numMappedSdrBands, numSigmaSdrBands);
        sigmaSdrMappedBandNames = SpectralInversionUtils.getSigmaSdrBandNames(numSigmaSdrBands);

        sdrMappedBandNames = new String[numMappedSdrBands];
        sigmaSdrMappedBandNames = new String[numMappedSdrBands];
        if (numMappedSdrBands == 1) {
            sdrMappedBandNames[0] = AlbedoInversionConstants.MODIS_SPECTRAL_SDR_NAME_PREFIX + singleBandIndex;
            sigmaSdrMappedBandNames[0] = AlbedoInversionConstants.MODIS_SPECTRAL_SDR_SIGMA_NAME_PREFIX + singleBandIndex;
        } else {
            sdrMappedBandNames = SpectralInversionUtils.getSdrBandNames(numMappedSdrBands);
            sigmaSdrMappedBandNames = SpectralInversionUtils.getSigmaSdrBandNames(numSigmaSdrBands);
        }

        kernelBandNames = AlbedoInversionConstants.CONSTANT_KERNEL_BAND_NAMES;

        sm = new MsslModisSpectralMapper(sensor);
        // read coefficients from a text file
        // we have constants now, 20161118
//        sm.readCoeff();
    }

    @Override
    protected void configureSourceSamples(SampleConfigurer configurator) {
        final String commonLandExpr;
        if (landExpression != null && !landExpression.isEmpty()) {
            commonLandExpr = landExpression;
        } else {
            commonLandExpr = sensor.getLandExpr();
        }

        final String snowMaskExpression = "cloud_classif_flags.F_CLEAR_SNOW";

        BandMathsOp.BandDescriptor bdSnow = new BandMathsOp.BandDescriptor();
        bdSnow.name = "snow_mask";
        bdSnow.expression = snowMaskExpression;
        bdSnow.type = ProductData.TYPESTRING_INT8;

        BandMathsOp snowOp = new BandMathsOp();
        snowOp.setParameterDefaultValues();
        snowOp.setSourceProduct(sourceProduct);
        snowOp.setTargetBandDescriptors(bdSnow);
        Product snowMaskProduct = snowOp.getTargetProduct();

        configurator.defineSample(SRC_SNOW_MASK, snowMaskProduct.getBandAt(0).getName(), snowMaskProduct);

        BandMathsOp.BandDescriptor bdLand = new BandMathsOp.BandDescriptor();
        bdLand.name = "land_mask";
        bdLand.expression = commonLandExpr;
        bdLand.type = ProductData.TYPESTRING_INT8;

        int index = 0;
        for (int i = 0; i < sensor.getSdrBandNames().length; i++) {
            configurator.defineSample(index++, sensor.getSdrBandNames()[i], sourceProduct);
        }
        for (int i = 0; i < sensor.getSdrErrorBandNames().length; i++) {
            configurator.defineSample(index++, sensor.getSdrErrorBandNames()[i], sourceProduct);
        }

        configurator.defineSample(SRC_VZA, "VZA");
        configurator.defineSample(SRC_SZA, "SZA");
        configurator.defineSample(SRC_VAA, "VAA");
        configurator.defineSample(SRC_SAA, "SAA");

        configurator.defineSample(SRC_STATUS, "status", sourceProduct);
    }

    @Override
    protected void configureTargetSamples(SampleConfigurer configurator) throws OperatorException {
        int index = 0;
        for (String sdrMappedBandName : sdrMappedBandNames) {
            configurator.defineSample(index++, sdrMappedBandName);
        }
        for (String sigmaSdrMappedBandName : sigmaSdrMappedBandNames) {
            configurator.defineSample(index++, sigmaSdrMappedBandName);
        }
        for (String kernelBandName : kernelBandNames) {
            configurator.defineSample(index++, kernelBandName);
        }

        configurator.defineSample(index, AlbedoInversionConstants.BBDR_SNOW_MASK_NAME);
    }

    @Override
    protected void configureTargetProduct(ProductConfigurer productConfigurer) throws OperatorException {
        productConfigurer.copyTimeCoding();
        productConfigurer.copyGeoCoding();

        final Product targetProduct = productConfigurer.getTargetProduct();

        targetProduct.setAutoGrouping("sdr_sigma:sdr");
        addMappedSdrBands(targetProduct);
        addMappedSdrErrorBands(targetProduct);

        for (String bandName : kernelBandNames) {
            Band band = targetProduct.addBand(bandName, ProductData.TYPE_FLOAT32);
            band.setNoDataValue(Float.NaN);
            band.setNoDataValueUsed(true);
        }
        targetProduct.addBand(AlbedoInversionConstants.BBDR_SNOW_MASK_NAME, ProductData.TYPE_INT8);

        // copy flag coding and flag images
        ProductUtils.copyFlagBands(sourceProduct, targetProduct, true);
    }

    @Override
    protected void computePixel(int x, int y, Sample[] sourceSamples, WritableSample[] targetSamples) {
        final int status = sourceSamples[SRC_STATUS].getInt();

        // only compute over clear land (1) or snow (3):
        if (status != 1 && status != 3) {
            BbdrUtils.fillTargetSampleWithNoDataValue(targetSamples);
            return;
        }

        // prepare SDR spectral mapping...
        float[] sdr = new float[sensor.getNumBands()];
        for (int i = 0; i < sensor.getNumBands(); i++) {
            sdr[i] = sourceSamples[i].getFloat();
        }
        float[] sigmaSdr = new float[sensor.getNumBands()];
        for (int i = 0; i < sensor.getNumBands(); i++) {
            sigmaSdr[i] = sourceSamples[sensor.getNumBands() + i].getFloat();
        }

        int[] sinCoordinates = null;  // todo: clarify what means sinCoordinates
        if (x == 400 && y == 700) {
            System.out.println("status = " + status);
        }

        final float[] sdrMapped =
                getSpectralMappedSdr(numMappedSdrBands, singleBandIndex, sensor.name(), sdr, sinCoordinates, false);
//        final float[] sdrSigmaMapped =
//                getSpectralMappedSigmaSdr(numMappedSdrBands, sensor.name(), sigmaSdr, sinCoordinates, false);
        final float[] sdrSigmaMapped = getSpectralMappedSigmaSdr(numMappedSdrBands, singleBandIndex, sigmaSdr);

        // calculation of kernels (kvol, kgeo)
        final double sza = sourceSamples[SRC_SZA].getDouble();
        final double vza = sourceSamples[SRC_VZA].getDouble();
        final double saa = sourceSamples[SRC_SAA].getDouble();
        final double vaa = sourceSamples[SRC_VAA].getDouble();
        final double phi = Math.abs(saa - vaa);

        final double vzaRad = toRadians(vza);
        final double szaRad = toRadians(sza);
        final double phiRad = toRadians(phi);

        final double[] kernels = BbdrUtils.computeConstantKernels(vzaRad, szaRad, phiRad);
        final double kvol = kernels[0];
        final double kgeo = kernels[1];

        int index = 0;
        for (int i = 0; i < sdrMapped.length; i++) {
            targetSamples[index++].set(sdrMapped[i]);
        }
        for (int i = 0; i < sdrSigmaMapped.length; i++) {
            targetSamples[index++].set(sdrSigmaMapped[i]);
        }
        targetSamples[index++].set(kvol);
        targetSamples[index++].set(kgeo);

        targetSamples[index].set(sourceSamples[SRC_SNOW_MASK].getInt());
    }

    float[] getSpectralMappedSdr(int numMappedSdrBands, int singleBandIndex, String sensorName, float[] sdr,
                                 int[] sinCoordinates, boolean snow) {
//        float[] sdrMapped = new float[numMappedSdrBands];   // the 7 MODIS channels
//        return sdrMapped;
        // todo: this is preliminary. SK to explain how to address the other parameters?!
        if (numMappedSdrBands == 1) {
            return new float[]{sm.getSpectralMappedSdr(sdr)[singleBandIndex]};
        } else {
            return sm.getSpectralMappedSdr(sdr);
        }
    }

//    float[] getSpectralMappedSigmaSdr(int numMappedSdrBands, String sensorName, float[] sdrErrors,
//                                      int[] sinCoordinates, boolean snow) {
//
//        // todo: SK to explain how to address the other parameters?!
//        // todo: SK to provide 7+6+5+4+3+2+1 sigma UR matrix elements. Currently we only get diagonal elements sigma_ii!
////        return sm.getSpectralMappedSigmaSdr(sdrErrors);
//
//        // todo: this is preliminary until issues above are addressed.
//        final int numSigmaSdrMappedBands = (numMappedSdrBands * numMappedSdrBands - numMappedSdrBands) / 2 + numMappedSdrBands;
//        float[] sdrSigmaMapped = new float[numSigmaSdrMappedBands];   // 28 sigma bands for the 7 MODIS channels
//        final int[] diagonalIndices = SpectralInversionUtils.getSigmaSdrDiagonalIndices(numMappedSdrBands);
//
//        final float[] spectralMappedSigmaSdrDiagonal = sm.getSpectralMappedSigmaSdr(sdrErrors);
//        for (int i = 0; i < numSigmaSdrMappedBands; i++) {
//            // use random number in [0.0, 0.05]
//            // sdrSigmaMapped[i] = (float) Math.abs((0.1 * (Math.random() - 0.5)));
//            // better: initialize to zero
//            // sdrSigmaMapped[i] = 0.0f
//            for (int j = 0; j < diagonalIndices.length; j++) {
//                if (i == diagonalIndices[j]) {
//                    // use SK results for diagonal elements
//                    sdrSigmaMapped[i] = spectralMappedSigmaSdrDiagonal[j];
//                    break;
//                }
//            }
//        }
//        return sdrSigmaMapped;
//    }

    float[] getSpectralMappedSigmaSdr(int numMappedSdrBands, int singleBandIndex, float[] sdrErrors) {
        // todo: clarify if this is what we want
        if (numMappedSdrBands == 1) {
            return new float[]{sm.getSpectralMappedSigmaSdr(sdrErrors)[singleBandIndex]};
        } else {
            return sm.getSpectralMappedSigmaSdr(sdrErrors);
        }
    }


    private void addMappedSdrBands(Product targetProduct) {
        for (int i = 0; i < sdrMappedBandNames.length; i++) {
            Band band = targetProduct.addBand(sdrMappedBandNames[i], ProductData.TYPE_FLOAT32);
            band.setNoDataValue(Float.NaN);
            band.setNoDataValueUsed(true);
            if (numMappedSdrBands == 1) {
                band.setSpectralBandIndex(singleBandIndex);
                band.setSpectralWavelength(AlbedoInversionConstants.MODIS_WAVELENGHTS[singleBandIndex-1]);
            } else {
                band.setSpectralBandIndex(i);
                band.setSpectralWavelength(AlbedoInversionConstants.MODIS_WAVELENGHTS[i]);
            }
        }
    }

    private void addMappedSdrErrorBands(Product targetProduct) {
        for (int i = 0; i < sigmaSdrMappedBandNames.length; i++) {
            Band band = targetProduct.addBand(sigmaSdrMappedBandNames[i], ProductData.TYPE_FLOAT32);
            band.setNoDataValue(Float.NaN);
            band.setNoDataValueUsed(true);
        }
    }


    public static class Spi extends OperatorSpi {

        public Spi() {
            super(SdrSpectralMappingToModisOp.class);
        }
    }
}

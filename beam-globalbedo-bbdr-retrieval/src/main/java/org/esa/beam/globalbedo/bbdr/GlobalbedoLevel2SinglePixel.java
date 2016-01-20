/*
 * Copyright (C) 2012 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.beam.globalbedo.bbdr;


import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.globalbedo.sdr.operators.CreateElevationBandOp;
import org.esa.beam.meris.radiometry.MerisRadiometryCorrectionOp;
import org.esa.beam.meris.radiometry.equalization.ReprocessingVersion;
import org.esa.beam.util.BitSetter;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.logging.BeamLogManager;

import javax.media.jai.operator.ConstantDescriptor;
import java.text.ParseException;
import java.util.logging.Logger;

/**
 * GlobAlbedo Level 2 processor for BBDR and kernel parameter computation: special use case of single pixel input
 *
 * @author Olaf Danne
 */
@OperatorMetadata(alias = "ga.l2.single")
public class GlobalbedoLevel2SinglePixel extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @Parameter(defaultValue = "MERIS")
    private Sensor sensor;

    @Parameter(defaultValue = "")
    private String tile;

    @Override
    public void initialize() throws OperatorException {
        Logger logger = BeamLogManager.getSystemLogger();
        Product bbdrInputProduct = null;

        if (sourceProduct.getStartTime() == null) {
            try {
                // todo: these are test times. get correct start/stop from product name!!
                sourceProduct.setStartTime(ProductData.UTC.parse("11-May-2006 09:51:46"));
                sourceProduct.setEndTime(ProductData.UTC.parse("11-May-2006 09:51:46"));
            } catch (ParseException e) {
                e.printStackTrace();
            }
        }

        BbdrMasterOp bbdrOp;
        switch (sensor.getInstrument()) {
            case "MERIS":
                sourceProduct.setProductType("MER_RR_L1b");
                if (sourceProduct.getFlagCodingGroup().getNodeCount() == 0) {
                    final FlagCoding merisL1bFlagCoding = createMerisL1bFlagCoding("l1_flags");
                    sourceProduct.getFlagCodingGroup().add(merisL1bFlagCoding);
                    sourceProduct.getBand("l1_flags").setSampleCoding(merisL1bFlagCoding);
                }
                bbdrInputProduct = prepareMerisBbdrInputProduct();
                bbdrOp = new BbdrMerisOp();
                break;
            case "VGT":
                // todo: apply radiometric correction as in GaMasterOp
                bbdrInputProduct = prepareVgtBbdrInputProduct();
                bbdrOp = new BbdrVgtOp();
                break;
            default:
                throw new OperatorException("Sensor " + sensor.getInstrument() + " not supported.");
        }

        bbdrOp.setSourceProduct(bbdrInputProduct);
        bbdrOp.setParameterDefaultValues();
        bbdrOp.setParameter("sensor", sensor);
        bbdrOp.setParameter("singlePixelMode", true);
        bbdrOp.setParameter("useAotClimatology", true);
        Product bbdrProduct = bbdrOp.getTargetProduct();

        setTargetProduct(bbdrProduct);
        getTargetProduct().setProductType(sourceProduct.getProductType() + "_BBDR");
    }

    private Product prepareVgtBbdrInputProduct() {
        // todo
        return null;
    }

    private Product prepareMerisBbdrInputProduct() {
        int index = 0;
        for (Band srcBand : sourceProduct.getBands()) {
            final String srcName = srcBand.getName();
            if (srcName.startsWith("radiance_")) {
                srcBand.setSpectralBandIndex(index++);
            }
        }

        Product radiometryProduct;
        MerisRadiometryCorrectionOp merisRadiometryCorrectionOp = new MerisRadiometryCorrectionOp();
        merisRadiometryCorrectionOp.setSourceProduct(sourceProduct);
        merisRadiometryCorrectionOp.setParameterDefaultValues();
        merisRadiometryCorrectionOp.setParameter("doRadToRefl", true);
        merisRadiometryCorrectionOp.setParameter("doEqualization", true);
        merisRadiometryCorrectionOp.setParameter("reproVersion", ReprocessingVersion.REPROCESSING_3);
        radiometryProduct = merisRadiometryCorrectionOp.getTargetProduct();

        // copy all non-radiance bands from sourceProduct and
        // copy reflectance bands from reflProduct
        for (Band srcBand : radiometryProduct.getBands()) {
            final String srcName = srcBand.getName();
            if (srcName.startsWith("reflec_")) {
                final String reflectanceName = "reflectance_" + srcName.split("_")[1];
                srcBand.setName(reflectanceName);
            }
        }

//        radiometryProduct.getBand("dem_alt").setName("elevation");
        CreateElevationBandOp createElevationBandOp = new CreateElevationBandOp();
        createElevationBandOp.setParameterDefaultValues();
        createElevationBandOp.setSourceProduct(radiometryProduct);
        Product elevProduct = createElevationBandOp.getTargetProduct();
        ProductUtils.copyBand("elevation", elevProduct, radiometryProduct, true);

        final int w = radiometryProduct.getSceneRasterWidth();
        final int h = radiometryProduct.getSceneRasterHeight();
        radiometryProduct.addBand("aot", ProductData.TYPE_FLOAT32).
                setSourceImage(ConstantDescriptor.create((float)w, (float)h, new Float[]{0.15f}, null)); // dummy
        radiometryProduct.addBand("aot_err", ProductData.TYPE_FLOAT32).
                setSourceImage(ConstantDescriptor.create(1.0f, 1.0f, new Float[]{0.0f}, null)); // dummy

        return radiometryProduct;
    }

    public static FlagCoding createMerisL1bFlagCoding(String flagIdentifier) {
        FlagCoding flagCoding = new FlagCoding(flagIdentifier);
        flagCoding.addFlag("COSMETIC", BitSetter.setFlag(0, 0), null);
        flagCoding.addFlag("DUPLICATED", BitSetter.setFlag(0, 1), null);
        flagCoding.addFlag("GLINT_RISK", BitSetter.setFlag(0, 2), null);
        flagCoding.addFlag("SUSPECT", BitSetter.setFlag(0, 3), null);
        flagCoding.addFlag("LAND_OCEAN", BitSetter.setFlag(0, 4), null);
        flagCoding.addFlag("BRIGHT", BitSetter.setFlag(0, 5), null);
        flagCoding.addFlag("COASTLINE", BitSetter.setFlag(0, 6), null);
        flagCoding.addFlag("INVALID", BitSetter.setFlag(0, 7), null);
        return flagCoding;
    }


    public static class Spi extends OperatorSpi {

        public Spi() {
            super(GlobalbedoLevel2SinglePixel.class);
        }
    }
}

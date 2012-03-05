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

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.globalbedo.sdr.operators;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.FlagCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.ProductNodeGroup;
import org.esa.beam.framework.datamodel.VirtualBand;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.gpf.operators.standard.SubsetOp;
import org.esa.beam.idepix.operators.CloudScreeningSelector;
import org.esa.beam.idepix.operators.ComputeChainOp;
import org.esa.beam.jai.ImageManager;
import org.esa.beam.meris.brr.RayleighCorrectionOp;
import org.esa.beam.meris.radiometry.MerisRadiometryCorrectionOp;
import org.esa.beam.util.Guardian;
import org.esa.beam.util.ProductUtils;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.Map;

/**
 * Create Meris input product for Globalbedo aerosol retrieval and BBDR processor
 *
 * TODO: check what rad2refl does with the masks and which masks need to be copied.
 *       if the sourceProd contains already idepix, what happens with the masks in rad2refl?
 * @author akheckel
 */
@OperatorMetadata(alias = "ga.MerisPrepOp",
                  description = "Create Meris product for input to Globalbedo aerosol retrieval and BBDR processor",
                  authors = "Andreas Heckel",
                  version = "1.0",
                  copyright = "(C) 2010 by University Swansea (a.heckel@swansea.ac.uk)")
public class MerisPrepOp extends Operator {

    public static final String ALTITUDE_BAND_NAME = "altitude";
    @SourceProduct
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(defaultValue = "true",
               label = "Perform equalization",
               description = "Perform removal of detector-to-detector systematic radiometric differences in MERIS L1b data products.")
    private boolean doEqualization;
    @Parameter(defaultValue = "true",
               label = " Use land-water flag from L1b product instead")
    private boolean gaUseL1bLandWaterFlag;
    @Parameter(label = "Include the named Rayleigh Corrected Reflectances in target product")
    private String[] gaOutputRayleigh;
    @Parameter(defaultValue = "false", label = " 'P1' (LISE, O2 project, all surfaces)")
    private boolean pressureOutputP1Lise = false;
    @Parameter(defaultValue = "false", label = " Use the LC cloud buffer algorithm")
    private boolean gaLcCloudBuffer = false;

    @Override
    public void initialize() throws OperatorException {
        InstrumentConsts instrC = InstrumentConsts.getInstance();
        final boolean needPixelClassif = (!sourceProduct.containsBand(instrC.getIdepixFlagBandName()));
        final boolean needElevation = (!sourceProduct.containsBand(instrC.getElevationBandName()));
        final boolean needSurfacePres = (!sourceProduct.containsBand(instrC.getSurfPressureName("MERIS")));

        //general SzaSubset to less 70 degree
        Product szaSubProduct;
        Rectangle szaRegion = GaHelper.getSzaRegion(sourceProduct.getRasterDataNode("sun_zenith"), false, 69.99);
        if (szaRegion.x == 0 && szaRegion.y == 0 &&
                szaRegion.width == sourceProduct.getSceneRasterWidth() &&
                szaRegion.height == sourceProduct.getSceneRasterHeight()) {
            szaSubProduct = sourceProduct;
        } else if (szaRegion.width < 2 || szaRegion.height < 2) {
            targetProduct = GaMasterOp.EMPTY_PRODUCT;
            return;
        } else {
            Map<String,Object> subsetParam = new HashMap<String, Object>(3);
            subsetParam.put("region", szaRegion);
            Dimension targetTS = ImageManager.getPreferredTileSize(sourceProduct);
            RenderingHints rhTarget = new RenderingHints(GPF.KEY_TILE_SIZE, targetTS);
            szaSubProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(SubsetOp.class), subsetParam, sourceProduct, rhTarget);
        }

        // convert radiance bands to reflectance
        Map<String,Object> relfParam = new HashMap<String, Object>(3);
        relfParam.put("doRadToRefl", true);
        relfParam.put("doEqualization", doEqualization);
        Product reflProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(MerisRadiometryCorrectionOp.class), relfParam, szaSubProduct);

        // subset might have set ptype to null, thus:
        if (szaSubProduct.getDescription() == null) {
            szaSubProduct.setDescription("MERIS Radiance product");
        }

        // setup target product primarily as copy of sourceProduct
        final int rasterWidth = szaSubProduct.getSceneRasterWidth();
        final int rasterHeight = szaSubProduct.getSceneRasterHeight();
        targetProduct = new Product(szaSubProduct.getName(),
                                    szaSubProduct.getProductType(),
                                    rasterWidth, rasterHeight);
        targetProduct.setStartTime(szaSubProduct.getStartTime());
        targetProduct.setEndTime(szaSubProduct.getEndTime());
        targetProduct.setPointingFactory(szaSubProduct.getPointingFactory());
        ProductUtils.copyTiePointGrids(szaSubProduct, targetProduct);
        ProductUtils.copyFlagBands(szaSubProduct, targetProduct, true);
        ProductUtils.copyGeoCoding(szaSubProduct, targetProduct);

        // create pixel calssification if missing in sourceProduct
        // and add flag band to targetProduct
        Product idepixProduct = null;
        if (needPixelClassif) {
            Map<String, Object> pixelClassParam = new HashMap<String, Object>(4);
            pixelClassParam.put("algorithm", CloudScreeningSelector.GlobAlbedo);
            pixelClassParam.put("gaCopyRadiances", false);
            pixelClassParam.put("gaCopyAnnotations", false);
            pixelClassParam.put("gaComputeFlagsOnly", true);
            pixelClassParam.put("gaCloudBufferWidth", 3);
            pixelClassParam.put("gaOutputRayleigh", gaOutputRayleigh != null && gaOutputRayleigh.length > 0);
            pixelClassParam.put("gaUseL1bLandWaterFlag", gaUseL1bLandWaterFlag);
            pixelClassParam.put("pressureOutputP1Lise", pressureOutputP1Lise);
            pixelClassParam.put("gaLcCloudBuffer", gaLcCloudBuffer);
            idepixProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(ComputeChainOp.class), pixelClassParam, szaSubProduct);
            ProductUtils.copyFlagBands(idepixProduct, targetProduct, true);
            if (gaOutputRayleigh != null) {
                for (String rayleighBandName : gaOutputRayleigh) {
                    Band band = idepixProduct.getBand(rayleighBandName);
                    if (band != null) {
                        targetProduct.addBand(band);
                    }
                }
                ProductNodeGroup<FlagCoding> flagCodingGroup = targetProduct.getFlagCodingGroup();
                FlagCoding rayleighFlagCoding = flagCodingGroup.get(RayleighCorrectionOp.RAY_CORR_FLAGS);
                if (rayleighFlagCoding != null) {
                    flagCodingGroup.remove(rayleighFlagCoding);
                }
                Band band = idepixProduct.getBand(RayleighCorrectionOp.RAY_CORR_FLAGS);
                if (band != null) {
                    idepixProduct.removeBand(band);
                }
            }
            if (pressureOutputP1Lise) {
                    Band band = idepixProduct.getBand("p1_lise");
                    if (band != null) {
                        targetProduct.addBand(band);
                    }
            }
        }

        // create elevation product if band is missing in sourceProduct
        Product elevProduct = null;
        if (needElevation && !szaSubProduct.containsBand(ALTITUDE_BAND_NAME)){
            elevProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(CreateElevationBandOp.class), GPF.NO_PARAMS, szaSubProduct);
        }

        // create surface pressure estimate product if band is missing in sourceProduct
        VirtualBand surfPresBand = null;
        if (needSurfacePres){
            String presExpr = "(1013.25 * exp(-elevation/8400))";
            surfPresBand = new VirtualBand(instrC.getSurfPressureName("MERIS"),
                                                       ProductData.TYPE_FLOAT32,
                                                       rasterWidth, rasterHeight, presExpr);
            surfPresBand.setDescription("estimated sea level pressure (p0=1013.25hPa, hScale=8.4km)");
            surfPresBand.setNoDataValue(0);
            surfPresBand.setNoDataValueUsed(true);
            surfPresBand.setUnit("hPa");
        }



        // copy all non-radiance bands from sourceProduct and
        // copy reflectance bands from reflProduct
        for (Band srcBand : szaSubProduct.getBands()) {
            String srcName = srcBand.getName();
            if (!srcBand.isFlagBand()) {
                if (srcName.startsWith("radiance")) {
                    String reflName = "reflec_" + srcName.split("_")[1];
                    String tarName = "reflectance_" + srcName.split("_")[1];
                    ProductUtils.copyBand(reflName, reflProduct, tarName, targetProduct, true);
                } else if (!targetProduct.containsBand(srcName)) {
                    ProductUtils.copyBand(srcName, szaSubProduct, targetProduct, true);
                }
            }
        }

        // add elevation band if needed
        if (needElevation){
            if (elevProduct != null) {
                Band srcBand = elevProduct.getBand(instrC.getElevationBandName());
                Guardian.assertNotNull("elevation band", srcBand);
                ProductUtils.copyBand(srcBand.getName(), elevProduct, targetProduct, true);
            }
        }

        // add vitrual surface pressure band if needed
        if (needSurfacePres) {
            targetProduct.addBand(surfPresBand);
        }
        ProductUtils.copyPreferredTileSize(szaSubProduct, targetProduct);

    }

    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.beam.framework.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(MerisPrepOp.class);
        }
    }
}

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


import com.vividsolutions.jts.geom.Geometry;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.globalbedo.sdr.operators.GaMasterOp;
import org.esa.beam.gpf.operators.standard.SubsetOp;
import org.esa.beam.util.logging.BeamLogManager;

import java.awt.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GlobAlbedo Level 2 processor for BBDR and kernel parameter computation, and tile extraction
 *
 * @author Olaf Danne
 */
@OperatorMetadata(alias = "ga.l2")
public class GlobalbedoLevel2 extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @Parameter(defaultValue = "MERIS")
    private Sensor sensor;

    @Parameter(defaultValue = "false")     // cloud/snow flag refinement. Was not part of GA FPS processing.
    private boolean gaRefineClassificationNearCoastlines;

    @Parameter(defaultValue = "true")     // in line with GA FPS processing, BEAM 4.9.0.1. Better set to false??
    private boolean gaUseL1bLandWaterFlag;

    @Parameter(defaultValue = "false", label = " If set, we shall use AOT climatology (no retrieval)")
    private boolean useAotClimatology;

    @Parameter(defaultValue = "false", label = " If set, only SDR are computed and written")
    private boolean computeSdr;

    @Parameter(defaultValue = "false", label = " If set, BBDR are computed from previously written SDR")
    private boolean computeBbdrFromSdr;

    @Parameter(defaultValue = "false",
            label = " If set, AATSR input will be subsetted to valid lat/lon range (recommended for coregistered input)")
    private boolean subsetAatsr;

    @Parameter(defaultValue = "")
    private String tile;

    @Override
    public void initialize() throws OperatorException {
        Logger logger = BeamLogManager.getSystemLogger();

        if (sourceProduct.getPreferredTileSize() == null) {
            sourceProduct.setPreferredTileSize(sourceProduct.getSceneRasterWidth(), 45);
            System.out.println("adjusting tile size to: " + sourceProduct.getPreferredTileSize());
        }

        Product subsettedAatsrProduct = null;
        if (subsetAatsr && sensor == Sensor.AATSR_NADIR || sensor == Sensor.AATSR_FWARD) {
            subsettedAatsrProduct = subsetAatsrToDefinedRegion();
            // test to check result:
//            setTargetProduct(subsettedAatsrProduct);
//            return;
        }

        if (sensor == Sensor.PROBAV) {
            BbdrUtils.convertProbavToVgtReflectances(sourceProduct);
        }

        Product productToProcess;
        Product aotProduct;
        if (tile != null && !tile.isEmpty()) {
            Geometry geometry = TileExtractor.computeProductGeometry(TileExtractor.reprojectToModisTile(sourceProduct, tile));
            if (geometry == null) {
                throw new OperatorException("Could not get geometry for product");
            }
            SubsetOp subsetOp = new SubsetOp();
            subsetOp.setParameterDefaultValues();
            subsetOp.setGeoRegion(geometry);
            subsetOp.setSourceProduct(sourceProduct);
            productToProcess = subsetOp.getTargetProduct();
        } else {
            productToProcess = subsettedAatsrProduct != null ? subsettedAatsrProduct : sourceProduct;
        }

        if (computeBbdrFromSdr) {
            aotProduct = productToProcess;
        } else {
            GaMasterOp gaMasterOp = new GaMasterOp();
            gaMasterOp.setParameterDefaultValues();
            gaMasterOp.setParameter("copyToaRadBands", false);
            gaMasterOp.setParameter("copyToaReflBands", true);
            gaMasterOp.setParameter("gaUseL1bLandWaterFlag", gaUseL1bLandWaterFlag);    // todo: default tbd
            gaMasterOp.setParameter("gaRefineClassificationNearCoastlines", gaRefineClassificationNearCoastlines);
            gaMasterOp.setSourceProduct(productToProcess);
            aotProduct = gaMasterOp.getTargetProduct();
        }

        if (aotProduct.equals(GaMasterOp.EMPTY_PRODUCT)) {
            // todo: this happens when source product is too small (width or height < 9). Improve handling of this!
            logger.log(Level.ALL, "No AOT product generated for source product: " + sourceProduct.getName() +
                    " --> cannot create BBDR product.");
        } else {
            BbdrMasterOp bbdrOp;
            switch (sensor.getInstrument()) {
                case "MERIS":
                    if (computeSdr) {
                        bbdrOp = new SdrMerisOp();
                    } else if (computeBbdrFromSdr) {
                        bbdrOp = new BbdrFromSdrOp();
                    } else {
                        bbdrOp = new BbdrMerisOp();
                    }
                    bbdrOp.setParameterDefaultValues();
                    if (computeSdr) {
                        bbdrOp.setParameter("sdrOnly", true);
                        bbdrOp.setParameter("doUclCloudDetection", false);
                        bbdrOp.setParameter("writeGeometryAndAOT", true);
                    }
                    bbdrOp.setParameter("useAotClimatology", useAotClimatology);
                    break;
                case "VGT":
                    if (computeSdr) {
                        bbdrOp = new SdrVgtOp();
                    } else if (computeBbdrFromSdr) {
                        bbdrOp = new BbdrFromSdrOp();
                    } else {
                        bbdrOp = new BbdrVgtOp();
                    }
                    bbdrOp.setParameterDefaultValues();
                    if (computeSdr) {
                        bbdrOp.setParameter("sdrOnly", true);
                        bbdrOp.setParameter("writeGeometryAndAOT", true);
                    }
                    bbdrOp.setParameter("useAotClimatology", useAotClimatology);
                    break;
                case "PROBAV":
                    bbdrOp = new BbdrProbavOp();
                    bbdrOp.setParameterDefaultValues();
                    break;
                case "AATSR_NADIR":
                case "AATSR_FWARD":
                    computeSdr = false;   // todo: implement this option if AATSR is taken on-board
                    computeBbdrFromSdr = false;  // todo: implement this option if AATSR is taken on-board
                    bbdrOp = new BbdrAatsrOp();
                    bbdrOp.setParameterDefaultValues();
                    bbdrOp.setParameter("useAotClimatology", useAotClimatology);
                    break;
                case "AVHRR":
                    // todo
                    throw new OperatorException("Sensor " + sensor.getInstrument() + " not supported.");  // remove later
                default:
                    throw new OperatorException("Sensor " + sensor.getInstrument() + " not supported.");
            }

            bbdrOp.setSourceProduct(aotProduct);
            bbdrOp.setParameter("sensor", sensor);
            Product bbdrProduct = bbdrOp.getTargetProduct();

            if (tile != null && !tile.isEmpty()) {
                setTargetProduct(TileExtractor.reprojectToModisTile(bbdrProduct, tile));
            } else {
                setTargetProduct(bbdrProduct);
            }
            getTargetProduct().setProductType(sourceProduct.getProductType() + "_BBDR");
        }
    }

    private Product subsetAatsrToDefinedRegion() {
        final Band latBand = sourceProduct.getBand("latitude");
        final Band lonBand = sourceProduct.getBand("longitude");
//        final int startY = sourceProduct.getSceneRasterHeight()/2;
        final int startY = 2;
        final Rectangle sourceRect = new Rectangle(0, startY, sourceProduct.getSceneRasterWidth()-1, 1);
        final Tile latTile = getSourceTile(latBand, sourceRect);
        final Tile lonTile = getSourceTile(lonBand, sourceRect);

        int startX = sourceRect.width - 1;
        for (int x = 0; x <sourceRect.width; x++) {
            final boolean pixelValid = latTile.getSampleFloat(x, startY) != 0.0 && lonTile.getSampleFloat(x, startY) != 0.0;
            if (pixelValid) {
                startX = x;
                break;
            }
        }
        int endX = sourceProduct.getSceneRasterWidth()-1;
        for (int x = startX; x <sourceRect.width; x++) {
            final boolean pixelValid = latTile.getSampleFloat(x, startY) != 0.0 && lonTile.getSampleFloat(x, startY) != 0.0;
            if (!pixelValid) {
                endX = x-1;
                break;
            }
        }
        SubsetOp subsetOp = new SubsetOp();
        subsetOp.setParameterDefaultValues();
        // to be safe, cut two more pixels on each side
        final int subsetX = startX + 2;
        final int subsetY = startY;
        final int subsetWidth = endX - startX + 1 - 4;
        final int subsetHeight = sourceProduct.getSceneRasterHeight() - 1 - 4;
        System.out.println("startX = " + startX);
        System.out.println("endX = " + endX);
        System.out.println("subsetX = " + subsetX);
        System.out.println("subsetY = " + subsetY);
        System.out.println("subsetWidth = " + subsetWidth);
        System.out.println("subsetHeight = " + subsetHeight);

        final Rectangle subsetRect = new Rectangle(subsetX, subsetY, subsetWidth, subsetHeight);
        subsetOp.setRegion(subsetRect);
        subsetOp.setSourceProduct(sourceProduct);
        return subsetOp.getTargetProduct();
    }


    public static class Spi extends OperatorSpi {

        public Spi() {
            super(GlobalbedoLevel2.class);
        }
    }
}

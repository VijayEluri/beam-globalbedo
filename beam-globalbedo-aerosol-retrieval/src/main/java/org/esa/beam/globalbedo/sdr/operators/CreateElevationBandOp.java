/*
 * Copyright (C) 2002-2007 by ?
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.esa.beam.globalbedo.sdr.operators;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.framework.datamodel.*;
import org.esa.beam.framework.dataop.dem.ElevationModel;
import org.esa.beam.framework.dataop.dem.ElevationModelDescriptor;
import org.esa.beam.framework.dataop.dem.ElevationModelRegistry;
import org.esa.beam.framework.dataop.resamp.Resampling;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.util.ProductUtils;

import java.awt.*;

/**
 * Operator that creates the one and only elevation band
 * based on sourceProduct and BEAM GETASSE30
 */
@OperatorMetadata(alias = "ga.CreateElevationBandOp",
                  description = "creates a single band with elevation from getasse",
                  authors = "A.Heckel",
                  version = "1.0",
                  copyright = "(C) 2010 by University Swansea (a.heckel@swansea.ac.uk)",
                  internal = true)
public class CreateElevationBandOp extends Operator {

    @SourceProduct
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    private ElevationModel dem;
    private float noDataValue;
    private GeoCoding geoCoding;

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public CreateElevationBandOp() {
    }

    @Override
    public void initialize() throws OperatorException {
        final int rasterWidth = sourceProduct.getSceneRasterWidth();
        final int rasterHeight = sourceProduct.getSceneRasterHeight();
        geoCoding = sourceProduct.getGeoCoding();
        targetProduct = new Product("Elevation Product", "Elevation", rasterWidth, rasterHeight);
        targetProduct.setDescription("Elevation for "+sourceProduct.getName());
        ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        targetProduct.setPointingFactory(sourceProduct.getPointingFactory());

        final ElevationModelRegistry elevationModelRegistry = ElevationModelRegistry.getInstance();
        ElevationModelDescriptor demDescriptor = elevationModelRegistry.getDescriptor("GMTED2010_30");
        if (demDescriptor == null || !demDescriptor.isDemInstalled()) {
            demDescriptor = elevationModelRegistry.getDescriptor("GETASSE30");
            if (demDescriptor == null || !demDescriptor.isDemInstalled()) {
                throw new OperatorException(" No DEM installed (neither GETASSE30 nor GMTED2010_30).");
            }
        }
        getLogger().info("Dsing DEM: " + demDescriptor.getName());
        dem = demDescriptor.createDem(Resampling.BILINEAR_INTERPOLATION);
        noDataValue = dem.getDescriptor().getNoDataValue();
        String elevName = "elevation";
        Band elevBand = targetProduct.addBand(elevName, ProductData.TYPE_INT16);
        elevBand.setNoDataValue(noDataValue);
        elevBand.setNoDataValueUsed(true);
        elevBand.setUnit("meters");
        elevBand.setDescription(demDescriptor.getName());
        setTargetProduct(targetProduct);
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetBand The target band.
     * @param targetTile The current tile associated with the target band to be computed.
     * @param pm         A progress monitor which should be used to determine computation cancelation requests.
     * @throws org.esa.beam.framework.gpf.OperatorException
     *          If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        Rectangle targetTileRectangle = targetTile.getRectangle();
        int x0 = targetTileRectangle.x;
        int y0 = targetTileRectangle.y;
        int w = targetTileRectangle.width;
        int h = targetTileRectangle.height;

        pm.beginTask("Computing elevations", h);
        try {
            final GeoPos geoPos = new GeoPos();
            final PixelPos pixelPos = new PixelPos();
            float elevation;
            for (int y = y0; y < y0 + h; y++) {
                for (int x = x0; x < x0 + w; x++) {
                    pixelPos.setLocation(x + 0.5f, y + 0.5f);
                    geoCoding.getGeoPos(pixelPos, geoPos);
                    try {
                        elevation = dem.getElevation(geoPos);
                    } catch (Exception e) {
                        elevation = noDataValue;
                    }
                    targetTile.setSample(x, y, (short) Math.round(elevation));
                }
                pm.worked(1);
            }
        } finally {
            pm.done();
        }
    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.beam.framework.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CreateElevationBandOp.class);
        }
    }
}

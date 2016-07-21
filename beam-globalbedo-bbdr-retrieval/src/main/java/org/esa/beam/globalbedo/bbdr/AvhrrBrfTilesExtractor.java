/*
 * Copyright (C) 2011 Brockmann Consult GmbH (info@brockmann-consult.de)
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

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.experimental.Output;
import org.esa.beam.globalbedo.auxdata.ModisTileCoordinates;
import org.esa.beam.gpf.operators.standard.WriteOp;

import javax.media.jai.JAI;
import java.io.File;
import java.util.concurrent.*;

/**
 * Reads and reprojects an AVHRR-LTDR BRF global product onto MODIS SIN tiles and optionally converts to BBDRs.
 *
 * @author olafd
 */
@OperatorMetadata(alias = "ga.tile.avhrr",
        description = "Reads and reprojects an AVHRR-LTDR BRF global product onto MODIS SIN tiles.",
        authors = "Olaf Danne",
        version = "1.0",
        copyright = "(c) 2016 by Brockmann Consult")
public class AvhrrBrfTilesExtractor extends Operator implements Output {

    @SourceProduct
    private Product sourceProduct;

    @Parameter
    private String bbdrDir;

    @Parameter(defaultValue = "0")   // to define a subset of 'vertical stripes' of tiles
    protected int horizontalTileStartIndex;

    @Parameter(defaultValue = "35")   // to define a subset of 'vertical stripes' of tiles
    protected int horizontalTileEndIndex;

    @Parameter(defaultValue = "6.0",
            valueSet = {"0.5", "1.0", "2.0", "4.0", "6.0", "10.0", "12.0", "20.0", "60.0"},
            description = "Scale factor with regard to MODIS default 1200x1200. Values > 1.0 reduce product size.")
    protected double modisTileScaleFactor;

    @Parameter(defaultValue = "true")   // apply conversion to BBDR here
    private boolean convertToBbdr;

    private int parallelism;
    private ModisTileCoordinates tileCoordinates;

    @Override
    public void initialize() throws OperatorException {

        if (sourceProduct.getPreferredTileSize() == null) {
            sourceProduct.setPreferredTileSize(sourceProduct.getSceneRasterWidth(), 45);
            System.out.println("adjusting tile size to: " + sourceProduct.getPreferredTileSize());
        }
        parallelism = JAI.getDefaultInstance().getTileScheduler().getParallelism();
        tileCoordinates = ModisTileCoordinates.getInstance();
        doExtract_executor();
        setTargetProduct(new Product("n", "d", 1, 1));
    }

    private void doExtract_executor() {
        ExecutorService executorService = Executors.newFixedThreadPool(parallelism);
        ExecutorCompletionService<TileExtractor.TileProduct> ecs = new ExecutorCompletionService<>(executorService);

        for (int index = 0; index < tileCoordinates.getTileCount(); index++) {
            final String tileName = tileCoordinates.getTileName(index);
            Callable<TileExtractor.TileProduct> callable = new Callable<TileExtractor.TileProduct>() {
                @Override
                public TileExtractor.TileProduct call() throws Exception {
                    if (isTileToProcess(tileName)) {
                        Product reprojected = TileExtractor.reprojectToModisTile(sourceProduct, tileName, modisTileScaleFactor);
                        return new TileExtractor.TileProduct(reprojected, tileName);
                    } else {
                        return null;
                    }
                }
            };
            ecs.submit(callable);
        }
        for (int i = 0; i < tileCoordinates.getTileCount(); i++) {
            try {
                Future<TileExtractor.TileProduct> future = ecs.take();
                TileExtractor.TileProduct tileProduct = future.get();
                if (tileProduct != null && tileProduct.getProduct() != null && isTileToProcess(tileProduct.getTileName())) {
                    Product productToWrite;
                    if (convertToBbdr) {
                        // will save computation time and disk space in case of mass production...
                        BbdrAvhrrOp toBbdrOp = new BbdrAvhrrOp();
                        toBbdrOp.setParameterDefaultValues();
                        toBbdrOp.setSourceProduct(tileProduct.getProduct());
                        productToWrite = toBbdrOp.getTargetProduct();
                    } else {
                        productToWrite = tileProduct.getProduct();
                    }
                    writeTileProduct(productToWrite, tileProduct.getTileName());
                }
            } catch (Exception e) {
                throw new OperatorException(e);
            }
        }
        executorService.shutdown();
    }

    private boolean isTileToProcess(String tileName) {
        final int hIndex = Integer.parseInt(tileName.substring(1, 3));
        return horizontalTileStartIndex <= hIndex && hIndex <= horizontalTileEndIndex;
    }

    private void writeTileProduct(Product product, String tileName) {
        File dir = new File(bbdrDir, tileName);
        dir.mkdirs();
        File file;
        String writeFormat;
        if (convertToBbdr) {
            file = new File(dir, sourceProduct.getName().replace("_BRF_", "_BBDR_") + "_" + tileName + ".nc");
            writeFormat = "NetCDF4-BEAM";
        } else {
            file = new File(dir, sourceProduct.getName() + "_" + tileName + ".nc");
            writeFormat = "NetCDF4-GA-BBDR";
        }
        WriteOp writeOp = new WriteOp(product, file, writeFormat);
        writeOp.writeProduct(ProgressMonitor.NULL);
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(AvhrrBrfTilesExtractor.class);
        }
    }
}
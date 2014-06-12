/*
 * Copyright (C) 2010 Brockmann Consult GmbH (info@brockmann-consult.de)
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
package org.esa.beam.dataio.netcdf.metadata.profiles.cf;

import org.esa.beam.dataio.netcdf.Modis35ProfileReadContext;
import org.esa.beam.dataio.netcdf.metadata.Modis35ProfilePartIO;
import org.esa.beam.dataio.netcdf.util.Modis35MetadataUtils;
import org.esa.beam.framework.datamodel.Product;

import java.io.IOException;

public class Modis35CfMetadataPart extends Modis35ProfilePartIO {

    @Override
    public void decode(Modis35ProfileReadContext ctx, Product p) throws IOException {
        Modis35MetadataUtils.readNetcdfMetadata(ctx.getNetcdfFile(), p.getMetadataRoot());
    }
}

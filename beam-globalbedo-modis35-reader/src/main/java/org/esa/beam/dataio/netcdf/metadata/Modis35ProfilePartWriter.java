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

package org.esa.beam.dataio.netcdf.metadata;

import org.esa.beam.dataio.netcdf.Modis35ProfileWriteContext;
import org.esa.beam.framework.datamodel.Product;

import java.io.IOException;

/**
 * Implementations write a part of a {@link Product Product} to a NetCDF-file. They are provided to the framework by implementations
 * of {@link org.esa.beam.dataio.netcdf.AbstractNetCdfWriterPlugIn AbstractNetCdfWriterPlugIn}.
 * The methods of all {@see ProfilePartWriter} belonging to a {@link org.esa.beam.dataio.netcdf.AbstractNetCdfWriterPlugIn AbstractNetCdfWriterPlugIn}
 * are called in the following sequence
 * <ol>
 * <li> {@link #preEncode(org.esa.beam.dataio.netcdf.Modis35ProfileWriteContext, Product)}</li>
 * <li> {@link #encode(org.esa.beam.dataio.netcdf.Modis35ProfileWriteContext, Product)}</li>
 * </ol>
 * If two parts of one {@link org.esa.beam.dataio.netcdf.AbstractNetCdfWriterPlugIn AbstractNetCdfWriterPlugIn}
 * implementation depend on each other, the twofold encoding helps two transport information from one part to the other.
 * One part can store information in the {@link org.esa.beam.dataio.netcdf.Modis35ProfileWriteContext context} and the other can
 * retrieve it in the successive encoding step.
 */
public interface Modis35ProfilePartWriter {

    /**
     * When called the {@link org.esa.beam.dataio.netcdf.Modis35ProfileWriteContext#getNetcdfFileWriteable() ctx.getNetcdfFileWriteable()} is in define mode.
     * Which means that variables and attributes can be added, but no data can be written.
     *
     * @param ctx the context for writing the product
     * @param p the product
     * @throws IOException if an IO-Error occurs
     */
    void preEncode(Modis35ProfileWriteContext ctx, Product p) throws IOException;

    /**
     * When called the {@link org.esa.beam.dataio.netcdf.Modis35ProfileWriteContext#getNetcdfFileWriteable() ctx.getNetcdfFileWriteable()} is in write mode.
     * Which means that data can be written, but no variables and attributes can be added.
     *  
     * @param ctx the context for writing the product
     * @param p the product
     * @throws IOException if an IO-Error occurs
     */
    void encode(Modis35ProfileWriteContext ctx, Product p) throws IOException;

}

package org.esa.beam.globalbedo.inversion;

import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.experimental.PixelOperator;

import java.util.List;

/**
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
public class SingleDayAccumulator extends PixelOperator {

    @Parameter(description = "BBDR source products")
    private Product[] sourceProducts;

    @Override
    protected void configureSourceSamples(Configurator configurator) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    protected void configureTargetProduct(Product targetProduct) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    protected void configureTargetSamples(Configurator configurator) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    protected void computePixel(int x, int y, Sample[] sourceSamples,
                                WritableSample[] targetSamples) {
        //To change body of implemented methods use File | Settings | File Templates.
    }
}

package org.esa.beam.globalbedo.inversion.spectral;

import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.pointop.PixelOperator;
import org.esa.beam.framework.gpf.pointop.Sample;
import org.esa.beam.framework.gpf.pointop.SampleConfigurer;
import org.esa.beam.framework.gpf.pointop.WritableSample;

/**
 * todo: add comment
 * To change this template use File | Settings | File Templates.
 * Date: 20.05.2016
 * Time: 18:28
 *
 * @author olafd
 */
public class SpectralBrdfToAlbedoOp extends PixelOperator {
    @Override
    protected void computePixel(int x, int y, Sample[] sourceSamples, WritableSample[] targetSamples) {

    }

    @Override
    protected void configureSourceSamples(SampleConfigurer sampleConfigurer) throws OperatorException {

    }

    @Override
    protected void configureTargetSamples(SampleConfigurer sampleConfigurer) throws OperatorException {

    }
}

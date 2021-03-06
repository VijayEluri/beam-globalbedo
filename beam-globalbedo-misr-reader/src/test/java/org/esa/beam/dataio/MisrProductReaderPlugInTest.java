package org.esa.beam.dataio;

import org.esa.beam.framework.dataio.ProductReader;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;

public class MisrProductReaderPlugInTest {

    private MisrProductReaderPlugIn plugIn;

    @Before
    public void setup() {
        plugIn = new MisrProductReaderPlugIn();
    }

    @Test
    public void testCreateReaderInstanceReturnsNewInstanceEachTime() {
        ProductReader firstInstance = plugIn.createReaderInstance();
        assertNotNull(firstInstance);
        ProductReader secondInstance = plugIn.createReaderInstance();
        assertNotSame(secondInstance, firstInstance);
    }

}

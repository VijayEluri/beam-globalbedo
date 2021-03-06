package org.esa.beam.globalbedo.bbdr.seaice;

import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.gpf.operators.standard.reproject.ReprojectionOp;

@OperatorMetadata(alias = "ga.polarstereo",
        description = "Reprojects a source product onto polar stereographic projection." +
                "Source product is expected to cover only regions > 70N.",
        authors = "Olaf Danne",
        version = "1.0",
        copyright = "(C) 2013 by Brockmann Consult")
public class PolarStereographicOp extends Operator {

    @SourceProduct(alias = "source", description = "The source product to reproject.")
    private Product sourceProduct;

    @Parameter(defaultValue = "true")
    private boolean doLatlon;

    @Parameter(defaultValue = "true")
    private boolean doPst;

    @Override
    public void initialize() throws OperatorException {

        // to circumvent possible problems at dateline, do a double reprojection:
        // 1. sat coords --> Geographic lat/lon
        // 2. Geographic lat/lon --> Polar Stereographic, resize to RR (1200m/pixel)

        Product latlonProduct;
        Product pstProduct = sourceProduct;
        if (doLatlon) {
            latlonProduct = reprojectToGeographicLatLon(sourceProduct);
            if (doPst) {
                pstProduct = reprojectToPolarStereographic(latlonProduct);
            } else {
                pstProduct = latlonProduct;
            }
        } else {
            if (doPst) {
                pstProduct = reprojectToPolarStereographic(sourceProduct);
            }
        }
        setTargetProduct(pstProduct);
    }

    static Product reprojectToPolarStereographic(Product latlonProduct,
                                                 double referencePixelX, double referencePixelY,
                                                 double pixelSizeX, double pixelSizeY,
                                                 int width, int height) {

        ReprojectionOp repro = new ReprojectionOp();
        repro.setParameterDefaultValues();

        repro.setParameter("crs", "PROJCS[\"Polar_Stereographic / World Geodetic System 1984\"," +
                "GEOGCS[\"World Geodetic System 1984\"," +
                " DATUM[\"World Geodetic System 1984\"," +
                "  SPHEROID[\"WGS 84\",6378137.0, 298.257223563, AUTHORITY[\"EPSG\",\"7030\"]]," +
                "   AUTHORITY[\"EPSG\",\"6326\"]]," +
                "  PRIMEM[\"Greenwich\",0.0, AUTHORITY[\"EPSG\",\"8901\"]]," +
                "  UNIT[\"degree\",0.01745329251994328]," +
                "   AXIS[\"Geodetic longitude\", EAST]," +
                "   AXIS[\"Geodetic latitude\", NORTH]]," +
                "PROJECTION[\"Polar_Stereographic\"]," +
                "PARAMETER[\"semi_minor\",6378137.0]," +
                "PARAMETER[\"central_meridian\",0.0]," +
                "PARAMETER[\"latitude_of_origin\",90.0]," +
                "PARAMETER[\"scale_factor\",1.0]," +
                "PARAMETER[\"false_easting\",0.0]," +
                "PARAMETER[\"false_northing\",0.0]," +
                "UNIT[\"m\",1.0]," +
                "AXIS[\"Easting\", EAST]," +
                "AXIS[\"Northing\", NORTH]]");

        repro.setParameter("easting", 89.999999999);
        repro.setParameter("northing", 0.0);
        repro.setParameter("includeTiePointGrids", true);
        repro.setParameter("referencePixelX", referencePixelX);   // 0.0 or 2250.0
        repro.setParameter("referencePixelY", referencePixelY);   // 0.0 or 2250.0
        repro.setParameter("orientation", 0.0);
        repro.setParameter("pixelSizeX", pixelSizeX);             // 1000.0
        repro.setParameter("pixelSizeY", pixelSizeY);             // 1000.0
        repro.setParameter("width", width);                       // 4500
        repro.setParameter("height", height);                     // 4500
        repro.setParameter("orthorectify", true);
        repro.setSourceProduct(latlonProduct);

        final Product reprojectedProduct = repro.getTargetProduct();
        reprojectedProduct.setName(latlonProduct.getName());
        reprojectedProduct.setProductType(latlonProduct.getProductType());

        return reprojectedProduct;
    }

    private static Product reprojectToGeographicLatLon(Product origProduct) {

        ReprojectionOp repro = new ReprojectionOp();
        repro.setParameterDefaultValues();

        repro.setParameter("easting", 0.0);
        repro.setParameter("northing", 80.0);
        repro.setParameter("crs", "EPSG:4326");
        repro.setParameter("resampling", "Nearest");
        repro.setParameter("includeTiePointGrids", true);
        repro.setParameter("referencePixelX", 8100.5);
        repro.setParameter("referencePixelY", 450.5);
        repro.setParameter("orientation", 0.0);
        repro.setParameter("pixelSizeX", 0.02222222);
        repro.setParameter("pixelSizeY", 0.02222222);
        repro.setParameter("width", 16200);
        repro.setParameter("height", 900);
        repro.setParameter("orthorectify", true);
        repro.setSourceProduct(origProduct);

        repro.setParameter("easting", 0.0);
        repro.setParameter("northing", 80.0);
        repro.setParameter("crs", "EPSG:4326");
        repro.setParameter("resampling", "Nearest");
        repro.setParameter("includeTiePointGrids", true);
        repro.setParameter("referencePixelX", 4050.5);
        repro.setParameter("referencePixelY", 225.5);
        repro.setParameter("orientation", 0.0);
        repro.setParameter("pixelSizeX", 0.04444444);
        repro.setParameter("pixelSizeY", 0.04444444);
        repro.setParameter("width", 8100);
        repro.setParameter("height", 450);
        repro.setParameter("orthorectify", true);
        repro.setSourceProduct(origProduct);

        final Product reprojectedProduct = repro.getTargetProduct();
        reprojectedProduct.setName(origProduct.getName());
        reprojectedProduct.setProductType(origProduct.getProductType());

        return reprojectedProduct;
    }

    private static Product reprojectToPolarStereographic(Product latlonProduct) {

        ReprojectionOp repro = new ReprojectionOp();
        repro.setParameterDefaultValues();

        repro.setParameter("crs", "PROJCS[\"Polar_Stereographic / World Geodetic System 1984\"," +
                "GEOGCS[\"World Geodetic System 1984\"," +
                " DATUM[\"World Geodetic System 1984\"," +
                "  SPHEROID[\"WGS 84\",6378137.0, 298.257223563, AUTHORITY[\"EPSG\",\"7030\"]]," +
                "   AUTHORITY[\"EPSG\",\"6326\"]]," +
                "  PRIMEM[\"Greenwich\",0.0, AUTHORITY[\"EPSG\",\"8901\"]]," +
                "  UNIT[\"degree\",0.01745329251994328]," +
                "   AXIS[\"Geodetic longitude\", EAST]," +
                "   AXIS[\"Geodetic latitude\", NORTH]]," +
                "PROJECTION[\"Polar_Stereographic\"]," +
                "PARAMETER[\"semi_minor\",6378137.0]," +
                "PARAMETER[\"central_meridian\",0.0]," +
                "PARAMETER[\"latitude_of_origin\",90.0]," +
                "PARAMETER[\"scale_factor\",1.0]," +
                "PARAMETER[\"false_easting\",0.0]," +
                "PARAMETER[\"false_northing\",0.0]," +
                "UNIT[\"m\",1.0]," +
                "AXIS[\"Easting\", EAST]," +
                "AXIS[\"Northing\", NORTH]]");

        repro.setParameter("easting", 89.999999999);
        repro.setParameter("northing", 0.0);
        repro.setParameter("includeTiePointGrids", true);
        repro.setParameter("referencePixelX", 1875.0);
        repro.setParameter("referencePixelY", 1875.0);
        repro.setParameter("orientation", 0.0);
        repro.setParameter("pixelSizeX", 1200.0);
        repro.setParameter("pixelSizeY", 1200.0);
        repro.setParameter("width", 3750);
        repro.setParameter("height", 3750);
        repro.setParameter("orthorectify", true);
        repro.setSourceProduct(latlonProduct);

        final Product reprojectedProduct = repro.getTargetProduct();
        reprojectedProduct.setName(latlonProduct.getName());
        reprojectedProduct.setProductType(latlonProduct.getProductType());

        return reprojectedProduct;
    }


    public static class Spi extends OperatorSpi {

        public Spi() {
            super(PolarStereographicOp.class);
        }
    }
}

package org.esa.beam.globalbedo.inversion;

import Jama.Matrix;
import org.esa.beam.globalbedo.inversion.attic.InversionOpOld;

/**
 * Container object holding the M, V, E estimation matrices and mask value
 *
 * @author Olaf Danne
 * @version $Revision: $ $Date:  $
 */
public class Accumulator {

    private Matrix M;
    private Matrix V;
    private Matrix E;
    private double mask;

    public Accumulator(Matrix m, Matrix v, Matrix e, double mask) {
        this.M = m;
        this.V = v;
        this.E = e;
        this.mask = mask;
    }

    /**
     * Returns an accumulator object built from matrix array of a full accumulator product to be used
     * for inversion in {@link InversionOpOld}}.
     *
     * @param sumMatrices - array holding M, V, E, mask
     * @param x - pixel_x
     * @param y - pixel_y
     *
     * @return Accumulator
     */
    public static Accumulator createForInversion(float[][][] sumMatrices, int x, int y) {

        Matrix M = new Matrix(3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS,
                3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS);
        Matrix V = new Matrix(3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS, 1);
        Matrix E = new Matrix(1, 1);

        int index = 0;
        for (int i = 0; i < 3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; i++) {
            for (int j = 0; j < 3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; j++) {
                M.set(i, j, sumMatrices[index++][x][y]);
            }
        }
        for (int i = 0; i < 3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS; i++) {
            V.set(i, 0, sumMatrices[index++][x][y]);
        }
        E.set(0, 0, sumMatrices[index++][x][y]);

        final double mask = sumMatrices[index][x][y];

        return new Accumulator(M, V, E, mask);
    }

    public static Accumulator createZeroAccumulator() {
        final Matrix zeroM = new Matrix(3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS,
                                        3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS);
        final Matrix zeroV = new Matrix(3 * AlbedoInversionConstants.NUM_BBDR_WAVE_BANDS, 1);
        final Matrix zeroE = new Matrix(1, 1);

        return new Accumulator(zeroM, zeroV, zeroE, 0);
    }

    // getters and setters...
    public Matrix getM() {
        return M;
    }

    public Matrix getV() {
        return V;
    }

    public Matrix getE() {
        return E;
    }

    public double getMask() {
        return mask;
    }

    public void setM(Matrix m) {
        M = m;
    }

    public void setV(Matrix v) {
        V = v;
    }

    public void setE(Matrix e) {
        E = e;
    }

    public void setMask(double mask) {
        this.mask = mask;
    }
}

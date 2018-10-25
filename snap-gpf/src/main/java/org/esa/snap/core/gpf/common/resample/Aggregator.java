package org.esa.snap.core.gpf.common.resample;

import javax.media.jai.RasterAccessor;

/**
 * @author Tonio Fincke
 */
public interface Aggregator {

    void init(RasterAccessor srcAccessor, RasterAccessor dstAccessor, double nodataValue);

    void aggregate(int srcY0, int srcY1, int srcX0, int srcX1, int srcScanlineStride, double wx0, double wx1, double wy0, double wy1, int dstPos);

}

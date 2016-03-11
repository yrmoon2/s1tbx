package org.esa.snap.core.gpf.common.resample;

import com.bc.ceres.core.Assert;
import com.bc.ceres.glevel.MultiLevelImage;
import com.bc.ceres.glevel.MultiLevelModel;
import com.bc.ceres.glevel.support.AbstractMultiLevelSource;
import com.bc.ceres.glevel.support.DefaultMultiLevelImage;
import com.bc.ceres.glevel.support.DefaultMultiLevelModel;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.esa.snap.core.datamodel.Mask;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.esa.snap.core.datamodel.RasterDataNode;
import org.esa.snap.core.datamodel.Scene;
import org.esa.snap.core.datamodel.SceneFactory;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.image.ImageManager;
import org.esa.snap.core.image.ResolutionLevel;
import org.esa.snap.core.transform.MathTransform2D;
import org.esa.snap.core.util.ProductUtils;

import javax.media.jai.ImageLayout;
import java.awt.Dimension;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.RenderedImage;

/**
 * @author Tonio Fincke
 */
@OperatorMetadata(alias = "Resample",
        version = "2.0",
        authors = "Tonio Fincke",
        copyright = "(c) 2016 by Brockmann Consult",
        description = "Resampling of a multi-size source product to a single-size target product.")
public class ResamplingOp extends Operator {

    private static final String NAME_EXTENSION = "resampled";

    @SourceProduct(description = "The source product which is to be resampled.", label = "Name")
    Product sourceProduct;

    @TargetProduct(description = "The resampled target product.")
    Product targetProduct;

    //todo also allow to set a target size/resolution explicitly
    @Parameter(alias = "referenceBand",label = "Reference Band", description = "The name of the reference band. " +
            "All other bands will be re-sampled to match its size and resolution.",
            rasterDataNodeType = Band.class,
            notEmpty = true
    )
    String referenceBandName;

    @Parameter(alias = "interpolation",
            label = "Interpolation Method",
            description = "The method used for interpolation (upsampling to a finer resolution).",
            valueSet = {"NearestNeighbour", "Bilinear"}, //todo add bicubic interpolation
            defaultValue = "NearestNeighbour"
    )
    private String interpolationMethod;

    @Parameter(alias = "aggregation",
            label = "Aggregation Method",
            description = "The method used for aggregation (downsampling to a coarser resolution).",
            valueSet = {"First", "Min", "Max", "Mean", "Median"},
            defaultValue = "First")
    private String aggregationMethod;

    @Parameter(alias = "flagAggregation",
            label = "Flag Aggregation Method",
            description = "The method used for aggregation (downsampling to a coarser resolution) of flags.",
            valueSet = {"First", "FlagAnd", "FlagOr", "FlagMedianAnd", "FlagMedianOr"},
            defaultValue = "First")
    private String flagAggregationMethod;

    private InterpolationType interpolationType;
    private AggregationType aggregationType;
    private AggregationType flagAggregationType;

    @Override
    public void initialize() throws OperatorException {
        if (!sourceProduct.isMultiSize()) {
            targetProduct = sourceProduct;
            return;
        }
        if (!allNodesHaveIdentitySceneTransform(sourceProduct)) {
            throw new OperatorException("Not all nodes have identity model-to-scene transform.");
        }
        setResamplingTypes();
        final Band referenceBand = sourceProduct.getBand(referenceBandName);
        Assert.notNull(referenceBand);
        final int referenceWidth = referenceBand.getRasterWidth();
        final int referenceHeight = referenceBand.getRasterHeight();
        targetProduct = new Product(sourceProduct.getName() + "_" + NAME_EXTENSION, sourceProduct.getProductType(),
                                    referenceWidth, referenceHeight);
        resampleBands(referenceBand);
        resampleTiePointGrids(referenceBand);
        ProductUtils.copyFlagCodings(sourceProduct, targetProduct);
        ProductUtils.copyIndexCodings(sourceProduct, targetProduct);
        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        transferGeoCoding(referenceBand, targetProduct);
        ProductUtils.copyVectorData(sourceProduct, targetProduct);
        copyMasks(sourceProduct, targetProduct);
        targetProduct.setAutoGrouping(sourceProduct.getAutoGrouping());
    }

    private static void transferGeoCoding(Band referenceBand, Product targetProduct) {
        final Scene srcScene = SceneFactory.createScene(referenceBand);
        final Scene destScene = SceneFactory.createScene(targetProduct);
        if (srcScene != null && destScene != null) {
            srcScene.transferGeoCodingTo(destScene, null);
        }
    }

    //convenience method to increase speed for band maths type masks
    private static void copyMasks(Product sourceProduct, Product targetProduct) {
        final ProductNodeGroup<Mask> sourceMaskGroup = sourceProduct.getMaskGroup();
        for (int i = 0; i < sourceMaskGroup.getNodeCount(); i++) {
            final Mask mask = sourceMaskGroup.get(i);
            final Mask.ImageType imageType = mask.getImageType();
            if (imageType.getName().equals(Mask.BandMathsType.TYPE_NAME)) {
                String expression = Mask.BandMathsType.getExpression(mask);
                final Mask targetMask = Mask.BandMathsType.create(mask.getName(), mask.getDescription(),
                                                                  targetProduct.getSceneRasterWidth(),
                                                                  targetProduct.getSceneRasterHeight(), expression,
                                                                  mask.getImageColor(), mask.getImageTransparency());
                targetProduct.addMask(targetMask);
            } else if (imageType.canTransferMask(mask, targetProduct)) {
                imageType.transferMask(mask, targetProduct);
            }
        }
    }

    public static boolean canBeApplied(Product product) {
        return allNodesHaveIdentitySceneTransform(product);
    }

    static boolean allNodesHaveIdentitySceneTransform(Product product) {
        final ProductNodeGroup<Band> bandGroup = product.getBandGroup();
        for (int i = 0; i < bandGroup.getNodeCount(); i++) {
            if (bandGroup.get(i).getModelToSceneTransform() != MathTransform2D.IDENTITY) {
                return false;
            }
        }
        final ProductNodeGroup<TiePointGrid> tiePointGridGroup = product.getTiePointGridGroup();
        for (int i = 0; i < tiePointGridGroup.getNodeCount(); i++) {
            if (tiePointGridGroup.get(i).getModelToSceneTransform() != MathTransform2D.IDENTITY) {
                return false;
            }
        }
        return true;
    }

    private void resampleTiePointGrids(Band referenceBand) {
        final ProductNodeGroup<TiePointGrid> tiePointGridGroup = sourceProduct.getTiePointGridGroup();
        final AffineTransform referenceTransform = referenceBand.getImageToModelTransform();
        for (int i = 0; i < tiePointGridGroup.getNodeCount(); i++) {
            final TiePointGrid grid = tiePointGridGroup.get(i);
            AffineTransform transform;
            try {
                transform = new AffineTransform(referenceTransform.createInverse());
            } catch (NoninvertibleTransformException e) {
                throw new OperatorException("Cannot resample: " + e.getMessage());
            }
            final AffineTransform gridTransform = grid.getImageToModelTransform();
            transform.concatenate(gridTransform);
            if (Math.abs(transform.getScaleX() - 1.0) > 1e-8 || Math.abs(transform.getScaleY() - 1.0) > 1e-8 ||
                    referenceTransform.getTranslateX() != 0 || referenceTransform.getTranslateY() != 0) {
                double subSamplingX = grid.getSubSamplingX() * transform.getScaleX();
                double subSamplingY = grid.getSubSamplingY() * transform.getScaleY();
                double offsetX = (grid.getOffsetX() * transform.getScaleX()) - (referenceTransform.getTranslateX() / gridTransform.getScaleX());
                double offsetY = (grid.getOffsetY() * transform.getScaleY()) - (referenceTransform.getTranslateY() / gridTransform.getScaleY());

                final TiePointGrid resampledGrid = new TiePointGrid(grid.getName(), grid.getGridWidth(), grid.getGridHeight(),
                                                                    offsetX, offsetY,
                                                                    subSamplingX, subSamplingY, grid.getTiePoints());
                targetProduct.addTiePointGrid(resampledGrid);
                ProductUtils.copyRasterDataNodeProperties(grid, resampledGrid);
            } else {
                ProductUtils.copyTiePointGrid(grid.getName(), sourceProduct, targetProduct);
            }
        }
    }

    private void resampleBands(Band referenceBand) {
        final ProductNodeGroup<Band> sourceBands = sourceProduct.getBandGroup();
        final int referenceWidth = referenceBand.getRasterWidth();
        final int referenceHeight = referenceBand.getRasterHeight();
        Dimension referenceSize = referenceBand.getRasterSize();
        MultiLevelModel multiLevelModel;
        if (referenceBand.getGeoCoding() instanceof CrsGeoCoding) {
            multiLevelModel = referenceBand.getMultiLevelModel();
        } else {
            multiLevelModel = new DefaultMultiLevelModel(new AffineTransform(), referenceWidth, referenceHeight);
        }
        for (int i = 0; i < sourceBands.getNodeCount(); i++) {
            Band sourceBand = sourceBands.get(i);
            final int dataBufferType = ImageManager.getDataBufferType(sourceBand.getDataType());
            Band targetBand;
            AffineTransform sourceTransform = sourceBand.getImageToModelTransform();
            AffineTransform referenceTransform = referenceBand.getImageToModelTransform();
            final boolean isVirtualBand = sourceBand instanceof VirtualBand;
            if (!sourceBand.getRasterSize().equals(referenceSize) && !isVirtualBand) {
                targetBand = new Band(sourceBand.getName(), sourceBand.getDataType(), referenceWidth, referenceHeight);
                MultiLevelImage targetImage = sourceBand.getSourceImage();
                MultiLevelImage sourceImage = sourceBand.getSourceImage();
                if (referenceWidth <= sourceBand.getRasterWidth() && referenceHeight <= sourceBand.getRasterHeight()) {
                    targetImage = createAggregatedImage(sourceImage, dataBufferType, sourceBand.getNoDataValue(),
                                                        referenceBand, sourceBand.isFlagBand());
                } else if (referenceWidth >= sourceBand.getRasterWidth() && referenceHeight >= sourceBand.getRasterHeight()) {
                    targetImage = createInterpolatedImage(sourceImage, dataBufferType, sourceBand.getNoDataValue(),
                                                          referenceBand);
                } else if (referenceWidth < sourceBand.getRasterWidth()) {
                    Band intermediateBand = new Band("intermediate", ProductData.TYPE_INT8, referenceWidth, sourceBand.getRasterHeight());
                    AffineTransform intermediateTransform = new AffineTransform(
                            referenceTransform.getScaleX(), referenceTransform.getShearX(), sourceTransform.getShearY(),
                            sourceTransform.getScaleY(), referenceTransform.getTranslateX(), sourceTransform.getTranslateY());
                    intermediateBand.setImageToModelTransform(intermediateTransform);
                    targetImage = createAggregatedImage(targetImage, dataBufferType, sourceBand.getNoDataValue(),
                                                        intermediateBand, sourceBand.isFlagBand());
                    targetImage = createInterpolatedImage(targetImage, dataBufferType, sourceBand.getNoDataValue(),
                                                          referenceBand);
                } else if (referenceHeight < sourceBand.getRasterHeight()) {
                    Band intermediateBand = new Band("intermediate", ProductData.TYPE_INT8, sourceBand.getRasterWidth(), referenceHeight);
                    AffineTransform intermediateTransform = new AffineTransform(
                            sourceTransform.getScaleX(), sourceTransform.getShearX(), referenceTransform.getShearY(),
                            referenceTransform.getScaleY(), sourceTransform.getTranslateX(), referenceTransform.getTranslateY());
                    intermediateBand.setImageToModelTransform(intermediateTransform);
                    targetImage = createAggregatedImage(targetImage, dataBufferType, sourceBand.getNoDataValue(),
                                                        intermediateBand, sourceBand.isFlagBand());
                    targetImage = createInterpolatedImage(targetImage, dataBufferType, sourceBand.getNoDataValue(),
                                                          referenceBand);
                }
                targetBand.setSourceImage(adjustImageToModelTransform(targetImage, multiLevelModel));
                targetProduct.addBand(targetBand);
            } else {
                if (isVirtualBand) {
                    targetBand = ProductUtils.copyVirtualBand(targetProduct, (VirtualBand) sourceBand, sourceBand.getName());
                    targetBand.setImageToModelTransform(referenceBand.getImageToModelTransform());
                } else {
                    targetBand = ProductUtils.copyBand(sourceBand.getName(), sourceProduct, targetProduct, false);
                    targetBand.setSourceImage(adjustImageToModelTransform(sourceBand.getSourceImage(), multiLevelModel));
                }
            }
            ProductUtils.copyRasterDataNodeProperties(sourceBand, targetBand);
        }
    }

    private RenderedImage adjustImageToModelTransform(final MultiLevelImage image, MultiLevelModel model) {
        MultiLevelModel actualModel = model;
        if (model.getLevelCount() > image.getModel().getLevelCount()) {
            actualModel = new DefaultMultiLevelModel(image.getModel().getLevelCount(), model.getImageToModelTransform(0),
                                                     image.getWidth(), image.getHeight());
        }
        final AbstractMultiLevelSource source = new AbstractMultiLevelSource(actualModel) {
            @Override
            protected RenderedImage createImage(int level) {
                return image.getImage(level);
            }
        };
        return new DefaultMultiLevelImage(source);
    }

    private MultiLevelImage createInterpolatedImage(MultiLevelImage sourceImage, int dataType, double noDataValue,
                                                    final RasterDataNode referenceBand) {
        if (interpolationType == null) {
            throw new OperatorException("Invalid upsampling method");
        }

        final AbstractMultiLevelSource source = new AbstractMultiLevelSource(referenceBand.getMultiLevelModel()) {
            @Override
            protected RenderedImage createImage(int targetLevel) {
                final MultiLevelModel targetModel = getModel();
                final double scale = targetModel.getScale(targetLevel);
                final MultiLevelModel sourceModel = sourceImage.getModel();
                final int sourceLevel = sourceModel.getLevel(scale);
                final RenderedImage sourceLevelImage = sourceImage.getImage(sourceLevel);
                final RenderedImage referenceImage = referenceBand.getSourceImage().getImage(targetLevel);
                final int width = referenceImage.getWidth();
                final int height = referenceImage.getHeight();
                final int tileWidth = referenceImage.getTileWidth();
                final int tileHeight = referenceImage.getTileHeight();
                final ImageLayout imageLayout = ImageManager.createSingleBandedImageLayout(dataType,
                                                                                           width,
                                                                                           height,
                                                                                           tileWidth,
                                                                                           tileHeight);
                try {
                    return new InterpolatedOpImage(sourceLevelImage, imageLayout, noDataValue, dataType, interpolationType,
                                                   sourceModel.getImageToModelTransform(sourceLevel),
                                                   targetModel.getImageToModelTransform(targetLevel));
                } catch (NoninvertibleTransformException e) {
                    throw new OperatorException("Could not downsample band image");
                }
            }
        };
        return new DefaultMultiLevelImage(source);
    }

    private RenderedImage createInterpolatedImage(RenderedImage sourceImage, int dataType, double noDataValue,
                                                  AffineTransform sourceTransform, final RasterDataNode referenceBand) {
        if (interpolationType == null) {
            throw new OperatorException("Invalid upsampling method");
        }
        final ImageLayout imageLayout = ImageManager.createSingleBandedImageLayout(referenceBand, dataType);
        try {
            return new InterpolatedOpImage(sourceImage, imageLayout, noDataValue, dataType, interpolationType,
                                           sourceTransform, referenceBand.getImageToModelTransform());
        } catch (NoninvertibleTransformException e) {
            throw new OperatorException("Could not upsample band image");
        }
    }

    private MultiLevelImage createAggregatedImage(MultiLevelImage sourceImage, int dataType, double noDataValue,
                                                  final RasterDataNode referenceBand, boolean isFlagBand) {
        AggregationType type;
        if (isFlagBand) {
            if (flagAggregationType == null) {
                throw new OperatorException("Invalid flag downsampling method");
            }
            type = flagAggregationType;
        } else {
            if (aggregationType == null) {
                throw new OperatorException("Invalid downsampling method");
            }
            type = aggregationType;
        }
        final AbstractMultiLevelSource source = new AbstractMultiLevelSource(referenceBand.getMultiLevelModel()) {
            @Override
            protected RenderedImage createImage(int targetLevel) {
                final MultiLevelModel targetModel = getModel();
                final double scale = targetModel.getScale(targetLevel);
                final MultiLevelModel sourceModel = sourceImage.getModel();
                final int sourceLevel = sourceModel.getLevel(scale);
                final RenderedImage sourceLevelImage = sourceImage.getImage(sourceLevel);
                final RenderedImage referenceImage = referenceBand.getSourceImage().getImage(targetLevel);
                final int width = referenceImage.getWidth();
                final int height = referenceImage.getHeight();
                final int tileWidth = referenceImage.getTileWidth();
                final int tileHeight = referenceImage.getTileHeight();
                final ImageLayout imageLayout = ImageManager.createSingleBandedImageLayout(dataType,
                                                                                           width,
                                                                                           height,
                                                                                           tileWidth,
                                                                                           tileHeight
                );
                try {
                    return new AggregatedOpImage(sourceLevelImage, imageLayout, noDataValue, type, dataType,
                                                 sourceModel.getImageToModelTransform(sourceLevel),
                                                 targetModel.getImageToModelTransform(targetLevel));
                } catch (NoninvertibleTransformException e) {
                    throw new OperatorException("Could not downsample band image");
                }
            }
        };
        return new DefaultMultiLevelImage(source);
    }

    private RenderedImage createAggregatedImage(RenderedImage sourceImage, int dataType, double noDataValue,
                                                AffineTransform sourceTransform, final RasterDataNode referenceBand,
                                                boolean isFlagBand) {
        AggregationType type;
        if (isFlagBand) {
            if (flagAggregationType == null) {
                throw new OperatorException("Invalid flag downsampling method");
            }
            type = flagAggregationType;
        } else {
            if (aggregationType == null) {
                throw new OperatorException("Invalid downsampling method");
            }
            type = aggregationType;
        }
        final ImageLayout imageLayout = ImageManager.createSingleBandedImageLayout(dataType,
                                                                                   referenceBand.getRasterWidth(),
                                                                                   referenceBand.getRasterHeight(),
                                                                                   sourceProduct.getPreferredTileSize(),
                                                                                   ResolutionLevel.MAXRES);
        try {
            return new AggregatedOpImage(sourceImage, imageLayout, noDataValue, type, dataType,
                                         sourceTransform, referenceBand.getImageToModelTransform());
        } catch (NoninvertibleTransformException e) {
            throw new OperatorException("Could not downsample band image");
        }
    }

    private void setResamplingTypes() {
        interpolationType = getInterpolationType(interpolationMethod);
        aggregationType = getAggregationType(aggregationMethod);
        flagAggregationType = getAggregationType(flagAggregationMethod);
    }

    private InterpolationType getInterpolationType(String interpolationMethod) {
        switch (interpolationMethod) {
            case "NearestNeighbour":
                return InterpolationType.Nearest;
            case "Bilinear":
                return InterpolationType.Bilinear;
        }
        return null;
    }

    private AggregationType getAggregationType(String aggregationMethod) {
        switch (aggregationMethod) {
            case "Mean":
                return AggregationType.Mean;
            case "Median":
                return AggregationType.Median;
            case "Min":
                return AggregationType.Min;
            case "Max":
                return AggregationType.Max;
            case "First":
                return AggregationType.First;
            case "FlagAnd":
                return AggregationType.FlagAnd;
            case "FlagOr":
                return AggregationType.FlagOr;
            case "FlagMedianAnd":
                return AggregationType.FlagMedianAnd;
            case "FlagMedianOr":
                return AggregationType.FlagMedianOr;
        }
        return null;
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(ResamplingOp.class);
        }
    }

}

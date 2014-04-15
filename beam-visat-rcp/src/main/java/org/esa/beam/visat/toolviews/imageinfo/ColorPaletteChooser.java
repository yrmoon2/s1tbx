package org.esa.beam.visat.toolviews.imageinfo;

import org.esa.beam.framework.datamodel.ColorPaletteDef;
import org.esa.beam.framework.datamodel.ImageInfo;
import org.esa.beam.jai.ImageManager;

import javax.swing.ComboBoxModel;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.border.EmptyBorder;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Vector;

class ColorPaletteChooser extends JComboBox<ColorPaletteChooser.ColorPaletteWrapper> {

    private final String USER_DEFINED = "user defined";
    private boolean discreteDisplay;
    private boolean log10Display;

    public ColorPaletteChooser() {
        super(getPalettes());
        setRenderer(createPaletteRenderer());
    }

    public void setUserDefinedPalette(ColorPaletteDef userPalette) {
        removeUserDefinedPalette();
        final ColorPaletteWrapper item = new ColorPaletteWrapper(USER_DEFINED, userPalette);
        insertItemAt(item, 0);
        setSelectedIndex(0);
    }

    public void removeUserDefinedPalette() {
        if (USER_DEFINED.equals(getItemAt(0).name)) {
            removeItemAt(0);
        }
    }

    public ColorPaletteDef getSelectedColorPaletteDefinition() {
        final int selectedIndex = getSelectedIndex();
        final ComboBoxModel<ColorPaletteWrapper> model = getModel();
        return model.getElementAt(selectedIndex).cpd;
    }

    public void setSelectedColorPaletteDefinition(ColorPaletteDef cpd) {
        final ComboBoxModel<ColorPaletteWrapper> model = getModel();
        for (int i = 0; i < model.getSize(); i++) {
            if (model.getElementAt(i).cpd == cpd) {
                setSelectedIndex(i);
                return;
            }
        }
        setUserDefinedPalette(cpd);
    }

    private static Vector<ColorPaletteWrapper> getPalettes() {
        final List<ColorPaletteDef> defList = ColorPalettesManager.getColorPaletteDefList();
        final Vector<ColorPaletteWrapper> cpws = new Vector<>();
        for (ColorPaletteDef colorPaletteDef : defList) {
            final String nameFor = getNameForWithoutExtension(colorPaletteDef);
            cpws.add(new ColorPaletteWrapper(nameFor, colorPaletteDef));
        }
        return cpws;
    }

    private static String getNameForWithoutExtension(ColorPaletteDef colorPaletteDef) {
        final String nameFor = ColorPalettesManager.getNameFor(colorPaletteDef);
        if (nameFor.toLowerCase().endsWith(".cpd")) {
            return nameFor.substring(0, nameFor.length() - 4);
        } else {
            return nameFor;
        }
    }

    private ListCellRenderer<ColorPaletteWrapper> createPaletteRenderer() {
        return new ListCellRenderer<ColorPaletteWrapper>() {
            @Override
            public Component getListCellRendererComponent(JList<? extends ColorPaletteWrapper> list, ColorPaletteWrapper value, int index, boolean isSelected, boolean cellHasFocus) {
                final ImageIcon icon = new ImageIcon();
                final BufferedImage image = new BufferedImage(180, 15, BufferedImage.TYPE_INT_RGB);
                drawPalette(image.createGraphics(), value.cpd, new Dimension(image.getWidth(), image.getHeight()));
                icon.setImage(image);
                final JLabel jLabel = new JLabel(value.name);
                jLabel.setIcon(icon);
                jLabel.setBorder(new EmptyBorder(1, 1, 1, 1));
                return jLabel;
            }
        };
    }

    private void drawPalette(Graphics2D g2, ColorPaletteDef colorPaletteDef, Dimension paletteDim) {
        final int width = paletteDim.width;
        final int height = paletteDim.height;

        final ColorPaletteDef cpdCopy = colorPaletteDef.createDeepCopy();
        cpdCopy.setDiscrete(discreteDisplay);
        cpdCopy.setNumColors(width);
        final ImageInfo imageInfo = new ImageInfo(cpdCopy);
        imageInfo.setLogScaled(log10Display);

        Color[] colorPalette = ImageManager.createColorPalette(imageInfo);

        g2.setStroke(new BasicStroke(1.0f));

        for (int x = 0; x < width; x++) {
            g2.setColor(colorPalette[x]);
            g2.drawLine(x, 0, x, height);
        }
    }

    public void setLog10Display(boolean log10Display) {
        this.log10Display = log10Display;
        repaint();
    }

    public void setDiscreteDisplay(boolean discreteDisplay) {
        this.discreteDisplay = discreteDisplay;
        repaint();
    }

    public static final class ColorPaletteWrapper {

        public final String name;

        public final ColorPaletteDef cpd;

        private ColorPaletteWrapper(String name, ColorPaletteDef cpd) {
            this.name = name;
            this.cpd = cpd;
        }
    }
}

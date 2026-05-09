package com.e.demo.wsi;

import loci.common.services.ServiceFactory;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.gui.BufferedImageReader;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import ome.units.UNITS;
import ome.units.quantity.Length;

import java.awt.image.BufferedImage;

public class BioFormatsWsiReader implements AutoCloseable {

  private final IFormatReader reader;
  private final BufferedImageReader biReader;
  private final IMetadata meta;

  // Дефолтный MPP, если в файле не нашли — 0.25 µm/px (типичный x40 сканер)
  public static final double DEFAULT_MPP = 0.25;

  public BioFormatsWsiReader(String path) throws Exception {
    this.reader = new ImageReader();

    ServiceFactory factory = new ServiceFactory();
    OMEXMLService service = factory.getInstance(OMEXMLService.class);
    this.meta = service.createOMEXMLMetadata();
    reader.setMetadataStore(meta);

    reader.setId(path);
    reader.setSeries(0);
    reader.setResolution(0);

    this.biReader = new BufferedImageReader(reader);
  }

  public int width()  { return reader.getSizeX(); }
  public int height() { return reader.getSizeY(); }

  /** MPP по X в микрометрах/пиксель из OME-метаданных, либо null. */
  public Double mppX() {
    Length l = meta.getPixelsPhysicalSizeX(0);
    if (l == null || l.value() == null) return null;
    return l.value(UNITS.MICROMETER).doubleValue();
  }

  /** MPP по Y. */
  public Double mppY() {
    Length l = meta.getPixelsPhysicalSizeY(0);
    if (l == null || l.value() == null) return null;
    return l.value(UNITS.MICROMETER).doubleValue();
  }

  public BufferedImage readRegion(int x, int y, int w, int h) throws Exception {
    return biReader.openImage(0, x, y, w, h);
  }

  @Override
  public void close() throws Exception {
    reader.close();
  }
}

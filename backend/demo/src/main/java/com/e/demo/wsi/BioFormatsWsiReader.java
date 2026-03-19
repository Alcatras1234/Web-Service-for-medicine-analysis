package com.e.demo.wsi;

import loci.formats.ImageReader;
import loci.formats.IFormatReader;
import loci.formats.gui.BufferedImageReader;

import java.awt.image.BufferedImage;

public class BioFormatsWsiReader implements AutoCloseable {

  private final IFormatReader reader;
  private final BufferedImageReader biReader;

  public BioFormatsWsiReader(String path) throws Exception {
    this.reader = new ImageReader();
    reader.setId(path);
    reader.setSeries(0);
    reader.setResolution(0);

    this.biReader = new BufferedImageReader(reader);
  }

  public int width()  { return reader.getSizeX(); }
  public int height() { return reader.getSizeY(); }

  public BufferedImage readRegion(int x, int y, int w, int h) throws Exception {
    int imageIndex = 0;
    // BufferedImageReader умеет читать регион и вернуть BufferedImage
    return biReader.openImage(imageIndex, x, y, w, h);
  }

  @Override
  public void close() throws Exception {
    reader.close();
  }
}

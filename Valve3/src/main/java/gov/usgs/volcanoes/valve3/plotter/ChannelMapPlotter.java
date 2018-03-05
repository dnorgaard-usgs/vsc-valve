package gov.usgs.volcanoes.valve3.plotter;

import gov.usgs.volcanoes.core.legacy.plot.PlotException;
import gov.usgs.volcanoes.core.legacy.plot.map.GeoImageSet;
import gov.usgs.volcanoes.core.legacy.plot.map.GeoLabel;
import gov.usgs.volcanoes.core.legacy.plot.map.GeoLabelSet;
import gov.usgs.volcanoes.core.legacy.plot.map.MapRenderer;
import gov.usgs.volcanoes.core.math.proj.GeoRange;
import gov.usgs.volcanoes.core.math.proj.TransverseMercator;
import gov.usgs.volcanoes.core.legacy.util.Pool;
import gov.usgs.volcanoes.core.util.UtilException;
import gov.usgs.volcanoes.valve3.PlotComponent;
import gov.usgs.volcanoes.valve3.Plotter;
import gov.usgs.volcanoes.valve3.Valve3;
import gov.usgs.volcanoes.valve3.Valve3Exception;
import gov.usgs.volcanoes.valve3.result.Valve3Plot;
import gov.usgs.volcanoes.vdx.client.VDXClient;
import gov.usgs.volcanoes.vdx.data.Channel;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.image.RenderedImage;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generate PNG map image to local file from vdx source data.
 *
 * @author Dan Cervelli
 * @author Bill Tollett
 */
public class ChannelMapPlotter extends Plotter {

  private GeoRange range;
  private GeoLabelSet labels;
  private String selectedChannels;

  protected boolean tickMarksX  = true;
  protected boolean tickValuesX = true;
  protected boolean unitsX      = true;
  protected boolean labelX      = false;
  protected boolean tickMarksY  = true;
  protected boolean tickValuesY = true;
  protected boolean unitsY      = true;
  protected boolean labelY      = false;

  protected Logger logger;

  /**
   * Default constructor.
   */
  public ChannelMapPlotter() {
    logger = LoggerFactory.getLogger(ChannelMapPlotter.class);
  }

  /**
   * Initialize internal data from PlotComponent.
   */
  private void getInputs(PlotComponent comp) throws Valve3Exception {

    double w = comp.getDouble("west");
    if (w > 360 || w < -360) {
      throw new Valve3Exception("Illegal area of interest: w=" + w);
    }
    double e = comp.getDouble("east");
    if (e > 360 || e < -360) {
      throw new Valve3Exception("Illegal area of interest: e=" + e);
    }
    double s = comp.getDouble("south");
    if (s < -90) {
      throw new Valve3Exception("Illegal area of interest: s=" + s);
    }
    double n = comp.getDouble("north");
    if (n > 90) {
      throw new Valve3Exception("Illegal area of interest: n=" + n);
    }
    if (s >= n) {
      throw new Valve3Exception("Illegal area of interest: s=" + s + ", n=" + n);
    }
    try {
      tickMarksX = comp.getBoolean("xTickMarks");
    } catch (Valve3Exception ex) {
      tickMarksX = true;
    }
    try {
      tickValuesX = comp.getBoolean("xTickValues");
    } catch (Valve3Exception ex) {
      tickValuesX = true;
    }
    try {
      unitsX = comp.getBoolean("xUnits");
    } catch (Valve3Exception ex) {
      unitsX = true;
    }
    try {
      labelX = comp.getBoolean("xLabel");
    } catch (Valve3Exception ex) {
      labelX = false;
    }
    try {
      tickMarksY = comp.getBoolean("yTickMarks");
    } catch (Valve3Exception ex) {
      tickMarksY = true;
    }
    try {
      tickValuesY = comp.getBoolean("yTickValues");
    } catch (Valve3Exception ex) {
      tickValuesY = true;
    }
    try {
      unitsY = comp.getBoolean("yUnits");
    } catch (Valve3Exception ex) {
      unitsY = true;
    }
    try {
      labelY = comp.getBoolean("yLabel");
    } catch (Valve3Exception ex) {
      labelY = false;
    }
    range = new GeoRange(w, e, s, n);
    try {
      selectedChannels = comp.getString("ch");
    } catch (Valve3Exception ex) {
      selectedChannels = "";
    }
  }

  /**
   * Loads GeoLabelSet labels from VDX.
   */
  private void getData(PlotComponent comp) throws Valve3Exception {

    // initialize variables
    List<String> stringList = null;
    VDXClient client        = null;
    labels                  = new GeoLabelSet();

    // create a map of all the input parameters
    Map<String, String> params = new LinkedHashMap<String, String>();
    params.put("source", vdxSource);
    params.put("action", "channels");

    // checkout a connection to the database, the vdxClient could be null or invalid
    Pool<VDXClient> pool = null;
    pool = Valve3.getInstance().getDataHandler().getVDXClient(vdxClient);
    if (pool != null) {
      client = pool.checkout();
      try {
        stringList = client.getTextData(params);
      } catch (UtilException e) {
        stringList = null;
      } finally {
        pool.checkin(client);
      }
    }

    // if data was collected, iterate through the list of channels and add a geo label
    if (stringList != null) {
      Set<String> used  = new HashSet<String>();
      Set<Integer> cids = new HashSet<Integer>();
      for (String cid : selectedChannels.split(",")) {
        cids.add(Integer.parseInt(cid));
      }
      for (String ch : stringList) {
        Channel channel = new Channel(ch);
        int cid         = channel.getCId();

        if (cids.contains(cid)) {
          String channelCode  = channel.getCode();
          String channelCode0 = channelCode.split(" ")[0];
          if (!used.contains(channelCode0)) {
            GeoLabel gl = new GeoLabel(channelCode0, channel.getLon(), channel.getLat());
            labels.add(gl);
            used.add(channelCode0);
          }
        }
      }
    }
  }

  /**
   * Initialize MapRenderer and add it to plot. Generate PNG map image to local file.
   */
  private void plotMap(Valve3Plot v3p, PlotComponent comp) throws Valve3Exception, PlotException {

    TransverseMercator proj = new TransverseMercator();
    Point2D.Double origin   = range.getCenter();
    proj.setup(origin, 0, 0);

    MapRenderer mr = new MapRenderer(range, proj);
    mr.setLocationByMaxBounds(comp.getBoxX(), comp.getBoxY(), comp.getBoxWidth(),
        comp.getBoxMapHeight());
    v3p.getPlot().setSize(v3p.getPlot().getWidth(), mr.getGraphHeight() + 60 + 16);
    v3p.setHeight(mr.getGraphHeight() + 60 + 16);

    if (labels != null) {
      mr.setGeoLabelSet(labels.getSubset(range));
    }

    GeoImageSet images = Valve3.getInstance().getGeoImageSet();
    RenderedImage ri   = images.getMapBackground(proj, range, comp.getBoxWidth());

    mr.setMapImage(ri);
    mr.createBox(8);
    mr.createGraticule(8, tickMarksX, tickMarksY, tickValuesX, tickValuesY, Color.BLACK);
    mr.createScaleRenderer();

    double[] trans = mr.getDefaultTranslation(v3p.getPlot().getHeight());
    trans[4]       = 0;
    trans[5]       = 0;
    trans[6]       = origin.x;
    trans[7]       = origin.y;
    comp.setTranslation(trans);
    comp.setTranslationType("map");

    mr.createEmptyAxis();
    if (unitsX) {
      mr.getAxis().setBottomLabelAsText("Longitude");
    }
    if (unitsY) {
      mr.getAxis().setLeftLabelAsText("Latitude");
    }
    v3p.getPlot().addRenderer(mr);
    writeFile(v3p);

    v3p.addComponent(comp);
    if (vdxSource != null) {
      String n = Valve3.getInstance().getMenuHandler().getItem(vdxSource).name;
      v3p.setTitle("Map: " + n);
    } else {
      v3p.setTitle("Map");
    }
  }

  /**
   * Concrete realization of abstract method. Generate PNG map image to local file.
   *
   * @see Plotter
   */
  public void plot(Valve3Plot v3p, PlotComponent comp) throws Valve3Exception, PlotException {

    forExport = (v3p == null);
    comp.setPlotter(this.getClass().getName());
    getInputs(comp);

    // plot configuration, channel maps don't support data export
    if (!forExport) {
      v3p.setExportable(false);
      v3p.setCombineable(false);
    }

    getData(comp);
    plotMap(v3p, comp);
  }
}

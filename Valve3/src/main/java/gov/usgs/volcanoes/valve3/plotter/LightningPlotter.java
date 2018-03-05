package gov.usgs.volcanoes.valve3.plotter;

import cern.colt.matrix.DoubleMatrix2D;

import gov.usgs.volcanoes.core.legacy.plot.PlotException;
import gov.usgs.volcanoes.core.legacy.plot.decorate.SmartTick;
import gov.usgs.volcanoes.core.legacy.plot.map.GeoImageSet;
import gov.usgs.volcanoes.core.legacy.plot.map.GeoLabelSet;
import gov.usgs.volcanoes.core.legacy.plot.map.MapRenderer;
import gov.usgs.volcanoes.core.legacy.plot.render.AxisRenderer;
import gov.usgs.volcanoes.core.legacy.plot.render.BasicFrameRenderer;
import gov.usgs.volcanoes.core.legacy.plot.render.InvertedFrameRenderer;
import gov.usgs.volcanoes.core.legacy.plot.render.Renderer;
import gov.usgs.volcanoes.core.legacy.plot.render.ShapeRenderer;
import gov.usgs.volcanoes.core.legacy.plot.transform.ArbDepthCalculator;
import gov.usgs.volcanoes.core.math.proj.GeoRange;
import gov.usgs.volcanoes.core.math.proj.TransverseMercator;
import gov.usgs.volcanoes.core.legacy.util.Pool;
import gov.usgs.volcanoes.core.time.J2kSec;
import gov.usgs.volcanoes.core.util.StringUtils;
import gov.usgs.volcanoes.core.util.UtilException;
import gov.usgs.volcanoes.valve3.PlotComponent;
import gov.usgs.volcanoes.valve3.Plotter;
import gov.usgs.volcanoes.valve3.Valve3;
import gov.usgs.volcanoes.valve3.Valve3Exception;
import gov.usgs.volcanoes.valve3.result.Valve3Plot;
import gov.usgs.volcanoes.vdx.client.VDXClient;
import gov.usgs.volcanoes.vdx.data.ExportData;
import gov.usgs.volcanoes.vdx.data.HistogramExporter;
import gov.usgs.volcanoes.vdx.data.MatrixExporter;
import gov.usgs.volcanoes.vdx.data.Rank;
import gov.usgs.volcanoes.vdx.data.lightning.LightningExporter;
import gov.usgs.volcanoes.vdx.data.lightning.LightningRenderer;
import gov.usgs.volcanoes.vdx.data.lightning.LightningRenderer.AxesOption;
import gov.usgs.volcanoes.vdx.data.lightning.LightningRenderer.ColorOption;
import gov.usgs.volcanoes.vdx.data.lightning.Stroke;
import gov.usgs.volcanoes.vdx.data.lightning.StrokeList;
import gov.usgs.volcanoes.vdx.data.lightning.StrokeList.BinSize;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.image.RenderedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A class for making lightning map plots and histograms.
 *
 * Modeled after HypocenterPlotter.
 *
 * @author Tom Parker
 */
public class LightningPlotter extends RawDataPlotter {

  private enum PlotType {
    MAP, STROKES;

    public static PlotType fromString(String s) {
      if (s.equals("map")) {
        return MAP;
      } else if (s.equals("cnts")) {
        return STROKES;
      } else {
        return null;
      }
    }
  }

  private enum RightAxis {
    NONE(""), CUM_STROKES("Cumulative Strikes");

    private String description;

    private RightAxis(String s) {
      description = s;
    }

    public String toString() {
      return description;
    }

    public static RightAxis fromString(String s) {
      switch (s.charAt(0)) {
        case 'N':
          return NONE;
        case 'S':
          return CUM_STROKES;
        default:
          return null;
      }
    }
  }

  private static final double DEFAULT_WIDTH = 100.0;

  // the width is for the Arbitrary line vs depth plot (SBH)
  private double hypowidth;
  private GeoRange range;
  private Point2D startLoc;
  private Point2D endLoc;
  private boolean doLog;

  private AxesOption axesOption;
  private ColorOption colorOption;
  private PlotType plotType;
  private BinSize bin;
  private RightAxis rightAxis;
  private StrokeList strokes;

  /**
   * Default constructor.
   */
  public LightningPlotter() {
    super();
  }

  /**
   * Initialize internal data from PlotComponent component.
   *
   * @param comp PlotComponent
   */
  protected void getInputs(PlotComponent comp) throws Valve3Exception {

    rk = comp.getInt("rk");

    endTime = comp.getEndTime();
    if (Double.isNaN(endTime)) {
      throw new Valve3Exception("Illegal end time.");
    }

    startTime = comp.getStartTime(endTime);
    if (Double.isNaN(startTime)) {
      throw new Valve3Exception("Illegal start time.");
    }

    timeOffset = comp.getOffset(startTime);
    timeZoneID = comp.getTimeZone().getID();

    String pt = comp.get("plotType");
    if (pt == null) {
      plotType = PlotType.MAP;
    } else {
      plotType = PlotType.fromString(pt);
      if (plotType == null) {
        throw new Valve3Exception("Illegal plot type: " + pt);
      }
    }
    try {
      tickMarksX = comp.getBoolean("xTickMarks");
    } catch (Valve3Exception e) {
      tickMarksX = true;
    }
    try {
      tickValuesX = comp.getBoolean("xTickValues");
    } catch (Valve3Exception e) {
      tickValuesX = true;
    }
    try {
      unitsX = comp.getBoolean("xUnits");
    } catch (Valve3Exception e) {
      unitsX = true;
    }
    try {
      labelX = comp.getBoolean("xLabel");
    } catch (Valve3Exception e) {
      labelX = true;
    }
    try {
      tickMarksY = comp.getBoolean("yTickMarks");
    } catch (Valve3Exception e) {
      tickMarksY = true;
    }
    try {
      tickValuesY = comp.getBoolean("yTickValues");
    } catch (Valve3Exception e) {
      tickValuesY = true;
    }
    try {
      unitsY = comp.getBoolean("yUnits");
    } catch (Valve3Exception e) {
      unitsY = true;
    }
    try {
      labelY = comp.getBoolean("yLabel");
    } catch (Valve3Exception e) {
      labelY = false;
    }
    try {
      isDrawLegend = comp.getBoolean("lg");
    } catch (Valve3Exception e) {
      isDrawLegend = true;
    }

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

    this.startLoc = new Point2D.Double(w, n);
    this.endLoc = new Point2D.Double(e, s);

    if (s >= n) {
      double t = s;
      s = n;
      n = t;
    }

    range = new GeoRange(w, e, s, n);

    hypowidth = StringUtils.stringToDouble(comp.get("hypowidth"), DEFAULT_WIDTH);

    switch (plotType) {

      case MAP:

        // axes defaults to Map View
        axesOption = AxesOption.fromString(StringUtils.stringToString(comp.get("axesOption"), "M"));
        if (axesOption == null) {
          throw new Valve3Exception("Illegal axes type.");
        }

        // color defaults to Auto
        String c = StringUtils.stringToString(comp.get("colorOption"), "A");
        if (c.equals("A")) {
          colorOption = ColorOption.chooseAuto(axesOption);
        } else {
          colorOption = ColorOption.fromString(c);
        }
        if (colorOption == null) {
          throw new Valve3Exception("Illegal color option.");
        }

        break;

      case STROKES:

        // bin size defalts to day
        bin = BinSize.fromString(StringUtils.stringToString(comp.get("cntsBin"), "day"));
        if (bin == null) {
          throw new Valve3Exception("Illegal bin size option.");
        }

        if ((endTime - startTime) / bin.toSeconds() > 10000) {
          throw new Valve3Exception("Bin size too small.");
        }

        // right axis default to cumulative counts
        rightAxis = RightAxis.fromString(StringUtils.stringToString(comp.get("cntsAxis"), "S"));
        if (rightAxis == null) {
          throw new Valve3Exception("Illegal counts axis option. (" + comp.get("cntsAxis") + ")");
        }

        break;
      default:
        break;
    }
    if (comp.get("outputAll") != null) {
      exportAll = comp.getBoolean("outputAll");
    } else {
      exportAll = false;
    }
  }

  /**
   * Gets hypocenter list binary data from VDX.
   *
   * @param comp PlotComponent
   */
  protected void getData(PlotComponent comp) throws Valve3Exception {

    // initialize variables
    boolean exceptionThrown = false;
    String exceptionMsg = "";
    VDXClient client = null;

    double twest = range.getWest();
    double teast = range.getEast();
    double tsouth = range.getSouth();
    double tnorth = range.getNorth();

    // create a map of all the input parameters
    Map<String, String> params = new LinkedHashMap<String, String>();
    params.put("source", vdxSource);
    params.put("action", "data");
    params.put("st", Double.toString(startTime));
    params.put("et", Double.toString(endTime));
    params.put("rk", Integer.toString(rk));
    params.put("west", Double.toString(twest));
    params.put("east", Double.toString(teast));
    params.put("south", Double.toString(tsouth));
    params.put("north", Double.toString(tnorth));
    params.put("outputAll", Boolean.toString(exportAll));

    // checkout a connection to the database
    Pool<VDXClient> pool = null;
    pool = Valve3.getInstance().getDataHandler().getVDXClient(vdxClient);
    if (pool != null) {
      client = pool.checkout();

      // get the data, if nothing is returned then create an empty list
      try {
        strokes = (StrokeList) client.getBinaryData(params);
      } catch (UtilException e) {
        exceptionThrown = true;
        exceptionMsg = e.getMessage();
      } catch (Exception e) {
        strokes = null;
      }

      // we return an empty list if there is no data, because it is valid
      // to have no hypocenters for a time period
      if (strokes != null) {
        strokes.adjustTime(timeOffset);
      } else {
        strokes = new StrokeList();
      }

      // check back in our connection to the database
      pool.checkin(client);
    }

    // if a data limit message exists, then throw exception
    if (exceptionThrown) {
      throw new Valve3Exception(exceptionMsg);
    }
  }

  /**
   * Initialize MapRenderer and add it to given plot.
   *
   * @param v3p Valve3Plot
   * @param comp PlotComponent
   */
  private BasicFrameRenderer plotMapView(Valve3Plot v3p, PlotComponent comp)
      throws Valve3Exception {

    // TODO: make projection variable
    TransverseMercator proj = new TransverseMercator();
    Point2D.Double origin = range.getCenter();
    proj.setup(origin, 0, 0);

    strokes.project(proj);

    MapRenderer mr = new MapRenderer(range, proj);
    mr.setLocationByMaxBounds(comp.getBoxX(), comp.getBoxY(), comp.getBoxWidth(),
        comp.getBoxMapHeight());
    v3p.getPlot().setSize(v3p.getPlot().getWidth(), mr.getGraphHeight() + 190);

    GeoLabelSet labels = Valve3.getInstance().getGeoLabelSet();
    labels = labels.getSubset(range);
    mr.setGeoLabelSet(labels);

    GeoImageSet images = Valve3.getInstance().getGeoImageSet();
    RenderedImage ri = images.getMapBackground(proj, range, comp.getBoxWidth());

    mr.setMapImage(ri);
    mr.createBox(8);
    mr.createGraticule(8, tickMarksX, tickMarksY, tickValuesX, tickValuesY, Color.BLACK);

    double[] trans = mr.getDefaultTranslation(v3p.getPlot().getHeight());
    trans[4] = startTime + timeOffset;
    trans[5] = endTime + timeOffset;
    trans[6] = origin.x;
    trans[7] = origin.y;
    comp.setTranslation(trans);
    comp.setTranslationType("map");
    return mr;
  }

  /**
   * Initialize BasicFrameRenderer (init mode depends from axes type) and add it to plot. Generate
   * PNG image to local file.
   *
   * @param v3p Valve3Plot
   * @param comp PlotComponent
   * @param rank Rank
   */
  private void plotMap(Valve3Plot v3p, PlotComponent comp, Rank rank) throws Valve3Exception {

    // default variables
    ArbDepthCalculator adc = null;
    String subCount = "";
    double lat1;
    double lon1;
    double lat2;
    double lon2;
    int count;
    List<Stroke> mystrokes;
    BasicFrameRenderer base = new InvertedFrameRenderer();
    base.setLocation(comp.getBoxX(), comp.getBoxY(), comp.getBoxWidth(), comp.getBoxHeight() - 16);
    v3p.getPlot().setSize(v3p.getPlot().getWidth(), v3p.getPlot().getHeight() + 115);

    switch (axesOption) {

      case MAP_VIEW:
        base = plotMapView(v3p, comp);
        base.createEmptyAxis();
        if (unitsX) {
          base.getAxis().setBottomLabelAsText("Longitude");
        }
        if (unitsY) {
          base.getAxis().setLeftLabelAsText("Latitude");
        }
        ((MapRenderer) base).createScaleRenderer();
        break;
      default:
        break;

    }

    // set the label at the top of the plot.
    if (labelX) {
      base.getAxis().setTopLabelAsText(subCount + getTopLabel(rank));
    }

    // add this plot to the valve plot
    v3p.getPlot().addRenderer(base);

    // Create density overlay if desired
    // create the scale renderer
    LightningRenderer hr = new LightningRenderer(strokes, base, axesOption);
    hr.setColorOption(colorOption);
    if (colorOption == ColorOption.TIME) {
      hr.setColorTime(startTime + timeOffset, endTime + timeOffset);
    }
    if (labelX) {
      hr.createColorScaleRenderer(base.getGraphX() + base.getGraphWidth() / 2 + 150,
          base.getGraphY() + base.getGraphHeight() + 150);
      hr.createStrokeScaleRenderer(base.getGraphX() + base.getGraphWidth() / 2 - 150,
          base.getGraphY() + base.getGraphHeight() + 150);
    }
    v3p.getPlot().addRenderer(hr);
    v3p.addComponent(comp);
  }

  /**
   * If v3Plot is null, prepare data for exporting Otherwise, initialize HistogramRenderer and add
   * it to plot. Generate PNG image to local file.
   *
   * @param v3p Valve3Plot
   * @param comp PlotComponent
   * @param rank Rank
   */
  private void plotCounts(Valve3Plot v3p, PlotComponent comp, Rank rank) throws Valve3Exception {

    int leftLabels = 0;
    HistogramExporter hr = new HistogramExporter(strokes.getCountsHistogram(bin));
    hr.setLocation(comp.getBoxX(), comp.getBoxY(), comp.getBoxWidth(), comp.getBoxHeight() - 16);
    hr.setDefaultExtents();
    hr.setMinX(startTime + timeOffset);
    hr.setMaxX(endTime + timeOffset);
    hr.createDefaultAxis(8, 8, tickMarksX, tickMarksY, false, true, tickValuesX, tickValuesY);
    hr.setXAxisToTime(8, tickMarksX, tickValuesX);
    if (unitsY) {
      hr.getAxis().setLeftLabelAsText("Strokes per " + bin);
    }
    if (unitsX) {
      hr.getAxis().setBottomLabelAsText(
          timeZoneID + " Time (" + J2kSec.toDateString(startTime + timeOffset)
              + " to "
              + J2kSec.toDateString(endTime + timeOffset) + ")");
    }
    if (labelX) {
      hr.getAxis().setTopLabelAsText(getTopLabel(rank));
    }
    if (hr.getAxis().getLeftLabels() != null) {
      leftLabels = hr.getAxis().getLeftLabels().length;
    }
    if (forExport) {
      // Add column headers to csvHdrs (second one incomplete)
      String[] hdr = {null, null, null, String.format("%s_StrokesPer%s", rank.getName(), bin)};
      csvHdrs.add(hdr);
      csvData.add(new ExportData(csvIndex, hr));
      csvIndex++;
    }
    DoubleMatrix2D data = null;
    String headerName = "";
    switch (rightAxis) {
      case CUM_STROKES:
        data = strokes.getCumulativeCounts();
        if (forExport) {
          // Add specialized part of column header to csvText
          headerName = "CumulativeStrokes";
        }
        break;
      default:
        break;
    }
    if (data != null && data.rows() > 0) {
      double cmin = data.get(0, 1);
      double cmax = data.get(data.rows() - 1, 1);

      // TODO: utilize ranks for counts plots
      MatrixExporter mr = new MatrixExporter(data, false, null);
      mr.setAllVisible(true);
      mr.setLocation(comp.getBoxX(), comp.getBoxY(), comp.getBoxWidth(), comp.getBoxHeight() - 16);
      mr.setExtents(startTime + timeOffset, endTime + timeOffset, cmin, cmax * 1.05);
      mr.createDefaultLineRenderers(comp.getColor());

      if (forExport) {
        // Add column to header; add Exporter to set for CSV
        String[] hdr = {null, rank.getName(), null, headerName};
        csvHdrs.add(hdr);
        csvData.add(new ExportData(csvIndex, mr));
        csvIndex++;
      } else {
        Renderer[] r = mr.getLineRenderers();
        ((ShapeRenderer) r[0]).color = Color.red;
        ((ShapeRenderer) r[0]).stroke = new BasicStroke(2.0f);
        AxisRenderer ar = new AxisRenderer(mr);
        if (tickValuesY) {
          ar.createRightTickLabels(SmartTick.autoTick(cmin, cmax, leftLabels, false), null);
        }
        if (unitsY) {
          hr.getAxis().setRightLabelAsText(rightAxis.toString());
        }
        mr.setAxis(ar);
        hr.addRenderer(mr);
      }
    }
    if (isDrawLegend) {
      hr.createDefaultLegendRenderer(new String[]{rank.getName() + " Strokes"});
    }

    if (!forExport) {
      comp.setTranslation(hr.getDefaultTranslation(v3p.getPlot().getHeight()));
      comp.setTranslationType("ty");
      addMetaData(vdxSource, vdxClient, v3p, comp);
      v3p.getPlot().addRenderer(hr);
      v3p.addComponent(comp);
    }
  }

  /**
   * Compute rank, calls appropriate function to init renderers.
   *
   * @param v3p Valve3Plot
   * @param comp PlotComponent
   */
  public void plotData(Valve3Plot v3p, PlotComponent comp, Rank rank) throws Valve3Exception {

    switch (plotType) {
      case MAP:
        if (forExport) {
          // Add column headers to csvHdrs
          String rankName = rank.getName();
          String[] hdr1 = {null, rankName, null, "Lat"};
          csvHdrs.add(hdr1);
          String[] hdr2 = {null, rankName, null, "Lon"};
          csvHdrs.add(hdr2);
          String[] hdr3 = {null, rankName, null, "Stations Detected"};
          csvHdrs.add(hdr3);
          String[] hdr4 = {null, rankName, null, "Residual"};
          csvHdrs.add(hdr4);
          // Initialize data for export; add to set for CSV
          ExportData ed = new ExportData(csvIndex, new LightningExporter(strokes, true));
          csvData.add(ed);
          csvIndex++;
        } else {
          plotMap(v3p, comp, rank);
          v3p.setCombineable(false);
          addMetaData(vdxSource, vdxClient, v3p, comp);
          v3p.setTitle(Valve3.getInstance().getMenuHandler().getItem(vdxSource).name + " Map");
        }
        break;

      case STROKES:
        plotCounts(v3p, comp, rank);
        if (!forExport) {
          v3p.setCombineable(true);
          addMetaData(vdxSource, vdxClient, v3p, comp);
          v3p.setTitle(Valve3.getInstance().getMenuHandler().getItem(vdxSource).name + " Counts");
        }
        break;
      default:
        break;
    }
  }

  /**
   * Concrete realization of abstract method. Generate PNG image (hypocenters map or histogram,
   * depends on plot type) to file with random name. If v3p is null, prepare data for export --
   * assumes csvData, csvData & csvIndex initialized
   *
   * @param v3p Valve3Plot
   * @param comp PlotComponent
   * @see Plotter
   */
  public void plot(Valve3Plot v3p, PlotComponent comp) throws Valve3Exception, PlotException {

    forExport = (v3p == null);
    ranksMap = getRanks(vdxSource, vdxClient);
    comp.setPlotter(this.getClass().getName());
    getInputs(comp);

    // get the rank object for this request
    Rank rank = new Rank();
    if (rk == 0) {
      rank = rank.bestAvailable();
    } else {
      rank = ranksMap.get(rk);
    }

    // plot configuration
    if (!forExport) {
      v3p.setExportable(true);
    }

    /*
     * if (!forExport) { if (rk == 0) { v3p.setExportable(false); } else {
     * v3p.setExportable(true); }
     *
     * // export configuration } else { if (rk == 0) { throw new
     * Valve3Exception(
     * "Data Export Not Available for Best Available Rank"); } }
     */

    // this is a legitimate request so lookup the data from the database and
    // plot it
    getData(comp);
    plotData(v3p, comp, rank);

    if (!forExport) {
      writeFile(v3p);
    }
  }

  /**
   * Generate top label.
   *
   * @return plot top label text
   */
  private String getTopLabel(Rank rank) {

    StringBuilder top = new StringBuilder(100);
    top.append(strokes.size() + " " + rank.getName());

    // data coming from the stroke list have already been adjusted for
    // the time offset
    if (strokes.size() == 1) {
      top.append(" stroke on ");
      top.append(J2kSec.toDateString(strokes.getStrokes().get(0).j2ksec));
    } else {
      top.append(" strokes between ");
      if (strokes.size() == 0) {
        top.append(J2kSec.toDateString(startTime + timeOffset));
        top.append(" and ");
        top.append(J2kSec.toDateString(endTime + timeOffset));
      } else if (strokes.size() > 1) {
        top.append(J2kSec.toDateString(strokes.getStrokes().get(0).j2ksec));
        top.append(" and ");
        top.append(J2kSec.toDateString(strokes.getStrokes().get(strokes.size() - 1).j2ksec));
      }
    }
    top.append(" " + timeZoneID + " Time");
    return top.toString();
  }

  /**
   * Is this a char column.
   *
   * @return "column i contains single-character strings"
   */
  boolean isCharColumn(int i) {
    return (i > 13);
  }

}

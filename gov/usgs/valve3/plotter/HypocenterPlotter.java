package gov.usgs.valve3.plotter;

import gov.usgs.plot.AxisRenderer;
import gov.usgs.plot.BasicFrameRenderer;
import gov.usgs.plot.HistogramRenderer;
import gov.usgs.plot.MatrixRenderer;
import gov.usgs.plot.Plot;
import gov.usgs.plot.Renderer;
import gov.usgs.plot.ShapeRenderer;
import gov.usgs.plot.SmartTick;
import gov.usgs.plot.map.GeoImageSet;
import gov.usgs.plot.map.GeoLabelSet;
import gov.usgs.plot.map.MapRenderer;
import gov.usgs.proj.GeoRange;
import gov.usgs.proj.TransverseMercator;
import gov.usgs.util.Pool;
import gov.usgs.util.Util;
import gov.usgs.valve3.PlotComponent;
import gov.usgs.valve3.Plotter;
import gov.usgs.valve3.Valve3;
import gov.usgs.valve3.Valve3Exception;
import gov.usgs.valve3.result.Valve3Plot;
import gov.usgs.vdx.client.VDXClient;
import gov.usgs.vdx.data.Rank;
import gov.usgs.vdx.data.hypo.HypocenterList;
import gov.usgs.vdx.data.hypo.HypocenterList.BinSize;
import gov.usgs.vdx.data.hypo.plot.HypocenterRenderer;
import gov.usgs.vdx.data.hypo.plot.HypocenterRenderer.Axes;
import gov.usgs.vdx.data.hypo.plot.HypocenterRenderer.ColorOption;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.image.RenderedImage;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import cern.colt.matrix.DoubleMatrix2D;

/**
 * A class for making hypocenter map plots and histograms. 
 * 
 * TODO: display number of hypocenters on plot. 
 * TODO: implement triple view. 
 * TODO: implement arbitrary cross-sections. 
 * 
 * @author Dan Cervelli
 */
public class HypocenterPlotter extends Plotter {
	
	private enum PlotType {
		MAP, COUNTS;
		public static PlotType fromString(String s) {
			if (s.equals("map")) {
				return MAP;
			} else if (s.equals("cnts")) {
				return COUNTS;
			} else {
				return null;
			}
		}
	}

	private enum RightAxis {
		NONE(""), CUM_COUNTS("Cumulative Counts"), CUM_MAGNITUDE("Cumulative Magnitude"), CUM_MOMENT("Cumulative Moment");

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
			case 'C':
				return CUM_COUNTS;
			case 'M':
				return CUM_MAGNITUDE;
			case 'T':
				return CUM_MOMENT;
			default:
				return null;
			}
		}
	}

	private Valve3Plot v3Plot;
	private PlotComponent component;
	private double startTime;
	private double endTime;
	private int rk;
	private GeoRange range;
	private double minDepth, maxDepth;
	private double minMag, maxMag;
	private Integer minNPhases, maxNPhases;
	private double minRMS, maxRMS;
	private double minHerr, maxHerr;
	private double minVerr, maxVerr;
	private String rmk;
	
	private Axes axes;
	private ColorOption color;
	private PlotType plotType;
	private BinSize bin;
	private RightAxis rightAxis;
	private HypocenterList hypos;
	private DateFormat dateFormat;
	private int leftTicks;
	
	public final boolean ranks	= true;
	private static Map<Integer, Rank> ranksMap;
	
	protected Logger logger;

	/**
	 * Default constructor
	 */
	public HypocenterPlotter() {
		dateFormat	= new SimpleDateFormat("yyyy-MM-dd");
		logger		= Logger.getLogger("gov.usgs.vdx");	
	}

	/**
	 * Initialize internal data from PlotComponent component
	 * 
	 * @throws Valve3Exception
	 */
	private void getInputs() throws Valve3Exception {
		
		rk = Util.stringToInt(component.get("rk"));
		if (rk < 0) {
			throw new Valve3Exception("Illegal rank.");
		}
		
		endTime = component.getEndTime();
		if (Double.isNaN(endTime))
			throw new Valve3Exception("Illegal end time.");
		
		startTime = component.getStartTime(endTime);
		if (Double.isNaN(startTime))
			throw new Valve3Exception("Illegal start time.");
		
		plotType	= PlotType.fromString(component.get("plotType"));
		if (plotType == null) {
			throw new Valve3Exception("Illegal plot type.");
		}
		
		double w = Util.stringToDouble(component.get("west"), -999);
		double e = Util.stringToDouble(component.get("east"), -999);
		double s = Util.stringToDouble(component.get("south"), -999);
		double n = Util.stringToDouble(component.get("north"), -999);
		if (s >= n || s < -90 || n > 90 || w > 360 || w < -360 || e > 360 || e < -360) {
			throw new Valve3Exception("Illegal area of interest.");
		} else {		
			range	= new GeoRange(w, e, s, n);
		}
		
		minMag		= Util.stringToDouble(component.get("minMag"), -Double.MAX_VALUE);
		maxMag		= Util.stringToDouble(component.get("maxMag"), Double.MAX_VALUE);
		if (minMag > maxMag)
			throw new Valve3Exception("Illegal magnitude filter.");
		
		minDepth	= Util.stringToDouble(component.get("minDepth"), -Double.MAX_VALUE);
		maxDepth	= Util.stringToDouble(component.get("maxDepth"), Double.MAX_VALUE);
		if (minDepth > maxDepth)
			throw new Valve3Exception("Illegal depth filter.");
		
		minNPhases	= Util.stringToInteger(component.get("minNPhases"), Integer.MIN_VALUE);
		maxNPhases	= Util.stringToInteger(component.get("maxNPhases"), Integer.MAX_VALUE);
		if (minNPhases > maxNPhases)
			throw new Valve3Exception("Illegal nphases filter.");
		
		minRMS		= Util.stringToDouble(component.get("minRMS"), -Double.MAX_VALUE);
		maxRMS		= Util.stringToDouble(component.get("maxRMS"), Double.MAX_VALUE);
		if (minRMS > maxRMS)
			throw new Valve3Exception("Illegal RMS filter.");
		
		minHerr		= Util.stringToDouble(component.get("minHerr"), -Double.MAX_VALUE);
		maxHerr		= Util.stringToDouble(component.get("maxHerr"), Double.MAX_VALUE);
		if (minHerr > maxHerr)
			throw new Valve3Exception("Illegal horizontal error filter.");
		
		minVerr		= Util.stringToDouble(component.get("minVerr"), -Double.MAX_VALUE);
		maxVerr		= Util.stringToDouble(component.get("maxVerr"), Double.MAX_VALUE);
		if (minVerr > maxVerr)
			throw new Valve3Exception("Illegal vertical error filter.");
		
		rmk			= Util.stringToString(component.get("rmk"), "");

		switch (plotType) {
		
		case MAP:			
			axes		= Axes.fromString(component.get("axes"));
			if (axes == null)
				throw new Valve3Exception("Illegal axes type.");

			String c	= Util.stringToString(component.get("color"), "A");
			if (c.equals("A"))
				color	= ColorOption.chooseAuto(axes);
			else
				color	= ColorOption.fromString(c);
			if (color == null)
				throw new Valve3Exception("Illegal color option.");
			
			break;
			
		case COUNTS:
			String bs	= Util.stringToString(component.get("cntsBin"), "day");
			bin			= BinSize.fromString(bs);
			if (bin == null)
				throw new Valve3Exception("Illegal bin size option.");

			if ((endTime - startTime) / bin.toSeconds() > 10000)
				throw new Valve3Exception("Bin size too small.");

			rightAxis	= RightAxis.fromString(component.get("cntsAxis"));
			if (rightAxis == null)
				throw new Valve3Exception("Illegal counts axis option.");
			
			break;
		}
	}

	/**
	 * Gets hypocenter list binary data from VDX
	 * 
	 * @throws Valve3Exception
	 */
	private void getData() throws Valve3Exception {
		
		// create a map of all the input parameters
		Map<String, String> params = new LinkedHashMap<String, String>();
		params.put("source", vdxSource);
		params.put("action", "data");
		params.put("st", Double.toString(startTime));
		params.put("et", Double.toString(endTime));
		params.put("rk", Integer.toString(rk));
		params.put("west", Double.toString(range.getWest()));
		params.put("east", Double.toString(range.getEast()));
		params.put("south", Double.toString(range.getSouth()));
		params.put("north", Double.toString(range.getNorth()));
		params.put("minDepth", Double.toString(-maxDepth));
		params.put("maxDepth", Double.toString(-minDepth));
		params.put("minMag", Double.toString(minMag));
		params.put("maxMag", Double.toString(maxMag));
		params.put("minNPhases", Integer.toString(minNPhases));
		params.put("maxNPhases", Integer.toString(maxNPhases));
		params.put("minRMS", Double.toString(minRMS));
		params.put("maxRMS", Double.toString(maxRMS));
		params.put("minHerr", Double.toString(minHerr));
		params.put("maxHerr", Double.toString(maxHerr));
		params.put("minVerr", Double.toString(minVerr));
		params.put("maxVerr", Double.toString(maxVerr));
		params.put("rmk", (rmk));

		// checkout a connection to the database
		Pool<VDXClient> pool	= Valve3.getInstance().getDataHandler().getVDXClient(vdxClient);
		VDXClient client		= pool.checkout();
		if (client == null)
			return;
		
		double TZOffset = Valve3.getInstance().getTimeZoneOffset() * 60 * 60;
		
		// get the data, if nothing is returned then create an empty list
		hypos = (HypocenterList) client.getBinaryData(params);
		if (hypos == null)
			hypos = new HypocenterList();
		
		// adjust the start and end times
		startTime	+= TZOffset;
		endTime		+= TZOffset;
		
		// check back in our connection to the database
		pool.checkin(client);
	}

	/**
	 * Initialize MapRenderer and add it to given plot 
	 * 
	 * @param plot
	 */
	private BasicFrameRenderer plotMapView(Plot plot) {
		
		// TODO: make projection variable
		TransverseMercator proj = new TransverseMercator();
		Point2D.Double origin = range.getCenter();
		proj.setup(origin, 0, 0);

		hypos.project(proj);

		MapRenderer mr = new MapRenderer(range, proj);
		int mh = Integer.parseInt(component.get("mh"));
		mr.setLocationByMaxBounds(component.getBoxX(), component.getBoxY(), component.getBoxWidth(), mh);

		GeoLabelSet labels = Valve3.getInstance().getGeoLabelSet();
		mr.setGeoLabelSet(labels.getSubset(range));

		GeoImageSet images = Valve3.getInstance().getGeoImageSet();
		RenderedImage ri = images.getMapBackground(proj, range, component.getBoxWidth());

		mr.setMapImage(ri);
		mr.createBox(8);
		mr.createGraticule(8, true);
		plot.setSize(plot.getWidth(), mr.getGraphHeight() + 60);
		double[] trans = mr.getDefaultTranslation(plot.getHeight());
		trans[4] = startTime;
		trans[5] = endTime;
		trans[6] = origin.x;
		trans[7] = origin.y;
		component.setTranslation(trans);
		component.setTranslationType("map");
		return mr;
	}

	/**
	 * 
	 * @return plot top label text
	 */
	private String getTopLabel(Rank rank) {
		StringBuilder top = new StringBuilder(100);
		top.append(hypos.size() + " " + rank.getName());
		if (hypos.size() == 1) {
			top.append(" earthquake on ");
			top.append(dateFormat.format(Util.j2KToDate(hypos.getHypocenters().get(0).j2ksec)));
		} else {
			top.append(" earthquakes");
			if (hypos.size() > 1) {
				top.append(" between ");
				top.append(dateFormat.format(Util.j2KToDate(hypos.getHypocenters().get(0).j2ksec)));
				top.append(" and ");
				top.append(dateFormat.format(Util.j2KToDate(hypos.getHypocenters().get(hypos.size() - 1).j2ksec)));
			}
		}
		return top.toString();
	}

	/**
	 * Initialize BasicFrameRenderer (init mode depends from axes type) and add it to plot.
	 * Generate PNG image to local file.
	 * 
	 */
	private void plotMap(Rank rank) {
		
		BasicFrameRenderer base = new BasicFrameRenderer();
		base.setLocation(component.getBoxX(), component.getBoxY(), component.getBoxWidth(), component.getBoxHeight());
		
		switch (axes) {
		case MAP_VIEW:
			base = plotMapView(v3Plot.getPlot());
			base.createEmptyAxis();
			base.getAxis().setBottomLabelAsText("Longitude");
			base.getAxis().setLeftLabelAsText("Latitude");
			((MapRenderer) base).createScaleRenderer();
			break;
		case LON_DEPTH:
			base.setExtents(range.getWest(), range.getEast(), -maxDepth, -minDepth);
			base.createDefaultAxis();
			component.setTranslation(base.getDefaultTranslation(v3Plot.getPlot().getHeight()));
			component.setTranslationType("xy");
			base.getAxis().setBottomLabelAsText("Longitude");
			base.getAxis().setLeftLabelAsText("Depth (km)");
			break;
		case LAT_DEPTH:
			base.setExtents(range.getSouth(), range.getNorth(), -maxDepth, -minDepth);
			base.createDefaultAxis();
			component.setTranslation(base.getDefaultTranslation(v3Plot.getPlot().getHeight()));
			component.setTranslationType("xy");
			base.getAxis().setBottomLabelAsText("Latitude");
			base.getAxis().setLeftLabelAsText("Depth (km)");
			break;
		case DEPTH_TIME:
			base.setExtents(startTime, endTime, -maxDepth, -minDepth);
			base.createDefaultAxis();
			base.setXAxisToTime(8);
			component.setTranslation(base.getDefaultTranslation(v3Plot.getPlot().getHeight()));
			component.setTranslationType("ty");
			base.getAxis().setBottomLabelAsText("Time");
			base.getAxis().setLeftLabelAsText("Depth (km)");
			break;
		}
		base.getAxis().setTopLabelAsText(getTopLabel(rank));
		v3Plot.getPlot().addRenderer(base);
		
		HypocenterRenderer hr = new HypocenterRenderer(hypos, base, axes);
		hr.setColorOption(color);
		if (color == ColorOption.TIME)
			hr.setColorTime(startTime, endTime);
		hr.createColorScaleRenderer(base.getGraphX() + base.getGraphWidth() + 16, base.getGraphY() + base.getGraphHeight());
		v3Plot.getPlot().addRenderer(hr);

		v3Plot.addComponent(component);
	}

	/**
	 * Initialize HistogramRenderer and add it to plot.
	 * Generate PNG image to local file.
	 */
	private void plotCounts(Rank rank) {

		HistogramRenderer hr = new HistogramRenderer(hypos.getCountsHistogram(bin));
		hr.setLocation(component.getBoxX(), component.getBoxY(), component.getBoxWidth(), component.getBoxHeight());
		hr.setDefaultExtents();
		hr.setMinX(startTime);
		hr.setMaxX(endTime);
		hr.createDefaultAxis(8, 8, false, true);
		hr.setXAxisToTime(8);
		hr.getAxis().setLeftLabelAsText("Earthquakes per " + bin);
		hr.getAxis().setBottomLabelAsText("Time (" + Valve3.getInstance().getTimeZoneAbbr()+ ")");
		hr.getAxis().setTopLabelAsText(getTopLabel(rank));
		leftTicks = hr.getAxis().leftTicks.length;

		DoubleMatrix2D data = null;
		switch (rightAxis) {
		case CUM_COUNTS:
			data = hypos.getCumulativeCounts();
			break;
		case CUM_MAGNITUDE:
			data = hypos.getCumulativeMagnitude();
			break;
		case CUM_MOMENT:
			data = hypos.getCumulativeMoment();
			break;
		}
		if (data != null && data.rows() > 0) {
			
			double cmin = data.get(0, 1);
			double cmax = data.get(data.rows() - 1, 1);	
			
			// TODO: utilize ranks for counts plots
			MatrixRenderer mr = new MatrixRenderer(data, false);
			mr.setAllVisible(true);
			mr.setLocation(component.getBoxX(), component.getBoxY(), component.getBoxWidth(), component.getBoxHeight());
			mr.setExtents(startTime, endTime, cmin, cmax * 1.05);
			mr.createDefaultLineRenderers();
			
			Renderer[] r = mr.getLineRenderers();
			((ShapeRenderer)r[0]).color		= Color.red;
			((ShapeRenderer)r[0]).stroke	= new BasicStroke(2.0f);
			AxisRenderer ar = new AxisRenderer(mr);
			ar.createRightTickLabels(SmartTick.autoTick(cmin, cmax, leftTicks, false), null);
			mr.setAxis(ar);
			
			hr.addRenderer(mr);
			hr.getAxis().setRightLabelAsText(rightAxis.toString());
		}
		
		hr.createDefaultLegendRenderer(new String[] {rank.getName() + " Events"});
		
		component.setTranslation(hr.getDefaultTranslation(v3Plot.getPlot().getHeight()));
		component.setTranslationType("ty");
		v3Plot.getPlot().addRenderer(hr);
		v3Plot.addComponent(component);	
	}
	
	public void plotData() throws Valve3Exception {
		
		// setup the display for the legend
		Rank rank	= new Rank();
		if (rk == 0) {
			rank	= rank.bestPossible();
		} else {
			rank	= ranksMap.get(rk);
		}

		switch (plotType) {
		case MAP:
			plotMap(rank);
			v3Plot.setTitle(Valve3.getInstance().getMenuHandler().getItem(vdxSource).name + " Map");
			break;
		case COUNTS:
			plotCounts(rank);
			v3Plot.setTitle(Valve3.getInstance().getMenuHandler().getItem(vdxSource).name + " Counts");
			break;
		}		
	}

	/**
	 * Concrete realization of abstract method. 
	 * Generate PNG image (hypocenters map or histogram, depends on plot type) to file with random name.
	 * @see Plotter
	 */
	public void plot(Valve3Plot v3p, PlotComponent comp) throws Valve3Exception {
		v3Plot		= v3p;
		component	= comp;
		ranksMap	= getRanks(vdxSource, vdxClient);
		getInputs();
		getData();

		plotData();
				
		Plot plot = v3Plot.getPlot();
		plot.setBackgroundColor(Color.white);
		plot.writePNG(v3Plot.getLocalFilename());
	}

	/**
	 * @return CSV string of binary data described by given PlotComponent
	 */
	public String toCSV(PlotComponent c) throws Valve3Exception {
		component	= c;
		
		getInputs();
		getData();
		
		return hypos.toCSV();
	}
	
	/**
	 * Initialize list of ranks for given vdx source
	 * @param source	vdx source name
	 * @param client	vdx name
	 */
	private static Map<Integer, Rank> getRanks(String source, String client) {
		Map<Integer, Rank> ranks;
		Map<String, String> params = new LinkedHashMap<String, String>();
		params.put("source", source);
		params.put("action", "ranks");
		Pool<VDXClient> pool = Valve3.getInstance().getDataHandler().getVDXClient(client);
		VDXClient cl = pool.checkout();
		List<String> rks = cl.getTextData(params);
		pool.checkin(cl);
		ranks = Rank.fromStringsToMap(rks);
		return ranks;
	}
}

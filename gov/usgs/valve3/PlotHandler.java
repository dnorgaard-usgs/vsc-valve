package gov.usgs.valve3;

import gov.usgs.util.Util;
import gov.usgs.valve3.data.DataHandler;
import gov.usgs.valve3.data.DataSourceDescriptor;
import gov.usgs.valve3.plotter.ChannelMapPlotter;
import gov.usgs.valve3.result.ErrorMessage;
import gov.usgs.valve3.result.Valve3Plot;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

/**
 * A request represents exactly one image plot.

 * $Log: not supported by cvs2svn $
 * Revision 1.4  2005/09/02 22:37:54  dcervelli
 * Support for ChannelMapPlotter.
 *
 * Revision 1.3  2005/08/29 22:52:54  dcervelli
 * Input validation.
 *
 * Revision 1.2  2005/08/28 19:00:20  dcervelli
 * Eliminated warning.  Added support for plotting exceptions and notifying clients about them.
 *
 * Revision 1.1  2005/08/26 20:41:31  dcervelli
 * Initial avosouth commit.
 *
 * @author Dan Cervelli
 */
public class PlotHandler implements HttpHandler
{
	public static final int MAX_PLOT_WIDTH = 6000;
	public static final int MAX_PLOT_HEIGHT = 6000;
	private DataHandler dataHandler;
	
	public PlotHandler(DataHandler dh)
	{
		dataHandler = dh;
	}
	
	protected PlotComponent createComponent(HttpServletRequest request, int i) throws Valve3Exception
	{
		String source = request.getParameter("src." + i);
		if (source == null)
			throw new Valve3Exception("Illegal src value.");
		
		PlotComponent component = new PlotComponent(source);

		// Not using generics because HttpServletRequest is Java 1.4
		Map parameters = request.getParameterMap();
		for (Object k : parameters.keySet())
		{
			String key = (String)k;
			if (key.endsWith("." + i))
			{
				String[] values = (String[])parameters.get(key);
				if (values == null || values.length <= 0)
					continue;
				String value = values[0];
				key = key.substring(0, key.indexOf('.'));
				component.put(key, value);
			}
		}
		return component;
	}
	
	protected List<PlotComponent> parseRequest(HttpServletRequest request) throws Valve3Exception
	{
		int n = Util.stringToInt(request.getParameter("n"), -1);
		if (n == -1)
			throw new Valve3Exception("Illegal n value.");
		
		ArrayList<PlotComponent> list = new ArrayList<PlotComponent>(n);
		
		for (int i = 0; i < n; i++)
		{
			PlotComponent component = createComponent(request, i);
			if (component == null)
				continue;
			int x = Util.stringToInt(request.getParameter("x." + i), 75);
			int y = Util.stringToInt(request.getParameter("y." + i), 19);
			int w = Util.stringToInt(request.getParameter("w." + i), 610);
			int h = Util.stringToInt(request.getParameter("h." + i), 140);
			if (x < 0)
				throw new Valve3Exception("Illegal x value.");
			if (y < 0)
				throw new Valve3Exception("Illegal y value.");
			if (w <= 0 || w > MAX_PLOT_WIDTH)
				throw new Valve3Exception("Illegal width.");
			if (h <= 0 || h > MAX_PLOT_HEIGHT)
				throw new Valve3Exception("Illegal height.");
			component.setBoxX(x);
			component.setBoxY(y);
			component.setBoxWidth(w);
			component.setBoxHeight(h);
			list.add(component);
		}
		return list;
	}
	
	public Object handle(HttpServletRequest request)
	{
		try
		{
			List<PlotComponent> components = parseRequest(request);
			if (components == null || components.size() <= 0)
				return null;
			
			Valve3Plot plot = new Valve3Plot(request);
			for (PlotComponent component : components)
			{
				String source = component.getSource();
				Plotter plotter = null;
				if (source.equals("channel_map"))
				{
					DataSourceDescriptor dsd = dataHandler.getDataSourceDescriptor(component.get("subsrc"));
					if (dsd == null)
						throw new Valve3Exception("Unknown data source.");
					
					plotter = new ChannelMapPlotter();
					plotter.setVDXClient(dsd.getVDXClientName());
					plotter.setVDXSource(dsd.getVDXSource());
				}
				else
				{
					plotter = dataHandler.getDataSourceDescriptor(component.getSource()).getPlotter();
				}
				if (plotter != null)
					plotter.plot(plot, component);
			}
			Valve3.getInstance().getResultDeleter().addResult(plot);
			return plot;
		}
		catch (Valve3Exception e)
		{
			return new ErrorMessage(e.getMessage());
		}
	}
	
	public static String getRandomFilename()
	{
		return "img" + File.separator + "tmp" + Math.round(Math.random() * 100000) + ".png"; 
	}
}

package gov.usgs.volcanoes.valve3.data;

import gov.usgs.volcanoes.core.configfile.ConfigFile;
import gov.usgs.volcanoes.core.legacy.util.Pool;
import gov.usgs.volcanoes.core.util.StringUtils;
import gov.usgs.volcanoes.core.util.UtilException;
import gov.usgs.volcanoes.valve3.HttpHandler;
import gov.usgs.volcanoes.valve3.Valve3;
import gov.usgs.volcanoes.valve3.Valve3Exception;
import gov.usgs.volcanoes.valve3.result.ErrorMessage;
import gov.usgs.volcanoes.valve3.result.GenericMenu;
import gov.usgs.volcanoes.valve3.result.ewRsamMenu;
import gov.usgs.volcanoes.vdx.client.VDXClient;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Constructs and keeps internal data representation described in the configuration file
 *
 * @author Dan Cervelli
 */
public class DataHandler implements HttpHandler
{
	private static final String CONFIG_FILE = "data.config";
	private static final int DEFAULT_VDX_CLIENT_TIMEOUT = 60000;
	private static final Logger LOGGER = LoggerFactory.getLogger(DataHandler.class);
	protected Map<String, DataSourceDescriptor> dataSources;
	protected Map<String, Pool<VDXClient>> vdxClients;
	protected ConfigFile config;
	
	/**
	 * Default constructor
	 */
	public DataHandler()
	{
		dataSources = new HashMap<String, DataSourceDescriptor>();
		vdxClients = new HashMap<String, Pool<VDXClient>>();
		processConfigFile();
	}
	
	/**
	 * Process valve config file to initialize this object, method is used in constructor.
	 */
	public void processConfigFile()
	{
		config = new ConfigFile(Valve3.getInstance().getConfigPath() + File.separator + CONFIG_FILE);
		
		List<String> vdxs = config.getList("vdx");
		for (String vdx : vdxs)
		{
			LOGGER.info("VDX: {}", vdx);
			ConfigFile sub = config.getSubConfig(vdx);
			int num = StringUtils.stringToInt(sub.getString("clients"), 4);
			Pool<VDXClient> pool = new Pool<VDXClient>();
			for (int i = 0; i < num; i++)
			{
				VDXClient client = new VDXClient(sub.getString("host"), Integer.parseInt(sub.getString("port")));
				int timeout = StringUtils.stringToInt(sub.getString("timeout"), DEFAULT_VDX_CLIENT_TIMEOUT);
				client.setTimeout(timeout);
				pool.checkin(client);
			}
			vdxClients.put(vdx, pool);
		}
		
		List<String> sources = config.getList("source");
		for (String source : sources)
		{
			LOGGER.info("Data source: {}", source);
			ConfigFile sub = config.getSubConfig(source);
			DataSourceDescriptor dsd = new DataSourceDescriptor(source, sub.getString("vdx"), sub.getString("vdx.source"), sub.getString("plotter"), sub);
			dataSources.put(source, dsd);
		}
	}

	/**
	 * Getter for config file
	 * @return config file
	 */
	public ConfigFile getConfig()
	{
		return config;
	}
	
	/**
	 * Yield VDXClient pool
	 * @param key vdx parameter string in config file
	 * @return Pool of initialized VDXClients configured in data.config file
	 */
	public Pool<VDXClient> getVDXClient(String key)
	{
		return vdxClients.get(key);
	}
	
	/**
	 * Yield named data source descriptor
	 * @param key data source name ("source" parameter in data.config file)
	 * @return initialized DataSourceDescriptor corresponding given name
	 */
	public DataSourceDescriptor getDataSourceDescriptor(String key)
	{
		return dataSources.get(key);
	}
	
	/**
	 * Yield list of data source descriptors
	 * @return List of descriptors for all configured data sources
	 */
	public List<DataSourceDescriptor> getDataSources()
	{
		List<DataSourceDescriptor> result = new ArrayList<DataSourceDescriptor>();
		result.addAll(dataSources.values());
		return result;
	}
	
	/**
	 * Implements HttpHandler.handle(). Computes data 
	 * source and action from request, send query to server 
	 * and construct appropriate returning object. 
	 * @param request servlet request
	 * @return returning type depends from data action parameter 
	 * in the request - GenericMenu, ewRsamMenu or list of results.
	 */
	public Object handle(HttpServletRequest request) {
		try {
			String source = request.getParameter("src");
			DataSourceDescriptor dsd = dataSources.get(source);
			if (dsd == null)
				return null;  // TODO: throw Valve3Exception
		
			String action = request.getParameter("da");
			if (action == null)
				return null;  // TODO: throw Valve3Exception		
			
			Map<String, String> params	= new HashMap<String, String>();
			params.put("source", dsd.getVDXSource());
			params.put("action", action);
			
			Pool<VDXClient> pool	= Valve3.getInstance().getDataHandler().getVDXClient(dsd.getVDXClientName());
			
			
			if (pool == null)
				throw new Valve3Exception("Unknown VDX server " + dsd.getVDXClientName() + " check .vdx line in data.config");

			VDXClient client		= pool.checkout();
			
			if (client != null) {
				List<String> ls	= null;
				if (action.equals("metadata") || action.equals("suppdata")) {
					// Add the parameters needed for meta or supp data
					// Also validate for required and duplicated parameters
					String arg;
					LOGGER.info("Processing {}", action);
					char m_kind[] = {'?','!','?','?','x','x','x','x'};
					char s_kind[] = {'?','?','?','?','!','?','?','?'};
					char kind[];
					int req_cnt = 1;
					if ( action.equals("metadata") ) {
						kind = m_kind;
					} else {
						kind = s_kind;
					}
					String args[] = {"byID","ch","col","rk","st","et","tz","type"};
					for ( int i=0; i<8; i++ ) {
						arg = request.getParameter( args[i] );
						if ( arg==null || arg.equals(""))
							continue;
						LOGGER.info("{} = {}", args[i], arg);
						switch ( kind[i] ) {
							case 'x':
								throw new Valve3Exception( "Illegal parameter: " + args[i] );
							case 'r':
								throw new Valve3Exception( "Duplicated paramneter: " + args[i] );
							case '!':
								req_cnt--;
							case '?':
								kind[i] = 'r';
								params.put( args[i], arg );
						}
					}
				}
				try {
					ls	= client.getTextData(params);
				} catch (UtilException e) {
					throw new Valve3Exception(e.getMessage()); 
				} finally {
					pool.checkin(client);
				}
				if (ls != null) {
					if (action.equals("genericMenu")) {
						GenericMenu result = new GenericMenu(ls);
						return result;
					} else if (action.equals("ewRsamMenu")) {
						ewRsamMenu result = new ewRsamMenu(ls);
						return result;
					} else {
						List<String> lsx;
						if (action.equals("suppdata")) {
							lsx = new ArrayList<String>();
							for ( String s: ls ) 
								lsx.add( protectSpecialCharacters(s) );
						} else
							lsx = ls;
						gov.usgs.volcanoes.valve3.result.List result	= new gov.usgs.volcanoes.valve3.result.List(lsx);
						return result;
					}
				}
			}
			// this should not be necessary, as the pool was already checked back in if it wasn't null.
			// pool.checkin(client);
			return null;
		}
		catch (Valve3Exception e)
		{
			return new ErrorMessage(e.getMessage());
		}
	}
	
	/**
	 * Returns the string where all non-ascii and <, &, > are encoded as numeric entities. I.e. "&lt;A &amp; B &gt;"
	 * .... (insert result here). The result is safe to include anywhere in a text field in an XML-string. If there was
	 * no characters to protect, the original string is returned.
	 * 
	 * @param originalUnprotectedString
	 *            original string which may contain characters either reserved in XML or with different representation
	 *            in different encodings (like 8859-1 and UFT-8)
	 * @return properly encoded version of parameter
	 */
	private static String protectSpecialCharacters(String originalUnprotectedString) {
	    if (originalUnprotectedString == null) {
	        return null;
	    }
	    boolean anyCharactersProtected = false;

	    StringBuffer stringBuffer = new StringBuffer();
	    for (int i = 0; i < originalUnprotectedString.length(); i++) {
	        char ch = originalUnprotectedString.charAt(i);

	        boolean controlCharacter = ch < 32;
	        boolean unicodeButNotAscii = ch > 126;
	        boolean characterWithSpecialMeaningInXML = ch == '<' || ch == '&' || ch == '>';

	        if (characterWithSpecialMeaningInXML || unicodeButNotAscii || controlCharacter) {
	            stringBuffer.append("&#" + (int) ch + ";");
	            anyCharactersProtected = true;
	        } else {
	            stringBuffer.append(ch);
	        }
	    }
	    if (anyCharactersProtected == false) {
	        return originalUnprotectedString;
	    }

	    return stringBuffer.toString();
	}
}

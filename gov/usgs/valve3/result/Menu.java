package gov.usgs.valve3.result;

import gov.usgs.valve3.Section;
import gov.usgs.valve3.Valve3;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 
 * $Log: not supported by cvs2svn $
 * @author Dan Cervelli
 */
public class Menu extends Result
{
	private List<Section> sections;
	
	public Menu(List<Section> s)
	{
		sections = s;
	}
	
	public String toXML()
	{
		StringBuffer sb = new StringBuffer();
		sb.append("\t<menu>\n");
		sb.append("\t\t<title>" + Valve3.getInstance().getInstallationTitle() + "</title>\n");
		sb.append("\t\t<administrator><![CDATA[" + Valve3.getInstance().getAdministrator() + "]]></administrator>\n");
		sb.append("\t\t<administrator-email><![CDATA[" + Valve3.getInstance().getAdministratorEmail() + "]]></administrator-email>\n");
		sb.append("\t\t<version>" + Valve3.VERSION + ", " + Valve3.BUILD_DATE + "</version>\n");
		sb.append("\t\t<sections>\n");
		
		Collections.sort(sections);
		for (Iterator it = sections.iterator(); it.hasNext(); )
		{
			Section section = (Section)it.next();
			sb.append(section.toXML());
		}
		sb.append("\t\t</sections>\n");
		sb.append("\t</menu>\n");
		return toXML("menu", sb.toString());
	}
}

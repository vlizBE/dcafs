package util.xml;

import org.apache.commons.lang3.SystemUtils;
import org.tinylog.Logger;
import org.w3c.dom.*;
import org.xml.sax.SAXException;
import util.tools.Tools;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class XMLtools {

	private XMLtools() {
		throw new IllegalStateException("Utility class");
	}
	/**
	 * Read and parse an XML file to a Document
	 * 
	 * @param xml The path to the file
	 * @return The Document of the XML
	 */
	public static Document readXML( Path xml ) {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); 
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
		Document doc=null;
		try {
			doc = dbf.newDocumentBuilder().parse(xml.toFile());
			doc.getDocumentElement().normalize();
		} catch (ParserConfigurationException | SAXException | IOException | java.nio.file.InvalidPathException e) {
			Logger.error("Error occurred while reading " + xml.toAbsolutePath().toString(), true);
			Logger.error(e);
		}
		return doc;
	}

	/**
	 * Create an empty xml file and return the Document to fill in
	 * 
	 * @param xmlFile The path to the file to create
	 * @param write    True if the actual file needs to be created already
	 * @return The document
	 */
	public static Document createXML(Path xmlFile, boolean write) {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();		
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); 
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); 

		DocumentBuilder dxml;
		try {
			dxml = dbf.newDocumentBuilder();
			Document doc = dxml.newDocument();
			if (write) {
				writeXML(xmlFile, doc);
			}
			return doc;
		} catch (ParserConfigurationException e) {
			Logger.error("Error occured while creating XML" + xmlFile.getFileName().toString(),true);
			return null;
		}
	}

	/**
	 * Write the content of a Document to an xml file
	 * 
	 * @param xmlFile The file to write to
	 * @param xmlDoc  The content to write in the file
	 * @return True if nothing weird happened
	 */
	public static boolean writeXML(Path xmlFile, Document xmlDoc) {
		if( xmlDoc == null )
			return false;

		try ( var fos = new FileOutputStream(xmlFile.toFile());
			  var writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)
		){
			Source source = new DOMSource(xmlDoc);
			XMLtools.cleanXML(xmlDoc);

			StreamResult result = new StreamResult(writer);
			
			TransformerFactory tFactory = TransformerFactory.newInstance();
			tFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); // Compliant
			tFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, ""); // Compliant
			
			Transformer xformer = tFactory.newTransformer();
			xformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			xformer.setOutputProperty(OutputKeys.INDENT, "yes");
			xformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
			xformer.transform(source, result);
		} catch (Exception e) {
			Logger.error("Failed writing XML: "+xmlFile.toString());
			Logger.error(e);
			return false;
		}
		Logger.info("Written XML: "+xmlFile.toString());
		return true;
	}
	/**
	 * Write the xmldoc to the file it was read from
	 * @param xmlDoc The updated document
	 * @return True if succesful
	 */
	public static boolean updateXML( Document xmlDoc ){
		String file = xmlDoc.getDocumentURI();
		
		if( SystemUtils.IS_OS_LINUX) {
			file=file.replace("file:", "");
		}else{
			file=file.replace("file:/", "");
		}
		file=file.replace("%20", " ");
		return XMLtools.writeXML( Path.of(file) , xmlDoc );//overwrite the file
	}
	/**
	 * Reload the given xmlDoc based on the internal URI
	 * @param xmlDoc The doc to reload
	 * @return The reloaded document
	 */
	public static Document reloadXML( Document xmlDoc ){
		String file = xmlDoc.getDocumentURI();
		if( file == null) {
			Logger.error("The give xmldoc doesn't contain a valid uri");
			return null;
		}
		if( SystemUtils.IS_OS_LINUX) {
			file=file.replace("file:", "");
		}else{
			file=file.replace("file:/", "");
		}
		file=file.replace("%20", " ");
		return XMLtools.readXML(Path.of(file));		
	}
	/* *********************************  S E A R C H I N G *******************************************************/
	/**
	 * Convenience method to a node based on a tag
	 * 
	 * @param xml The Document to check the node for
	 * @param tag The name of the element
	 * @return The element if found, null if not
	 */
	public static Element getFirstElementByTag(Document xml, String tag) {

		if (xml == null) {
			Logger.error("No valid XML provided");
			return null;
		}
		NodeList list = xml.getElementsByTagName(tag);

		if (list == null)
			return null;

		if (list.getLength() > 0) {
			Node nNode = xml.getElementsByTagName(tag).item(0);
			if (nNode == null)
				return null;
			if (nNode.getNodeType() == Node.ELEMENT_NODE) {
				return (Element) nNode;
			}
		}
		Logger.warn("No such tag? " + tag);
		return null;
	}
	/**
	 * Check the given document if it contains a node with the given tag
	 * @param xml The xml doc
	 * @param tag The node tag to look for
	 * @return True if found
	 */
	public static boolean hasElementByTag(Document xml, String tag) {
		return getFirstElementByTag(xml,tag) != null;
	}
	public static boolean hasChildByTag(Element parent, String tag) {
		return getFirstChildByTag(parent, tag)!=null;
	}
	/**
	 * Get an array containing all the elements in the xml with the give tag
	 * @param xml The document to look into
	 * @param tag The tag to look for
	 * @return An array with the result or an empty array
	 */
	public static Element[] getAllElementsByTag(Document xml, String tag) {
		if (xml == null) {
			Logger.error("No valid XML provided");
			return new Element[0];
		}
		NodeList list = xml.getElementsByTagName(tag);

		if (list == null)
			return new Element[0];
		
		var eles = new ArrayList<Element>();
		for( int a=0;a<list.getLength();a++ ){
			Node node = list.item(a);
			if(node==null)
				continue;
			if (list.item(a).getNodeType() == Node.ELEMENT_NODE)
				eles.add((Element)node);
		}
		return eles.toArray(new Element[0]);
	}
	/**
	 * Retrieve the first child from an element with a specific tag
	 * 
	 * @param element The Element to check
	 * @param tag     The name of the node in the element
	 * @return The element if found, null if not
	 */
	public static Element getFirstChildByTag(Element element, String tag) {
		if( element == null ){
			Logger.error("Element is null when looking for "+tag);
			return null;
		}

		NodeList lstNmElmntLst = element.getElementsByTagName(tag);

		if (lstNmElmntLst.getLength() > 0) {
			return (Element) lstNmElmntLst.item(0);
		}
		return null;
	}

	/**
	 * Get the string value of a node from the given element with the given name
	 * 
	 * @param element The element to look in
	 * @param tag     The name of the node
	 * @param def     The value to return if the node wasn't found
	 * @return The requested data or the def value if not found
	 */
	public static String getChildValueByTag(Element element, String tag, String def) {
		if( element == null ){
			Logger.error("Element is null when looking for "+tag);
			return def;
		}
		Element e = getFirstChildByTag(element, tag.toLowerCase());
		if (e == null)
			return def;
		return e.getTextContent();
	}

	/**
	 * Get the integer value of a node from the given element with the given name
	 * 
	 * @param element The element to look in
	 * @param tag     The name of the node
	 * @param def     The value to return if the node wasn't found
	 * @return The requested data or the def value if not found
	 */
	public static int getChildIntValueByTag(Element element, String tag, int def) {
		if( element == null ){
			Logger.error("Element is null when looking for "+tag);
			return def;
		}
		Element e = getFirstChildByTag(element, tag);
		if (e == null)
			return def;
		return Tools.parseInt(e.getTextContent(), def);
	}

	/**
	 * Get the double value of a node from the given element with the given name
	 * 
	 * @param element The element to look in
	 * @param tag     The name of the node
	 * @param def     The value to return if the node wasn't found
	 * @return The requested data or the def value if not found
	 */
	public static double getChildDoubleValueByTag(Element element, String tag, double def) {
		if( element == null ){
			Logger.error("Element is null when looking for "+tag);
			return def;
		}
		Element e = getFirstChildByTag(element, tag);
		if (e == null)
			return def;
		return Tools.parseDouble(e.getTextContent(), def);
	}
	/**
	 * 
	 * Get the boolean value of a node from the given element with the given name.
	 * yes,true and 1 -> true
	 * no,false and 0 -> false
	 * 
	 * @param element The element to look in
	 * @param tag     The name of the node
	 * @param def     The value to return if the node wasn't found
	 * @return The requested data or the def value if not found
	 */
	public static boolean getChildBooleanValueByTag( Element element, String tag, boolean def){
		Element e = getFirstChildByTag(element, tag);
		if (e == null)
			return def;
		String val = e.getTextContent().toLowerCase().trim();
		if( val.equals("yes")||val.equals("true")||val.equals("1"))
			return true;
		if( val.equals("no")||val.equals("false")||val.equals("0"))
			return false;
		Logger.error("Invalid text content to convert to boolean: "+val);	
		return def;	
	}
	/**
	 * Get all the child-elements of an element with the given name
	 * 
	 * @param element The element to look in to
	 * @param child   The name of the child-elements to look for
	 * @return An arrayliet with the child-elements or an empty one if none were found
	 */
	public static List<Element> getChildElements(Element element, String child) {
		if( child.isEmpty() )
			return getChildElements(element);
			
		var eles = new ArrayList<Element>();	
		if (element == null)
			return eles;

		NodeList list = element.getElementsByTagName(child);

		if (list.getLength() == 0)
			return eles;

		eles.ensureCapacity(list.getLength());	
		for (int a = 0; a < list.getLength(); a++){
			if (list.item(a).getNodeType() == Node.ELEMENT_NODE)
				eles.add( (Element) list.item(a));
		}
		return eles;
	}
	/**
	 * Get all the childnodes from the given element that are of the type element node
	 * @param element The parent node/element
	 * @return An array containing the child elements
	 */
	public static List<Element> getChildElements(Element element) {
		var eles = new ArrayList<Element>();
		if (element == null)
			return eles;

		NodeList list = element.getChildNodes();
		eles.ensureCapacity(list.getLength());
		for (int a = 0; a < list.getLength(); a++){
			if (list.item(a).getNodeType() == Node.ELEMENT_NODE)
				eles.add( (Element) list.item(a) );
		}
		return eles;
	}
	/* ******************************  E L E M E N T   A T T R I B U T E S *********************************/
	/**
	 * Get the attributes of an element and cast to string, return def if failed
	 * @param parent The element that holds the attribute
	 * @param attribute The tag of the attribute
	 * @param def The value to return if cast/parse fails
	 * @return The content if ok or def if failed
	 */
	public static String getStringAttribute(Element parent, String attribute, String def) {
		if( parent==null){
			Logger.error("Given parent is null while looking for "+attribute);
			return def;
		}
		if( parent.hasAttribute(attribute))
			return parent.getAttribute(attribute);
		return def;
	}
	/**
	 * Get the attributes of an element and cast to integer, return def if failed
	 * @param parent The element that holds the attribute, cant be null
	 * @param attribute The tag of the attribute
	 * @param def The value to return if cast/parse fails
	 * @return The content if ok or def if failed
	 */
	public static int getIntAttribute(Element parent, String attribute, int def) {
		if( parent==null){
			Logger.error("Given parent is null while looking for "+attribute);
			return def;
		}
		if( parent.hasAttribute(attribute))
			return Tools.parseInt(parent.getAttribute(attribute), def);
		return def;
	}
	/**
	 * Get the attributes of an element and cast to double, return def if failed
	 * @param parent The element that holds the attribute
	 * @param attribute The tag of the attribute
	 * @param def The value to return if cast/parse fails
	 * @return The content if ok or def if failed
	 */
	public static double getDoubleAttribute(Element parent, String attribute, double def) {
		if( parent==null){
			Logger.error("Given parent is null while looking for "+attribute);
			return def;
		}

		if( parent.hasAttribute(attribute))
			return Tools.parseDouble(parent.getAttribute(attribute), def);
		return def;
	}
	/**
	 * Get the attributes of an element and convert to boolean, return def if failed
	 * 
	 * @param parent The element that holds the attribute
	 * @param attribute The tag of the attribute
	 * @param def The value to return if parse fails
	 * @return The content if ok or def if failed
	 */
	public static boolean getBooleanAttribute(Element parent, String attribute, boolean def) {
		if( parent==null){
			Logger.error("Given parent is null while looking for "+attribute);
			return false;
		}
		String at = parent.getAttribute(attribute).toLowerCase().trim();
		
		if( at.equals("yes")||at.equals("true")||at.equals("1"))
			return true;
		if( at.equals("no")||at.equals("false")||at.equals("0"))
			return false;
		return def;
	}
	/**************************************************************************************/
	/***********************
	 * E L E M E N T   V A L U E S
	 ***************************/
	/**************************************************************************************/

	/**************************************************************************************/
	/******************************
	 * W R I T I N G
	 *****************************************/
	/**************************************************************************************/
	/**
	 * Remove all children of a node
	 * @param node The node to remove the children off
	 * @return The amound of nodes removed or -1 if invalid node was given
	 */
	public static int removeAllChildren(Node node) {

		if( node ==null){
			Logger.error("Given node is null");
			return -1;
		}
		int count=0;
		while (node.hasChildNodes()){
			node.removeChild(node.getFirstChild());
			count++;
		}
		return count;
	}
	/**
	 * Do a clean of the xml document according to xpathfactory
	 * @param xmlDoc The document to clean
	 * @return The element if no errors occurred or null if so
	 */
	public static boolean cleanXML(Document xmlDoc) {
		XPathFactory xpathFactory = XPathFactory.newInstance();
		// XPath to find empty text nodes.
		XPathExpression xpathExp;
		try {
			xpathExp = xpathFactory.newXPath().compile("//text()[normalize-space(.) = '']");
			NodeList emptyTextNodes = (NodeList) xpathExp.evaluate(xmlDoc, XPathConstants.NODESET);

			// Remove each empty text node from document.
			for (int i = 0; i < emptyTextNodes.getLength(); i++) {
				Node emptyTextNode = emptyTextNodes.item(i);
				emptyTextNode.getParentNode().removeChild(emptyTextNode);
			}
		} catch (XPathExpressionException e) {
			Logger.error(e);
			return false;
		}
		return true;
	}
	/**
	 * Create an empty child node in the given parent
	 * @param xmlDoc The document which the parent belongs to
	 * @param parent The parent node
	 * @param node The name of the child node
	 * @return The created element if succesfull or null if failed
	 */
	public static Element createChildElement( Document xmlDoc, Element parent, String node ){
		
		if( xmlDoc==null || parent == null){
			Logger.error("Given parent or doc is null while looking for "+node);
			return null;
		}

		try{
			return (Element) parent.appendChild( xmlDoc.createElement(node) );
		}catch( DOMException e){
			Logger.error(e);
			return null;
		}
	}

	/**
	 * Create an child node in the given parent with the given text content
	 * @param xmlDoc The document which the parent belongs to
	 * @param parent The parent node
	 * @param node The name of the child node
	 * @return The created node if succesfull or null if failed
	 */
	public static Element createChildTextElement( Document xmlDoc, Element parent, String node, String content ){
		
		if( xmlDoc==null || parent == null){
			Logger.error("Given parent or doc is null while looking for "+node);
			return null;
		}

		try{			
			Element ele = xmlDoc.createElement(node);
			ele.appendChild( xmlDoc.createTextNode(content) );
			parent.appendChild(ele);
			return ele;
		}catch( DOMException e){
			Logger.error(e);
			return null;
		}		
	}
	public static Element createTextElement( Document xmlDoc, String node, String content ){
		
		if( xmlDoc==null ){
			Logger.error("Given doc is null while looking for "+node);
			return null;
		}

		try{
			Element ele = xmlDoc.createElement(node);
			ele.appendChild( xmlDoc.createTextNode(content) );			
			return ele;
		}catch( DOMException e){
			Logger.error(e);
			return null;
		}		
	}

}

package util.xml;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.*;
import org.xml.sax.SAXException;
import util.tools.Tools;

import javax.xml.XMLConstants;
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
import java.io.*;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class XMLtools {

	private XMLtools() {
		throw new IllegalStateException("Utility class");
	}
	/**
	 * Read and parse an XML file to a Document, returning an empty optional on error
	 * 
	 * @param xml The path to the file
	 * @return The Document of the XML
	 */
	public static Optional<Document> readXML( Path xml ) {

		if(Files.notExists(xml)){
			Logger.error("No such file: "+xml);
			return Optional.empty();
		}

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

		try {
			Document doc = dbf.newDocumentBuilder().parse(xml.toFile());
			doc.getDocumentElement().normalize();
			return Optional.of(doc);
		} catch (ParserConfigurationException | SAXException | IOException | java.nio.file.InvalidPathException e) {
			Logger.error("Error occurred while reading " + xml, true);
			Logger.error(e.toString());
			return Optional.empty();
		}
	}
	/**
	 * Create an empty xml file and return the Document to fill in
	 *
	 * @param xmlFile The path to the file to create
	 * @param write True if the actual file needs to be created already
	 * @return The document
	 */
	public static Optional<Document> createXML(Path xmlFile, boolean write) {

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

		try {
			Document doc = dbf.newDocumentBuilder().newDocument();
			doc.getDocumentElement().normalize();
			if (write) {
				writeXML(xmlFile, doc);
			}
			return Optional.of(doc);
		} catch (ParserConfigurationException | java.nio.file.InvalidPathException e){
			Logger.error("Error occurred while creating XML" + xmlFile.getFileName().toString(),true);
			return Optional.empty();
		}
	}
	/**
	 * Does the same as readXML except it returns the error that occurred
	 * @param xml Path to the xml
	 * @return The error message or empty string if none
	 */
	public static String checkXML( Path xml ){

		if(Files.notExists(xml)){
			Logger.error("No such file: "+xml);
			return "No such file: "+xml;
		}

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

		try {
			Document doc = dbf.newDocumentBuilder().parse(xml.toFile());
			doc.getDocumentElement().normalize();
		} catch (ParserConfigurationException | SAXException | IOException | java.nio.file.InvalidPathException e) {
			var error = e.toString().replace("lineNumber: ", "line:").replace("; columnNumber","");

			error = error.substring(error.indexOf(":")+1).replace(": ",":").trim();

			if( error.startsWith("file:")){
				var file = error.substring(6,error.indexOf(";"));
				file = Path.of(file).getFileName().toString();
				error = file+":"+error.substring(error.indexOf(";")+1);
			}
			return error;
		}
		return "";
	}

	/**
	 * Get the xml Document from a resource inside a jar
	 * @param origin The class origin to pinpoint the jar
	 * @param path The path inside the jar
	 * @return The document if found or empty optional is not
	 */
	public static Optional<Document> readResourceXML( Class origin,String path ){

		if( !path.startsWith("/"))
			path = "/"+path;

		InputStream is = origin.getResourceAsStream(path);
		if( is==null){
			Logger.error("File not found "+path);
			return Optional.empty();
		}

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

		try {
			Document doc = dbf.newDocumentBuilder().parse(is);
			doc.getDocumentElement().normalize();
			return Optional.of(doc);
		} catch (ParserConfigurationException | SAXException | IOException | java.nio.file.InvalidPathException e) {
			Logger.error("Error occurred while reading " + path, true);
			Logger.error(e);
			return Optional.empty();
		}
	}
	/**
	 * Write the content of a Document to an xml file
	 *
	 * @param xmlFile The file to write to
	 * @param xmlDoc  The content to write in the file
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
			return true;
		} catch (Exception e) {
			Logger.error("Failed writing XML: "+xmlFile.toString());
			Logger.error(e);
			return false;
		}
	}
	/**
	 * Write the xmldoc to the file it was read from
	 *
	 * @param xmlDoc The updated document
	 */
	public static void updateXML(Document xmlDoc ){
		getDocPath(xmlDoc).ifPresent( d -> XMLtools.writeXML(d,xmlDoc));
	}
	/**
	 * Reload the given xmlDoc based on the internal URI
	 * @param xmlDoc The doc to reload
	 * @return The reloaded document
	 */
	public static Optional<Document> reloadXML( Document xmlDoc ){
		if( xmlDoc.getDocumentURI() == null) {
			Logger.error("The given document doesn't contain a valid URI");
			return Optional.empty();
		}
		return getDocPath(xmlDoc).flatMap( p -> XMLtools.readXML(p));
	}

	/**
	 * Get the parent path of this xml document
	 * @param xmlDoc The document
	 * @return The path of the document
	 */
	public static Optional<Path> getXMLparent(Document xmlDoc){
		if( xmlDoc.getDocumentURI() == null) {
			Logger.error("The given xmldoc doesn't contain a valid uri");
			return Optional.empty();
		}
		return getDocPath(xmlDoc).map(Path::getParent);
	}

	/**
	 * Get the path to the given doc
	 * @param xmlDoc The doc to get the path from
	 * @return An optional of the path
	 */
	public static Optional<Path> getDocPath(Document xmlDoc){
		try {
			return Optional.of(Path.of(new URL(xmlDoc.getDocumentURI()).toURI()));
		} catch (URISyntaxException | MalformedURLException e) {
			Logger.error(e);
			return Optional.empty();
		}
	}
	/* *********************************  S E A R C H I N G *******************************************************/
	/**
	 * Convenience method to a node based on a tag
	 * 
	 * @param xmlDoc The Document to check the node for
	 * @param tag The name of the element
	 * @return An optional containing the element if found, empty if not
	 */
	public static Optional<Element> getFirstElementByTag(Document xmlDoc, String tag) {

		if (xmlDoc == null) {
			Logger.error("No valid XML provided, looking for "+tag);
			return Optional.empty();
		}

		NodeList list = xmlDoc.getElementsByTagName(tag);
		if (list == null || list.getLength()==0 || list.item(0)==null ||list.item(0).getNodeType() != Node.ELEMENT_NODE)
			return Optional.empty();

		return Optional.of( (Element)list.item(0));
	}
	/**
	 * Get an array containing all the elements in the xml with the give tag
	 * @param xmlDoc The document to look into
	 * @param tag The tag to look for
	 * @return An array with the result or an empty array
	 */
	public static Element[] getAllElementsByTag(Document xmlDoc, String tag) {
		if (xmlDoc == null) {
			Logger.error("No valid XML provided while looking for "+tag);
			return new Element[0];
		}
		NodeList list = xmlDoc.getElementsByTagName(tag); // Get a list of all the nodes with that tag

		var eles = new ArrayList<Element>();
		for( int a=0;a<list.getLength();a++ ){
			Node node = list.item(a);
			if(node!=null && node.getNodeType() == Node.ELEMENT_NODE){
				eles.add((Element)node);
			}
		}
		return eles.toArray(new Element[0]);
	}
	/**
	 * Retrieve the first element in a xml doc based on the tag
	 * @param xmlPath The path to the xml
	 * @param tag The tag to log for
	 * @return The optional element
	 */
	public static Optional<Element> getFirstElementByTag(Path xmlPath, String tag) {
		return XMLtools.readXML(xmlPath).flatMap(d -> getFirstElementByTag(d, tag));
	}
	/**
	 * Check the given document if it contains a node with the given tag
	 * @param xml The xml doc
	 * @param tag The node tag to look for
	 * @return True if found
	 */
	public static boolean hasElementByTag(Document xml, String tag) {
		return getFirstElementByTag(xml,tag).isPresent();
	}
	/* ************************************** Child ******************************************************* */
	/**
	 * Retrieve the first child from an element with a specific tag
	 *
	 * @param element The Element to check
	 * @param tag     The name of the node in the element
	 * @return The element if found, null if not
	 */
	public static Optional<Element> getFirstChildByTag(Element element, String tag) {
		if( element == null ){
			Logger.error("Element is null when looking for "+tag);
			return Optional.empty();
		}

		NodeList lstNmElmntLst = element.getElementsByTagName(tag);
		return lstNmElmntLst.getLength() > 0?Optional.of((Element) lstNmElmntLst.item(0)):Optional.empty();
	}
	/**
	 * Check the given element for a child node with a specific tag
	 * @param parent The element to look into
	 * @param tag The tag to look for or * for 'any'
	 * @return True if found
	 */
	public static boolean hasChildByTag(Element parent, String tag) {
		return getFirstChildByTag(parent, tag).isPresent();
	}
	/**
	 * Get the string value of a node from the given element with the given tag, returning a default value if none found
	 *
	 * @param element The element to look in
	 * @param tag     The name of the node
	 * @param def     The value to return if the node wasn't found
	 * @return The requested data or the def value if not found
	 */
	public static String getChildStringValueByTag(Element element, String tag, String def) {
		return getFirstChildByTag(element, tag.toLowerCase()).map(Node::getTextContent).orElse(def);
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
		return getFirstChildByTag(element, tag).map(e-> NumberUtils.toInt(e.getTextContent(),def)).orElse(def);
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
		return getFirstChildByTag(element, tag).map(e-> NumberUtils.toDouble(e.getTextContent(),def)).orElse(def);
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
		// Get the first child with the tag, if found convert the content if any otherwise return def
		return getFirstChildByTag(element, tag).map( child -> Tools.parseBool(child.getTextContent(),def)).orElse(def);
	}
	/**
	 * Get the path value of a node from the given element with the given tag, returning a default value if none found
	 *
	 * @param element The element to look in
	 * @param tag     The name of the node
	 * @param defPath The value to return if the node wasn't found
	 * @return The requested path or an empty optional is something went wrong
	 */
	public static Optional<Path> getChildPathValueByTag(Element element, String tag, String defPath ) {
		var childOpt = getFirstChildByTag(element, tag.toLowerCase());
		if (childOpt.isEmpty() )
			return Optional.empty();

		String p = childOpt.get().getTextContent().replace("/", File.separator); // Make sure to use correct slashes
		p=p.replace("\\",File.separator);
		if( p.isEmpty() )
			return Optional.empty();

		var path = Path.of(p);
		if( path.isAbsolute() || defPath.isEmpty())
			return Optional.of(path);

		return Optional.of( Path.of(defPath).resolve(path) );
	}
	/**
	 * Get all the child-elements of an element with the given name
	 * 
	 * @param element The element to look in to
	 * @param child   The named of the child-elements to look for
	 * @return An arraylist with the child-elements or an empty one if none were found
	 */
	public static List<Element> getChildElements(Element element, String... child) {

		if (element == null)
			return new ArrayList<>();

		if( child.length==1 && (child[0].isEmpty()) )
			child[0]="*";

		var eles = new ArrayList<Element>();
		for( String ch : child ){
			NodeList list = element.getElementsByTagName(ch);

			for (int a = 0; a < list.getLength(); a++){
				if (list.item(a).getNodeType() == Node.ELEMENT_NODE)
					eles.add( (Element) list.item(a));
			}
		}
		eles.trimToSize();
		return eles;
	}
	/**
	 * Get all the childnodes from the given element that are of the type element node
	 * @param element The parent node/element
	 * @return An array containing the child elements
	 */
	public static List<Element> getChildElements(Element element) {
		return getChildElements(element,"*");
	}
	/* ******************************  E L E M E N T   A T T R I B U T E S *********************************/
	/**
	 * Get the attributes of an element and cast to string, return def if failed
	 * @param element The element that might hold the attribute
	 * @param attribute The tag of the attribute
	 * @param def The value to return if cast/parse fails
	 * @return The content if ok or def if failed
	 */
	public static String getStringAttribute(Element element, String attribute, String def) {
		if( element==null){
			Logger.error("Given parent is null while looking for "+attribute);
			return def;
		}
		// If the parent doesn't have the attribute, return the default
		if( !element.hasAttribute(attribute))
			return def;

		var val = element.getAttribute(attribute);
		if( val.isBlank() && !val.isEmpty()) // If the value is whitespace but not empty return it
			return val;
		return val.trim(); //trim spaces around the val

	}
	/**
	 * Get the optional path value of a node from the given element with the given name
	 *
	 * @param element The element to look in
	 * @param attribute The name of the attribute
	 * @param workPath The value to return if the node wasn't found
	 * @return The requested path or an empty optional is something went wrong
	 */
	public static Optional<Path> getPathAttribute(Element element, String attribute, Path workPath ) {
		if( element == null ){
			Logger.error("Parent is null when looking for "+attribute);
			return Optional.empty();
		}
		if( !element.hasAttribute(attribute))
			return Optional.empty();

		String p = element.getAttribute(attribute).trim().replace("/", File.separator); // Make sure to use correct slashes
		p = p .replace("\\",File.separator);
		if( p.isEmpty() )
			return Optional.empty();
		var path = Path.of(p);

		if( path.isAbsolute() || workPath==null)
			return Optional.of(path);
		return Optional.of( workPath.resolve(path) );
	}
	/**
	 * Get the attributes of an element and cast to integer, return def if failed
	 * @param element The element that holds the attribute, cant be null
	 * @param attribute The tag of the attribute
	 * @param def The value to return if cast/parse fails
	 * @return The content if ok or def if failed
	 */
	public static int getIntAttribute(Element element, String attribute, int def) {
		if( element==null){
			Logger.error("Given parent is null while looking for "+attribute);
			return def;
		}
		if( element.hasAttribute(attribute))
			return NumberUtils.toInt(element.getAttribute(attribute), def);
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
			return NumberUtils.toDouble(parent.getAttribute(attribute), def);
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
		return Tools.parseBool(parent.getAttribute(attribute),def);
	}

	/**
	 * Get a list of all the attributes in the given element
	 * @param ele The element to look into
	 * @return A list of arrays containing nodename,value pairs
	 */
	public static ArrayList<String[]> getAttributes(Element ele){
		ArrayList<String[]> list = new ArrayList<>();

		var attrs = ele.getAttributes();
		for( int a=0;a<attrs.getLength();a++){
			String val = attrs.item(a).getNodeValue();
			String att = attrs.item(a).getNodeName();
			list.add(new String[]{att,val});
		}
		return list;
	}
	/* **************************** E L E M E N T   V A L U E S ***************************/
	/* ********************************* W R I T I N G **************************************/
	/**
	 * Remove all children of a node
	 *
	 * @param node The node to remove the children off
	 */
	public static void removeAllChildren(Node node) {

		if( node ==null){
			Logger.error("Given node is null");
			return;
		}
		while (node.hasChildNodes()){
			node.removeChild(node.getFirstChild());
		}
	}
	/**
	 * Do a clean of the xml document according to xpathfactory
	 *
	 * @param xmlDoc The document to clean
	 */
	public static void cleanXML(Document xmlDoc) {
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
		}
	}
	/**
	 * Create an empty child node in the given parent
	 * @param xmlDoc The document which the parent belongs to
	 * @param parent The parent node
	 * @param node The name of the child node
	 * @return The created element if successful or null if failed
	 */
	public static Optional<Element> createChildElement( Document xmlDoc, Element parent, String node ){
		
		if( xmlDoc==null || parent == null){
			Logger.error("Given parent or doc is null while looking for "+node);
			return Optional.empty();
		}

		try{
			return Optional.of((Element) parent.appendChild( xmlDoc.createElement(node) ));
		}catch( DOMException e){
			Logger.error(e);
			return Optional.empty();
		}
	}

	/**
	 * Create an child node in the given parent with the given text content
	 * @param xmlDoc The document which the parent belongs to
	 * @param parent The parent node
	 * @param node The name of the child node
	 * @return The created node if succesfull or null if failed
	 */
	public static Optional<Element> createChildTextElement( Document xmlDoc, Element parent, String node, String content ){
		
		if( xmlDoc==null || parent == null){
			Logger.error("Given parent or doc is null while looking for "+node);
			return Optional.empty();
		}

		try{			
			Element ele = xmlDoc.createElement(node);
			ele.appendChild( xmlDoc.createTextNode(content) );
			parent.appendChild(ele);
			return Optional.of(ele);
		}catch( DOMException e){
			Logger.error(e);
			return Optional.empty();
		}		
	}
	public static Optional<Element> createTextElement( Document xmlDoc, String node, String content ){
		
		if( xmlDoc==null ){
			Logger.error("Given doc is null while looking for "+node);
			return Optional.empty();
		}

		try{
			Element ele = xmlDoc.createElement(node);
			ele.appendChild( xmlDoc.createTextNode(content) );			
			return Optional.of(ele);
		}catch( DOMException e){
			Logger.error(e);
			return Optional.empty();
		}		
	}
}

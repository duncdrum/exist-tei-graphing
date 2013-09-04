package org.exist.xquery.tei.drama;

import java.awt.Dimension;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Formatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.regex.*;

import org.apache.log4j.Logger;

import org.apache.batik.dom.GenericDOMImplementation;
import org.apache.batik.svggen.SVGGeneratorContext;
import org.apache.batik.svggen.SVGGraphics2D;
import org.apache.batik.svggen.SVGGraphics2DIOException;
import org.apache.commons.io.output.ByteArrayOutputStream;

import edu.uci.ics.jung.visualization.VisualizationImageServer;
import edu.uci.ics.jung.visualization.decorators.ToStringLabeller;
import edu.uci.ics.jung.algorithms.layout.FRLayout;
import edu.uci.ics.jung.algorithms.layout.Layout;
import edu.uci.ics.jung.graph.Graph;

import org.exist.Namespaces;
import org.exist.collections.Collection;
import org.exist.dom.BinaryDocument;
import org.exist.dom.DocumentImpl;
import org.exist.dom.QName;
//import org.exist.memtree.DocumentBuilderReceiver;
import org.exist.memtree.MemTreeBuilder;
import org.exist.memtree.NodeImpl;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.lock.Lock;
import org.exist.storage.txn.TransactionManager;
import org.exist.storage.txn.Txn;
import org.exist.util.MimeType;
import org.exist.util.VirtualTempFile;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.*;
import org.exist.xquery.tei.TEIDramaModule;
import org.exist.xquery.tei.drama.jung.JungRelationGraph;
import org.exist.xquery.tei.drama.jung.JungRelationGraphVertex;
import org.exist.xquery.tei.drama.jung.JungRelationGraphEdge;
import org.exist.xquery.value.*;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/** 
 * Creates RelationGraph presentations out of the TEI drama texts.
 *
 * @author ljo
 */
public class Visualization extends BasicFunction {
    private final static Logger LOG = Logger.getLogger(Visualization.class);
    
    public final static FunctionSignature signatures[] = {
        new FunctionSignature(
                              new QName("relation-graph", TEIDramaModule.NAMESPACE_URI, TEIDramaModule.PREFIX),
                              "Serializes a relation graph based on provided persons and relations. All other parameters use default values if empty.",
                              new SequenceType[] {
                                  new FunctionParameterSequenceType("listPersons", Type.ELEMENT, Cardinality.ONE_OR_MORE,
                                                                    "The listPerson elements to create the graph from"),
                                  new FunctionParameterSequenceType("listRelations", Type.ELEMENT, Cardinality.ONE_OR_MORE,
                                                                    "The listRelation elements to create the graph from")
                              },
                              new FunctionReturnSequenceType(Type.NODE, Cardinality.EXACTLY_ONE,
                                                             "The serialized relation graph, by default GraphML, otherwise output-type.")
                              ),
        new FunctionSignature(
                              new QName("relation-graph", TEIDramaModule.NAMESPACE_URI, TEIDramaModule.PREFIX),
                              "Serializes a relation graph. All other parameters use default values if empty.",
                              new SequenceType[] {
                                  new FunctionParameterSequenceType("relation-graph-doc", Type.ANY_URI, Cardinality.EXACTLY_ONE,
                                                                    "The path within the database to the serialized relation graph document to use"),
                                  new FunctionParameterSequenceType("tei-doc", Type.ANY_URI, Cardinality.EXACTLY_ONE,
                                                                    "The path within the database to the tei document to use")
                              },
                              new FunctionReturnSequenceType(Type.NODE, Cardinality.ONE_OR_MORE,
                                                             "The serialized relation graph, by default GraphML, otherwise output-type.")
                              )
    };

    private static File dataDir = null;

    private static String relationGraphSource = null;
    private static RelationGraph cachedRelationGraph = null;

    private static RelationGraph relationGraph;

    private final Map<String, RelationGraph.Vertex> vertexFromSubjectId = new HashMap();

    public Visualization(XQueryContext context, FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
        String relationGraphPath = null;

        boolean storeRelationGraph = true;
        boolean useStoredRelationGraph = isCalledAs("relation-graph-stored")
            && getSignature().getArgumentCount() == 5 ? true : false;

        context.pushDocumentContext();
        ValueSequence result = new ValueSequence();
        try {
            relationGraph = new JungRelationGraph();
            
            if (!args[0].isEmpty()) {
                for (int i = 0; i < args[0].getItemCount(); i++) {
                    LOG.debug("Lägger till listPerson #: " + i);
                    parseListPersons(((NodeValue)args[0].itemAt(i)).getNode());
                }
            }
            LOG.info("Number of Subjects (vertices):" +vertexFromSubjectId.size());
            
            if (!args[1].isEmpty()) {
                for (int i = 0; i < args[1].getItemCount(); i++) {
                    LOG.debug("Lägger till listRelation #: " + i);
                    parseListRelations(((NodeValue)args[1].itemAt(i)).getNode());
                }
            }
            LOG.info("Number of Relations (edges):" +relationGraph.edgeCount());
                        

            RelationGraphSerializer rgs = new RelationGraphSerializer(context, relationGraph);
            return rgs.relationGraphReport("svg");
        } finally {
            context.popDocumentContext();
        }
    }

    /* private void cleanCaches() {
       cachedVariantGraph = null;
       } */

    /**
     * The method <code>readRelationGraph</code>
     *
     * @param context a <code>XQueryContext</code> value
     * @param relationGraphPath a <code>String</code> value
     * @return an <code>RelationGraph</code> value
     * @exception XPathException if an error occurs
     */
    /* public static RelationGraph readRelationGraph(XQueryContext context, final String variantGraphPath) throws XPathException {
       try {
       if (relationGraphSource == null || !relationGraphPath.equals(relationGraphSource)) {
       relationGraphSource = relationGraphPath;
       DocumentImpl doc = (DocumentImpl) context.getBroker().getXMLResource(XmldbURI.createInternal(relationGraphPath));
       if (doc.getResourceType() != DocumentImpl.BINARY_FILE) {
       throw new XPathException("RelationGraph path does not point to a binary resource");
       }
       BinaryDocument binaryDocument = (BinaryDocument)doc;
       File relationGraphFile = context.getBroker().getBinaryFile(binaryDocument);
       if (dataDir == null) {
       dataDir = relationGraphFile.getParentFile();
       }
       cachedRelationGraph = RelationGraph.read(relationGraphFile);
       }
       } catch (PermissionDeniedException e) {
       throw new XPathException("Permission denied to read relation graph resource", e);
       } catch (IOException e) {
       throw new XPathException("Error while reading relation graph resource: " + e.getMessage(), e);
       }
       return cachedRelationGraph;
       } */

    public void parseListPersons(Node listPerson) throws XPathException {
        String type = "unknown";
        String persId = "unknown";
        String persName = "unknown";
        String sex = "unknown";
        String age = "unknown";
        String occupation = "unknown";
        
        if (listPerson.getNodeType() == Node.ELEMENT_NODE && listPerson.getLocalName().equals("listPerson") && listPerson.getNamespaceURI().equals(RelationGraphSerializer.TEI_NS)) {
            NamedNodeMap attrs = listPerson.getAttributes();
            if (attrs.getLength() > 0) {
                type = attrs.getNamedItem("type").getNodeValue();
            }
            //Get the listPerson children
            Node child = listPerson.getFirstChild();
            while (child != null) {
                //Parse each of the child nodes person/listPerson
                if (child.getNodeType() == Node.ELEMENT_NODE && child.hasChildNodes()) {
                    if (child.getLocalName().equals("person") &&
                        child.getNamespaceURI().equals(RelationGraphSerializer.TEI_NS)) {
                        
                        parsePersons(child, type);

                    } else if (child.getLocalName().equals("listPerson") &&
                               child.getNamespaceURI().equals(RelationGraphSerializer.TEI_NS)) {
                        parseListPersonGroup(child, type);
                    }
                }
                //next person/listPerson node
                child = child.getNextSibling();
            }
        }
    }

    public void parsePersons(Node child, String type) throws XPathException {
        String persId = "unknown";
        String persName = "unknown";
        String sex = "unknown";
        String age = "unknown";
        String occupation = "unknown";
        NamedNodeMap persAttrs = child.getAttributes();
        if (persAttrs.getLength() > 0) {
            try {
                persId = persAttrs.getNamedItemNS(Namespaces.XML_NS, "id").getNodeValue();
            } catch (NullPointerException e1) {
                try {
                    persId = persAttrs.getNamedItem("sameAs").getNodeValue().substring(1);
                } catch (NullPointerException e2) {
                    LOG.error("Element personGrp is missing xml:id-attribute and  has no sameAs-attribute.");
                }
            }
            if (persAttrs.getNamedItem("sex") != null && !"".equals(persAttrs.getNamedItem("sex").getNodeValue())) {
                sex = persAttrs.getNamedItem("sex").getNodeValue();
            }
        }
        //Get the person child nodes
        Node personChild = child.getFirstChild();
        while (personChild != null) {
            //Parse each of the personChild nodes
            if (personChild.getNodeType() == Node.ELEMENT_NODE && personChild.hasChildNodes()) {
                //parsePersonChildren(personChild, persName, sex, age, occupation);
                if (personChild.getLocalName().equals("persName")) {
            
                    String value = personChild.getFirstChild().getNodeValue();
                    if (value == null) {
                        throw new XPathException("Value for 'persName' cannot be parsed");
                    } else {
                        persName = value;
                    }
                } else if (personChild.getLocalName().equals("occupation")) {
                    String value = personChild.getFirstChild().getNodeValue();
                    if (value == null) {
                        throw new XPathException("Value for 'occupation' cannot be parsed");
                    } else {
                        occupation = value;
                    }
                } else if (personChild.getLocalName().equals("sex")) {
                    String value = personChild.getFirstChild().getNodeValue();
                    if (value == null) {
                        throw new XPathException("Value for 'sex' cannot be parsed");
                    } else {
                        sex = value;
                    }
            
                } else if (personChild.getLocalName().equals("age")) {
                    String value = personChild.getFirstChild().getNodeValue();
                    if (value == null) {
                        throw new XPathException("Value for 'age' cannot be parsed");
                    } else {
                        age = value;
                    }
                }                            
            }
            //next personChild node
            personChild = personChild.getNextSibling();    
        }
        LOG.info("parsePersons::" + persId +":"+ persName +":"+ type +":"+ sex +":"+ age +":"+ occupation);
        vertexFromSubjectId.put(persId, relationGraph.add(new PersonSubject(persId, persName, type, sex, age, occupation)));
                    
    }

    public void parsePersonChildren(Node personChild, String persName, String sex, String age, String occupation) throws XPathException {
        if (personChild.getLocalName().equals("persName")) {
            
            String value = personChild.getFirstChild().getNodeValue();
            if (value == null) {
                throw new XPathException("Value for 'persName' cannot be parsed");
            } else {
                persName = value;
            }
        } else if (personChild.getLocalName().equals("occupation")) {
            String value = personChild.getFirstChild().getNodeValue();
            if (value == null) {
                throw new XPathException("Value for 'occupation' cannot be parsed");
            } else {
                occupation = value;
            }
        } else if (personChild.getLocalName().equals("sex")) {
            String value = personChild.getFirstChild().getNodeValue();
            if (value == null) {
                throw new XPathException("Value for 'sex' cannot be parsed");
            } else {
                sex = value;
            }
            
        } else if (personChild.getLocalName().equals("age")) {
            String value = personChild.getFirstChild().getNodeValue();
            if (value == null) {
                throw new XPathException("Value for 'age' cannot be parsed");
            } else {
                age = value;
            }
        }                            
    }

    public void parseListPersonGroup(Node child, String type) throws XPathException {
        //Get the listPerson/listPerson child nodes
        Node listPersonChild = child.getFirstChild();
        while (listPersonChild != null) {
            String persId = "unknown";
            String persName = "unknown";
            String sex = "unknown";
            String age = "unknown";
            String occupation = "unknown";

            //Parse each of the listPersonChild nodes
            if (listPersonChild.getNodeType() == Node.ELEMENT_NODE && listPersonChild.hasChildNodes()) {
                if (listPersonChild.getLocalName().equals("personGrp") &&
                    listPersonChild.getNamespaceURI().equals(RelationGraphSerializer.TEI_NS)) {
                    LOG.info("listPerson/listPerson/personGrp");
                    NamedNodeMap persGrpAttrs = listPersonChild.getAttributes();
                    if (persGrpAttrs.getLength() > 0) {
                        try {
                            persId = persGrpAttrs.getNamedItemNS(Namespaces.XML_NS, "id").getNodeValue();
                        } catch (NullPointerException e) {
                            try {
                                persId = persGrpAttrs.getNamedItem("sameAs").getNodeValue().substring(1);
                            } catch (NullPointerException e0) {
                                LOG.error("Element personGrp is missing xml:id-attribute and  has no sameAs-attribute.");
                            }
                        }

                        if (persGrpAttrs.getNamedItem("sex") != null && !"".equals(persGrpAttrs.getNamedItem("sex").getNodeValue())) {
                            sex = persGrpAttrs.getNamedItem("sex").getNodeValue();
                        }
                    }

                    Node grpChild = listPersonChild.getFirstChild();
                    while (grpChild != null) {
                        //parsePersonChildren(grpChild, persName, sex, age, occupation);
                        if (grpChild.getLocalName().equals("persName")) {
            
                            String value = grpChild.getFirstChild().getNodeValue();
                            if (value == null) {
                                throw new XPathException("Value for 'persName' cannot be parsed");
                            } else {
                                persName = value;
                            }
                        } else if (grpChild.getLocalName().equals("occupation")) {
                            String value = grpChild.getFirstChild().getNodeValue();
                            if (value == null) {
                                throw new XPathException("Value for 'occupation' cannot be parsed");
                            } else {
                                occupation = value;
                            }
                        } else if (grpChild.getLocalName().equals("sex")) {
                            String value = grpChild.getFirstChild().getNodeValue();
                            if (value == null) {
                                throw new XPathException("Value for 'sex' cannot be parsed");
                            } else {
                                sex = value;
                            }
            
                        } else if (grpChild.getLocalName().equals("age")) {
                            String value = grpChild.getFirstChild().getNodeValue();
                            if (value == null) {
                                throw new XPathException("Value for 'age' cannot be parsed");
                            } else {
                                age = value;
                            }
                        }                            
                        //next person/listPerson node
                        grpChild = grpChild.getNextSibling();
                    }
                    LOG.info("parseListPersons::personGrp: " + persId +":"+ persName == "unknown" ? persId : persName +":"+ type +":"+ sex +":"+ age +":"+ occupation);
                    vertexFromSubjectId.put(persId, relationGraph.add(new PersonSubject(persId, persName == "unknown" ? persId : persName, type, sex, age, occupation, true)));
                } else if (listPersonChild.getLocalName().equals("person") &&
                           listPersonChild.getNamespaceURI().equals(RelationGraphSerializer.TEI_NS)) {
                    LOG.info("listPerson/listPerson/person");
                    parsePersons(listPersonChild, type);
                }
            }
            //next listPersonChild node
            listPersonChild = listPersonChild.getNextSibling();
        }
    }


    public void parseListRelations(Node relations) throws XPathException {
        if (relations.getNodeType() == Node.ELEMENT_NODE &&
            relations.getLocalName().equals("listRelation") &&
            relations.getNamespaceURI().equals(RelationGraphSerializer.TEI_NS)) {
            //Get the First Child
            Node child = relations.getFirstChild();
            while (child != null) {
                String relType = "unknown";
                String name;
                boolean hasMutual = false;
                boolean hasActive = false;
                
                //Parse each of the child nodes
                if (child.getNodeType() == Node.ELEMENT_NODE && child.hasChildNodes()) {
                    if (child.getLocalName().equals("relation") &&
                        child.getNamespaceURI().equals(RelationGraphSerializer.TEI_NS)) {
                
                        NamedNodeMap relAttrs = child.getAttributes();
                        if (relAttrs.getLength() > 0) {
                            name = relAttrs.getNamedItem("name").getNodeValue();
                            if (relAttrs.getNamedItem("type") != null && !"".equals(relAttrs.getNamedItem("type").getNodeValue())) {
                                relType = relAttrs.getNamedItem("type").getNodeValue();
                            }

                            if (relAttrs.getNamedItem("mutual") != null && !"".equals(relAttrs.getNamedItem("mutual").getNodeValue())) {
                                String[] mutual;
                                mutual = getIds(relAttrs.getNamedItem("mutual").getNodeValue());
                                hasMutual = true;
                                LOG.info("parseListRelations::mutual: " + relType +":"+ name);
                                connectMutual(relType, name, mutual);
                            } else if (relAttrs.getNamedItem("active") != null && !"".equals(relAttrs.getNamedItem("active").getNodeValue()) && !hasMutual) {
                                String[] active;
                                String[] passive;
                                
                                active = getIds(relAttrs.getNamedItem("active").getNodeValue());

                                passive = getIds(relAttrs.getNamedItem("passive").getNodeValue());
                                LOG.info("parseListRelations::activePassive: " + relType +":"+ name);
                                connectActivePassive(relType, name, active, passive);
                            }
                        }
                    }
                }
                //next relation element
                child = child.getNextSibling();
            }
        }
    }

    public static String[] getIds(final String attrValue) {
        HashSet<String> set = new HashSet(); 
        for (String idref : attrValue.split("\\s+")) {
            set.add(idref.substring(1));
        }
        return set.toArray(new String[0]);
    }

    private void connectMutual(String type, String name, String[] subjectIds) {
        Relation r1 = new Relation(name, type);
        if (subjectIds != null) {
            String id1 = subjectIds[0];
            for (String id : subjectIds) {
                if (vertexFromSubjectId.get(id) != null) {
                    id1 = id;
                    break;
                }
            }

            for (String id : subjectIds) {
                if (!id.equals(id1)) {
                    if (vertexFromSubjectId.get(id) == null) {
                        LOG.error("Vertex is missing for mutual-id: " + id);
                    } else {
                        relationGraph.connectUndirected(vertexFromSubjectId.get(id1), vertexFromSubjectId.get(id), r1);
                    }
                }
            }
        }
    }

    private void connectActivePassive(String type, String name, String[] activeSubjectIds, String[] passiveSubjectIds) {
        Relation r1 = new Relation(name, type);
        for (String activeId : activeSubjectIds) {
            for (String passiveId : passiveSubjectIds) {
                if (!activeId.equals(passiveId)) {
                    if (vertexFromSubjectId.get(activeId) == null) {
                        LOG.error("Vertex is missing for activeId: " + activeId);
                    } else if (vertexFromSubjectId.get(passiveId) == null) {
                        LOG.error("Vertex is missing for passiveId: " + passiveId);
                    } else {
                        relationGraph.connectDirected(vertexFromSubjectId.get(activeId), vertexFromSubjectId.get(passiveId), r1);
                    }
                }
            }
        }
    }
}
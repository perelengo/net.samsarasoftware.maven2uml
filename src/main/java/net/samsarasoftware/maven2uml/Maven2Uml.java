package net.samsarasoftware.maven2uml;

/*-
 * #%L
 * maven2uml
 * %%
 * Copyright (C) 2014 - 2017 Pere Joseph Rodriguez
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 * #L%
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;



public class Maven2Uml {

	private static final String XPATH_PARENT = "./parent";
	private static final String XPATH_REPOSITORIES = "./repositories";
	private static final String XPATH_DEPENDENCIES = "./dependencies";
	private static final String XPATH_PROJECT = "/project";
	private static final String[] HTTP_REPO1_MAVEN_ORG_MAVEN2 = new String[] {"https://repo1.maven.org/maven2/","file:///C:\\Users\\pjosepro\\.m2\\repository\\"};
	private static final String M2_REPO = "M2_REPO";
	private static final boolean PROCESS_TRANSITIVE_DEPENDENCIES = false;
	private static final String GROUP_ID_REGEXP = ".*";//"eu\\.europa\\.ema.*";
	private String ficheroOrigen;
	private String ficheroDestino;
	private File destino;
	private File origen;
	private XPathFactory xPathfactory;
	private OutputStreamWriter salida;
	private StringBuilder appliedStereotypes;
	private StringBuilder externalDependencies;
	private StringBuilder inlineBuffer;
	private DocumentBuilderFactory factory;
	private Map<String,String> alreadyDefinedComponents;
	private Map<String,String> alreadyDefinedUsages;
	private StringBuilder repositoriesBuffer;
	
	public static void main(String[] args) {
		Maven2Uml s=null;
		try {
		
			s = new Maven2Uml();
			s.parseParams(args);
			s.run();
		} catch (Exception e) {
			e.printStackTrace(); 
		}
		
	}

	

    private void run() throws Exception {

		try {
			configure();
		} catch (Exception e) {
			throw e;
		}
    	
    	//Ejemplo de xpath
    	
    	inlineBuffer=new StringBuilder();
    	appliedStereotypes=new StringBuilder();
    	externalDependencies=new StringBuilder();
    	alreadyDefinedComponents=new HashMap<String, String>();
    	alreadyDefinedUsages=new HashMap<String, String>();
    	repositoriesBuffer=new StringBuilder();
    	
    	initializeUmlModel(inlineBuffer);
		processParentPom(origen);

    	
    	finalizeDependencies(inlineBuffer);
    	finalizeRepositories(inlineBuffer);
    	finalizeUmlModel(inlineBuffer);
    	applyStereotypes(inlineBuffer);
    	finalizeXmi(inlineBuffer);
    	
    	salida.write(inlineBuffer.toString());
    	salida.close();
	}


	private void processParentPom(File parentPomFile) throws Exception {
		InputStream is=null;
		Document doc = null;
		try{
			DocumentBuilder builder = factory.newDocumentBuilder();
			
			if(parentPomFile.exists()){
				is=new FileInputStream(parentPomFile);
				doc = builder.parse(is);
			}else{
				for(int i=0;i<HTTP_REPO1_MAVEN_ORG_MAVEN2.length;i++) {
					try{
						String m2RepoPath=System.getenv().get(M2_REPO);
						is=new URL(HTTP_REPO1_MAVEN_ORG_MAVEN2[i]+parentPomFile.getAbsolutePath().replaceAll("\\\\","\\/").replace((m2RepoPath.replaceAll("\\\\","\\/")),"").replaceAll("^/","")).openStream();
						doc = builder.parse(is);
						
						break;
					}catch(Exception e) {}
				}
				
			}
		} catch (FileNotFoundException e) {
    		e.printStackTrace();
		}finally{
			if(is!=null){
				try{is.close();}catch(Exception e){}
			}
		}
		try {
        	
        	
        	doc.getDocumentElement().normalize();
        	
    		XPath xpath = xPathfactory.newXPath();
        	XPathExpression expr = xpath.compile(XPATH_PROJECT);
		
	    	Node projectNode=(Node) expr.evaluate(doc, XPathConstants.NODE);
	    	
	    	Map<String, String> properties=new HashMap<>();
	    	properties = processProperties(parentPomFile,projectNode, properties, true);
	    	
	    	String groupId=getGroupId(projectNode,properties);
	    	String version=properties.get("project.version");
			

	    	
	    	XPath xpath2 = xPathfactory.newXPath();
	    	XPathExpression expr2 = xpath2.compile(XPATH_DEPENDENCIES);
	    	Node dependenciesNode2=(Node) expr2.evaluate(projectNode, XPathConstants.NODE);
	    	String rootId=createRootComponent(inlineBuffer,projectNode,properties);
	    	
	    	XPath xpath3 = xPathfactory.newXPath();
	    	XPathExpression expr3 = xpath3.compile(XPATH_REPOSITORIES);
	    	Node repositoriesNode3=(Node) expr3.evaluate(projectNode, XPathConstants.NODE);
	    	processRepositories(repositoriesBuffer,repositoriesNode3,inlineBuffer,projectNode, properties );


	    	processModules(parentPomFile,projectNode, properties, groupId, version);
	    	
	    	//procesamos al final las dependencias para que no declaremos como dependencia un m?dulo pendiente de procesar
	    	if(dependenciesNode2!=null){
	    		processUsages(inlineBuffer, rootId, dependenciesNode2,projectNode, true,properties);
	    		processDependencies(inlineBuffer,rootId,dependenciesNode2,projectNode,true,true, properties);
	    	}
	    	processModulesDependencies(parentPomFile,projectNode,true, properties, groupId, version);
	    	
	    	finalizeRootComponent(inlineBuffer);
	    	
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
    	
	}



	private Map<String, String> processProperties(File parentPomFile, Node projectNode, Map<String, String> currentProperties, boolean overwrite) throws Exception {
		try {
			if(projectNode!=null){
				XPath xpath = xPathfactory.newXPath();
			
		    	XPathExpression expr = xpath.compile("./properties/*");
		    	NodeList properties=(NodeList) expr.evaluate(projectNode, XPathConstants.NODESET);
		    	for(int i=0;i<properties.getLength();i++){
		    		String propertyName = properties.item(i).getNodeName();
		    		String propertyValue = properties.item(i).getTextContent();
		    		if(overwrite) {
		    			currentProperties.put(propertyName,propertyValue);
		    		}else {
		    			if(currentProperties.get(propertyName)==null)
		    				currentProperties.put(propertyName,propertyValue);
		    		}
		    	}
		    	
		    	String groupId=getGroupId(projectNode, currentProperties);
		    	String version=getVersionInPomHierarchy(projectNode, groupId,  null, currentProperties);
		    	currentProperties.put("project.version", version);
		    	currentProperties.put("pom.version", getVersion(projectNode, currentProperties));
		    	currentProperties.put("project.groupId", groupId);
		    	currentProperties.put("pom.groupId", groupId);
		    			
			}
		}catch(Exception e){
			e.printStackTrace();
			throw e;
		}
		return currentProperties;
		
	}



	private void processRepositories(StringBuilder repositoriesBuffer, Node repositoriesNode, StringBuilder inlineBuffer, Node projectNode, Map<String, String> properties) throws Exception {
		try {
			if(repositoriesNode!=null){
				XPath xpath = xPathfactory.newXPath();
			
		    	XPathExpression expr = xpath.compile("./repository");
		    	NodeList repositories=(NodeList) expr.evaluate(repositoriesNode, XPathConstants.NODESET);
		    	for(int i=0;i<repositories.getLength();i++){
		    		String repoXmiId=createRepositoryComponent(repositoriesBuffer,repositories.item(i));
		    		createRepositoryUsage(inlineBuffer,projectNode,repoXmiId, properties);
		    	}
			}
		}catch(Exception e){
			e.printStackTrace();
			throw e;
		}
	}



	private String createRepositoryComponent(StringBuilder currentBuffer, Node projectNode) throws Exception {
    	String id=getId(projectNode);
		String layout=getLayout(projectNode);
		String url=getUrl(projectNode);
		String snapshots=getRepositoryEnabledSnapshots(projectNode);
		
		
		String rootId="repository_"+id.replaceAll(" ", "");
		if(alreadyDefinedComponents.get(rootId)==null){
			alreadyDefinedComponents.put(rootId,rootId);
			
	    	currentBuffer.append(openPackagedElementComponent(
	    			rootId
	        		,id
	    	));
	    	finalizePackagedElementComponent(currentBuffer);
	    	appliedStereotypes.append("<maven:repository xmi:id=\""+rootId.replaceAll(" ", "")+"_repository\" base_Component=\""+rootId+"\" name=\""+id+"\" layout=\""+layout+"\" url=\""+url+"\" snapshots=\""+snapshots+"\"/>");
	    	return rootId.replaceAll(" ", "");  
		}
		return alreadyDefinedComponents.get(rootId);
	}



	private void processModules(File pomFile, Node projectNode, Map<String, String> properties, String groupId, String version) throws Exception {
		try {
			XPath xpath = xPathfactory.newXPath();
		
	    	XPathExpression expr = xpath.compile("//project/modules//module");
	    	NodeList modules=(NodeList) expr.evaluate(projectNode, XPathConstants.NODESET);
	    	for(int i=0;i<modules.getLength();i++){
	    		String moduleName=modules.item(i).getTextContent();
	    		String moduleFilePath=pomFile.getParent()+File.separator+moduleName+File.separator+"pom.xml";
	    		File moduleFile=new File(moduleFilePath);
	    		processModulePom(moduleFile, properties, groupId, version);
	    	}
	    	
		}catch(Exception e){
			e.printStackTrace();
			throw e;
		}
	}
	
	private void processModulesDependencies(File pomFile, Node projectNode, boolean processOptionalDependencies, Map<String, String> properties, String groupId, String version) throws Exception {
		try {
			XPath xpath = xPathfactory.newXPath();
		
	    	XPathExpression expr = xpath.compile("//module");
	    	NodeList modules=(NodeList) expr.evaluate(projectNode, XPathConstants.NODESET);
	    	for(int i=0;i<modules.getLength();i++){
	    		String moduleName=modules.item(i).getTextContent();
	    		String moduleFilePath=pomFile.getParent()+File.separator+moduleName+File.separator+"pom.xml";
	    		File moduleFile=new File(moduleFilePath);
	    		processModulePomDependencies(moduleFile,true, properties,groupId,version);
	    	}
	    	
		}catch(Exception e){
			e.printStackTrace();
			throw e;
		}
	}

	private void processModulePom(File moduleFile, Map<String, String> properties, String groupId2, String version2) throws Exception {
		InputStream is=null;
		try{
			DocumentBuilder builder = factory.newDocumentBuilder();
        	Document doc = null;
        	
        	if(moduleFile.exists()){
				is=new FileInputStream(moduleFile);
				doc = builder.parse(is);
			}else{
				for(int i=0;i<HTTP_REPO1_MAVEN_ORG_MAVEN2.length;i++) {
					try{
						String m2RepoPath=System.getenv().get(M2_REPO);
						is=new URL(HTTP_REPO1_MAVEN_ORG_MAVEN2[i]+moduleFile.getAbsolutePath().replaceAll("\\\\","\\/").replace(m2RepoPath.replaceAll("\\\\","\\/"),"").replaceAll("^/","")).openStream();
						doc = builder.parse(is);
						break;
					}catch(Exception e) {}
				}
			}
			try {
    		
	        	
	        	doc.getDocumentElement().normalize();
	        	
	    		XPath xpath = xPathfactory.newXPath();
	        	XPathExpression expr = xpath.compile(XPATH_PROJECT);
	
		    	Node projectNode=(Node) expr.evaluate(doc, XPathConstants.NODE);

		    	Map<String, String> moduleProperties = processProperties(moduleFile, projectNode, properties, true);
		    	
		    	XPath xpath2 = xPathfactory.newXPath();
		    	XPathExpression expr2 = xpath2.compile(XPATH_DEPENDENCIES);
		    	Node dependenciesNode2=(Node) expr2.evaluate(projectNode, XPathConstants.NODE);
		    	String rootId_created=createNestedClassifierComponent(inlineBuffer,projectNode,moduleProperties);
		    	
		    	String groupId=getGroupId(projectNode,moduleProperties);
				String artifactId=getArtifactId(projectNode,moduleProperties);
				String packaging=getPackaging(projectNode,moduleProperties);
				String version=getVersion(projectNode,moduleProperties);
				
				String rootId=generateId(
		        		groupId
		        		,artifactId
		        		,packaging
		        		,version
		        	);
		    	if(dependenciesNode2!=null)
		    		processUsages(inlineBuffer, rootId, dependenciesNode2,projectNode, true,moduleProperties);
		    	processModules(moduleFile,projectNode,moduleProperties, groupId, version);
		    	
		    	XPath xpath3 = xPathfactory.newXPath();
		    	XPathExpression expr3 = xpath3.compile(XPATH_REPOSITORIES);
		    	Node repositoriesNode3=(Node) expr3.evaluate(projectNode, XPathConstants.NODE);
		    	processRepositories(repositoriesBuffer,repositoriesNode3,inlineBuffer,projectNode, moduleProperties);
		    	
		    	if(rootId_created!=null)
		    		finalizeNestedClassifierComponent(inlineBuffer);
		    	
	
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}
    	} catch (FileNotFoundException e) {
    		e.printStackTrace();
		}finally{
			if(is!=null){
				try{is.close();}catch(Exception e){}
			}
		}
	}

	private void processModulePomDependencies(File moduleFile,boolean processSubModules, Map<String, String> properties, String groupId2, String version2 ) throws Exception {
		InputStream is=null;
		DocumentBuilder builder = factory.newDocumentBuilder();
    	Document doc = null;
    	
		try{
			if(moduleFile.exists()){
				is=new FileInputStream(moduleFile);
				doc = builder.parse(is);
			}else{
				for(int i=0;i<HTTP_REPO1_MAVEN_ORG_MAVEN2.length;i++) {
					try{
						String m2RepoPath=System.getenv().get(M2_REPO);
						is=new URL(HTTP_REPO1_MAVEN_ORG_MAVEN2[i]+moduleFile.getAbsolutePath().replaceAll("\\\\","\\/").replace(m2RepoPath.replaceAll("\\\\","\\/"),"").replaceAll("^/","")).openStream();
						doc = builder.parse(is);
						break;
					}catch(Exception e) {}
				}
			}
	    	try {
	        	
	        	doc.getDocumentElement().normalize();
	        	
	    		XPath xpath = xPathfactory.newXPath();
	        	XPathExpression expr = xpath.compile(XPATH_PROJECT);
			
		    	Node projectNode=(Node) expr.evaluate(doc, XPathConstants.NODE);
		    	
		    	Map<String, String> moduleProperties = processProperties(moduleFile, projectNode, properties, true);
		    	
		    	
		    	XPath xpath2 = xPathfactory.newXPath();
		    	XPathExpression expr2 = xpath2.compile(XPATH_DEPENDENCIES);
		    	Node dependenciesNode2=(Node) expr2.evaluate(projectNode, XPathConstants.NODE);
		    	if(dependenciesNode2!=null){
		        	String groupId=getGroupId(projectNode, moduleProperties);
		    		String artifactId=getArtifactId(projectNode, moduleProperties);
		    		String packaging=getPackaging(projectNode, moduleProperties);
		    		String version=getVersionInPomHierarchy(projectNode, groupId, null, moduleProperties);
			    		String moduleId=generateId(
			            		groupId
			            		,artifactId
			            		,packaging
			            		,version
			            	);
			    		
			    		if(groupId.matches(GROUP_ID_REGEXP)) 
			    			processDependencies(inlineBuffer,moduleId,dependenciesNode2,projectNode,true,true,moduleProperties);
		    	}
		    	if(processSubModules) 
		    		processModulesDependencies(moduleFile,projectNode,true, moduleProperties, groupId2, version2);
		    	
	    	} catch (FileNotFoundException e) {
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}
    	} catch (FileNotFoundException e) {
    		e.printStackTrace();
		}finally{
			if(is!=null){
				try{is.close();}catch(Exception e){}
			}
		}	    	
	}	
	




	private void finalizeDependencies(StringBuilder currentBuffer) {
		currentBuffer.append(externalDependencies);
	}
	private void finalizeRepositories(StringBuilder currentBuffer) {
		currentBuffer.append(repositoriesBuffer);
	}
	private void processUsages(StringBuilder inlineBuffer,String rootId, Node dependenciesNode,Node projectNode, boolean processOptionalDependencies, Map<String, String> moduleProperties) throws Exception {
    	XPath xpath = xPathfactory.newXPath();
    	XPathExpression expr = xpath.compile(".//dependency");
    	NodeList dependencies=(NodeList) expr.evaluate(dependenciesNode, XPathConstants.NODESET);
    	for(int i=0;i<dependencies.getLength();i++){
    		boolean optional=getOptional(dependencies.item(i));
    		if((optional && processOptionalDependencies ) || !optional){
    			createUsage(inlineBuffer, rootId, dependencies.item(i),projectNode, moduleProperties);
    		}
    	}
	}


	private void processModulePomUsages(StringBuilder buffer,File moduleFile,boolean processSubModules, boolean processOptionalDependencies, String rootId,Map<String, String> properties) throws Exception {
			InputStream is=null;
    		DocumentBuilder builder = factory.newDocumentBuilder();
    		
        	Document doc = null;
			try{
				if(moduleFile.exists()){
					is=new FileInputStream(moduleFile);
					doc = builder.parse(is);
					try {
						doc.getDocumentElement().normalize();
					}catch (NullPointerException e) {
						System.err.println(moduleFile);
						e.printStackTrace();
						throw e;
					}
				}else{
					String m2RepoPath=System.getenv().get(M2_REPO);
					for(int i=0;i<HTTP_REPO1_MAVEN_ORG_MAVEN2.length;i++) {
						try{
							
							try {
								is=new URL(HTTP_REPO1_MAVEN_ORG_MAVEN2[i]+moduleFile.getAbsolutePath().replaceAll("\\\\","\\/").replace(m2RepoPath.replaceAll("\\\\","\\/"),"").replaceAll("^/","")).openStream();
								doc = builder.parse(is);
							
								doc.getDocumentElement().normalize();
							}catch (Exception e) {
								System.err.println((i+1)+" of "+HTTP_REPO1_MAVEN_ORG_MAVEN2.length+": "+HTTP_REPO1_MAVEN_ORG_MAVEN2[i]+moduleFile.getAbsolutePath().replaceAll("\\\\","\\/").replace(m2RepoPath.replaceAll("\\\\","\\/"),"").replaceAll("^/",""));
								e.printStackTrace();
								throw e;
							}
							break;
						}catch(Exception e) {
						}
					}
				}
				
				try {
					try {
						doc.getDocumentElement().normalize();
					}catch (NullPointerException e) {
						throw new FileNotFoundException("Unable to locate file "+moduleFile.getAbsolutePath());
					}
		    		XPath xpath = xPathfactory.newXPath();
		        	XPathExpression expr = xpath.compile(XPATH_PROJECT);
	
			    	Node projectNode=(Node) expr.evaluate(doc, XPathConstants.NODE);

			    	Map<String, String> moduleProperties = processProperties(moduleFile, projectNode, properties, true);

			    	XPath xpath2 = xPathfactory.newXPath();
			    	XPathExpression expr2 = xpath2.compile(XPATH_DEPENDENCIES);
			    	Node dependenciesNode2=(Node) expr2.evaluate(projectNode, XPathConstants.NODE);
			    	
					
			    	if(dependenciesNode2!=null)
			    		processUsages(externalDependencies, rootId, dependenciesNode2,projectNode, processOptionalDependencies, moduleProperties);
			    	
			    	
//			    	String m2RepoPath=System.getenv().get(M2_REPO);
//			    	XPath xpathp = xPathfactory.newXPath();
//	    			XPathExpression exprp = xpathp.compile(XPATH_PARENT);
//	    			Node parentNode=(Node) exprp.evaluate(projectNode, XPathConstants.NODE);
//	    			if(parentNode!=null){
//	    		    	 String groupId2=getGroupId(parentNode, moduleProperties,null);
//	    		    	 String artifactId2=getArtifactId(parentNode, moduleProperties);
//	    		    	 String version2=getVersion(parentNode, moduleProperties, null);
//		    					File transitiveParentPom=new File(m2RepoPath+File.separator
//		    							+groupId2.replaceAll("\\.", "\\/")+File.separator
//		    							+artifactId2+File.separator
//		    							+version2+File.separator
//		    							+artifactId2+"-"+version2+".pom");
//		    					processModulePomUsages(externalDependencies,transitiveParentPom, false,false, rootId, moduleProperties);
//	    					
//	    			}
			    	
		    	}catch(FileNotFoundException e){
		    		e.printStackTrace();
				} catch (Exception e) {
					e.printStackTrace();
					throw e;
				}
	    	} catch (FileNotFoundException e) {
	    		e.printStackTrace();
			}finally{
				if(is!=null){
					try{is.close();}catch(Exception e){}
				}
			}
	}



	private void processTransitiveDependencies( Node dependencyNode,Node projectNode,Map<String, String> moduleProperties) throws Exception {
		//FIXME Aqu? hauriem de mirar quina versió té d'entre: La pròpia, la del parent, la de managed dependencies.
		
		String m2RepoPath=System.getenv().get(M2_REPO);
    	String groupId=getGroupId(dependencyNode, moduleProperties);
		String artifactId=getArtifactId(dependencyNode, moduleProperties);
		String version=getDependencyVersionInPomHierarchy(projectNode,groupId,artifactId, null, moduleProperties);
			File transitivePom=new File(m2RepoPath+File.separator
					+groupId.replaceAll("\\.", "\\/")+File.separator
					+artifactId+File.separator
					+version+File.separator
					+artifactId+"-"+version+".pom");
			if(groupId.matches(GROUP_ID_REGEXP) )
				processTransitivePomDependencies(transitivePom, true, moduleProperties,groupId,version);
	}

	private String getDependencyVersionInPomHierarchy (Node projectNode,String groupId,String artifactId, String shortestPathVersion, Map<String, String> moduleProperties) throws Exception {
		String localVersion = shortestPathVersion;
		String dependencyManagementLocalVersion=null;
		
		if(localVersion==null){
			XPath xpath = xPathfactory.newXPath();
	    	XPathExpression expr = xpath.compile("./dependencies//dependency[./artifactId='"+artifactId+"' and groupId='"+groupId+"']");
	    	Node dependenciesNode=(Node) expr.evaluate(projectNode, XPathConstants.NODE);
	    	if(dependenciesNode!=null && null!=getVersion(dependenciesNode, moduleProperties)){
		    	localVersion=getVersion(dependenciesNode, moduleProperties);
		    	shortestPathVersion=localVersion;
	    	}
		}
		
    	XPath xpath2 = xPathfactory.newXPath();
    	XPathExpression expr2 = xpath2.compile("./dependencyManagement//dependency[./artifactId='"+artifactId+"' and groupId='"+groupId+"']");
    	Node dependenciesNode2=(Node) expr2.evaluate(projectNode, XPathConstants.NODE);
    	if(dependenciesNode2!=null  && null!=getVersion(dependenciesNode2, moduleProperties)){
    		dependencyManagementLocalVersion=getVersion(dependenciesNode2, moduleProperties);
    		return dependencyManagementLocalVersion;
    	}else{
    		XPath xpathp = xPathfactory.newXPath();
			XPathExpression exprp = xpathp.compile(XPATH_PARENT);
			Node parentNode=(Node) exprp.evaluate(projectNode, XPathConstants.NODE);
			if(parentNode!=null){
				String m2RepoPath=System.getenv().get(M2_REPO);
				
		    	String pGroupId=getGroupId(parentNode, moduleProperties);
				String pArtifactId=getArtifactId(parentNode, moduleProperties);
				String pVersion=getVersion(parentNode, moduleProperties);
					File transitivePom=new File(m2RepoPath+File.separator
							+pGroupId.replaceAll("\\.", "\\/")+File.separator
							+pArtifactId+File.separator
							+pVersion+File.separator
							+pArtifactId+"-"+pVersion+".pom");
				
					return getDependencyVersionInParentPomHierarchy( transitivePom, groupId, artifactId, shortestPathVersion, moduleProperties);
			}else{
				return shortestPathVersion;
			}
    	}
	}



	private String getVersionInPomHierarchy(Node projectNode,String groupId, String shortestPathVersion, Map<String, String> moduleProperties) throws Exception {
		
		if(getVersion(projectNode, moduleProperties)!=null){
			return getVersion(projectNode, moduleProperties);
		}else{
    		XPath xpathp = xPathfactory.newXPath();
			XPathExpression exprp = xpathp.compile(XPATH_PARENT);
			Node parentNode=(Node) exprp.evaluate(projectNode, XPathConstants.NODE);
			if(parentNode!=null){
				String m2RepoPath=System.getenv().get(M2_REPO);
		    	String pGroupId=getGroupId(parentNode, moduleProperties);
				String pArtifactId=getArtifactId(parentNode, moduleProperties);
				String pVersion=getVersion(parentNode, moduleProperties);
					File transitivePom=new File(m2RepoPath+File.separator
							+pGroupId.replaceAll("\\.", "\\/")+File.separator
							+pArtifactId+File.separator
							+pVersion+File.separator
							+pArtifactId+"-"+pVersion+".pom");
				
					return getVersionInParentPomHierarchy( transitivePom, groupId, shortestPathVersion, moduleProperties);
			}else {
				return shortestPathVersion;
			}
    	}
	}

	private String getVersionInParentPomHierarchy(File parentPom, String groupId, String shortestPathVersion, Map<String, String> moduleProperties) throws Exception {
		InputStream is=null;
		DocumentBuilder builder = factory.newDocumentBuilder();
    	Document doc = null;
		try{
			if(parentPom.exists()){
				is=new FileInputStream(parentPom);
				doc = builder.parse(is);
			}else{

				for(int i=0;i<HTTP_REPO1_MAVEN_ORG_MAVEN2.length;i++) {
					try{
						String m2RepoPath=System.getenv().get(M2_REPO);
						is=new URL(HTTP_REPO1_MAVEN_ORG_MAVEN2[i]+parentPom.getAbsolutePath().replaceAll("\\\\","\\/").replace(m2RepoPath.replaceAll("\\\\","\\/"),"").replaceAll("^/","")).openStream();
						doc = builder.parse(is);
						break;
					}catch(Exception e) {}
				}
			}
			try {
	        	doc.getDocumentElement().normalize();
	        	
	    		XPath xpath = xPathfactory.newXPath();
	        	XPathExpression expr = xpath.compile(XPATH_PROJECT);
			
		    	Node projectNode=(Node) expr.evaluate(doc, XPathConstants.NODE);
				
		    	
		    	return getVersionInPomHierarchy(projectNode, groupId,  shortestPathVersion, moduleProperties);
		    	
			} catch (FileNotFoundException e) {
	    		e.printStackTrace();
	    		return shortestPathVersion;
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}	
    	} catch (FileNotFoundException e) {
    		e.printStackTrace();
    		return shortestPathVersion;
		}finally{
			if(is!=null){
				try{is.close();}catch(Exception e){}
			}
		}		
	}

	private String getDependencyVersionInParentPomHierarchy(File parentPom, String groupId, String artifactId, String shortestPathVersion, Map<String, String> moduleProperties) throws Exception {
		InputStream is=null;
		DocumentBuilder builder = factory.newDocumentBuilder();
    	Document doc = null;
		try{
			if(parentPom.exists()){
				is=new FileInputStream(parentPom);
				doc = builder.parse(is);
			}else{

				for(int i=0;i<HTTP_REPO1_MAVEN_ORG_MAVEN2.length;i++) {
					try{
						String m2RepoPath=System.getenv().get(M2_REPO);
						is=new URL(HTTP_REPO1_MAVEN_ORG_MAVEN2[i]+parentPom.getAbsolutePath().replaceAll("\\\\","\\/").replace(m2RepoPath.replaceAll("\\\\","\\/"),"").replaceAll("^/","")).openStream();
						doc = builder.parse(is);
						break;
					}catch(Exception e) {}
				}
			}
			try {
				if(doc==null) {
					return shortestPathVersion;
				}else {
		        	doc.getDocumentElement().normalize();
		        	
		    		XPath xpath = xPathfactory.newXPath();
		        	XPathExpression expr = xpath.compile(XPATH_PROJECT);
				
			    	Node projectNode=(Node) expr.evaluate(doc, XPathConstants.NODE);
					
			    	Map<String, String> parentProperties = processProperties(parentPom, projectNode, moduleProperties,false);
			    	
			    	String pVersion= getDependencyVersionInPomHierarchy(projectNode,groupId,artifactId, null, parentProperties );
			    	if(pVersion==null){
			    		return shortestPathVersion;
			    	}else{
			    		return pVersion;
			    	}
				}
			} catch (FileNotFoundException e) {
	    		e.printStackTrace();
	    		return shortestPathVersion;
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}	
    	} catch (FileNotFoundException e) {
    		e.printStackTrace();
    		return shortestPathVersion;
		}finally{
			if(is!=null){
				try{is.close();}catch(Exception e){}
			}
		}		
	}


	private void processTransitivePomDependencies(File moduleFile, boolean processParentDependencies, Map<String, String> callerProperties, String groupId2, String version2) throws Exception {
		InputStream is=null;
		DocumentBuilder builder = factory.newDocumentBuilder();
    	Document doc = null;
    	
		try{
			if(moduleFile.exists()){
				is=new FileInputStream(moduleFile);
				try{
					doc = builder.parse(is);
				} catch (Exception e) {
					System.err.println("Error while parsing "+moduleFile.getAbsolutePath());
					e.printStackTrace();
					throw e;
				}
			}else{

				for(int i=0;i<HTTP_REPO1_MAVEN_ORG_MAVEN2.length;i++) {
					try{
						String m2RepoPath=System.getenv().get(M2_REPO);
						is=new URL(HTTP_REPO1_MAVEN_ORG_MAVEN2[i]+moduleFile.getAbsolutePath().replaceAll("\\\\","\\/").replace(m2RepoPath.replaceAll("\\\\","\\/"),"").replaceAll("^/","")).openStream();
						doc = builder.parse(is);
						break;
					}catch(Exception e) {}
				}
			}
	    	try {
	    		if(doc==null) {
	    			throw new FileNotFoundException("Unable to locate file "+moduleFile.getAbsolutePath());
	    		}
	        	doc.getDocumentElement().normalize();
	        	XPath xpath = xPathfactory.newXPath();
	        	XPathExpression expr = xpath.compile(XPATH_PROJECT);
			
		    	Node projectNode=(Node) expr.evaluate(doc, XPathConstants.NODE);
		    	Map<String, String> moduleProperties = processProperties(null, projectNode, new HashMap<>(),false);
	        	
	    		
		    	XPath xpath2 = xPathfactory.newXPath();
		    	XPathExpression expr2 = xpath2.compile(XPATH_DEPENDENCIES);
		    	if(projectNode==null) {
		    		throw new FileNotFoundException("Unable to locate dependencies "+moduleFile.getAbsolutePath());
		    	}
		    	Node dependenciesNode2=(Node) expr2.evaluate(projectNode, XPathConstants.NODE);
		    	if(dependenciesNode2!=null){
		        	String groupId=getGroupId(projectNode,moduleProperties);
		    		String artifactId=getArtifactId(projectNode,moduleProperties);
		    		String packaging=getPackaging(projectNode,moduleProperties);
		    		String scope=getScope(projectNode);
		    		String version=getVersionInPomHierarchy(projectNode, groupId, null,moduleProperties);
			    		String moduleId=generateId(
			            		groupId
			            		,artifactId
			            		,packaging
			            		,version
			            	);
			    		
			    		if(groupId.matches(GROUP_ID_REGEXP) && !scope.equals("test"))
			    			processDependencies(inlineBuffer,moduleId,dependenciesNode2,projectNode,true,true,moduleProperties);
				    	
		    	}
		    	
		    	if(processParentDependencies){
	    			XPath xpathp = xPathfactory.newXPath();
	    			XPathExpression exprp = xpathp.compile(XPATH_PARENT);
	    			Node parentNode=(Node) exprp.evaluate(projectNode, XPathConstants.NODE);
	    			if(parentNode!=null){
	    				String m2RepoPath=System.getenv().get(M2_REPO);
	    		    	String groupId=getGroupId(parentNode,moduleProperties);
	    				String artifactId=getArtifactId(parentNode,moduleProperties);
	    				String version=getVersion(parentNode,moduleProperties);
	    					File transitivePom=new File(m2RepoPath+File.separator
	    							+groupId.replaceAll("\\.", "\\/")+File.separator
	    							+artifactId+File.separator
	    							+version+File.separator
	    							+artifactId+"-"+version+".pom");
	    				
	    					if(groupId.matches(GROUP_ID_REGEXP) &&  PROCESS_TRANSITIVE_DEPENDENCIES)
	    						processTransitivePomDependencies( transitivePom,true,moduleProperties, groupId, null);
	    			}
		    	}
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}
    	} catch (FileNotFoundException e) {
    		e.printStackTrace();
		}finally{
			if(is!=null){
				try{is.close();}catch(Exception e){}
			}
		}
	}



	private void processDependencies(StringBuilder inlineBuffer,String rootId, Node dependenciesNode, Node projectNode, boolean processOptionalDependencies, boolean processUsages, Map<String, String> moduleProperties) throws Exception {
    	XPath xpath = xPathfactory.newXPath();
    	XPathExpression expr = xpath.compile(".//dependency");
    	NodeList dependencies=(NodeList) expr.evaluate(dependenciesNode, XPathConstants.NODESET);
    	for(int i=0;i<dependencies.getLength();i++){
    		boolean optional=getOptional(dependencies.item(i));
    		if((optional && processOptionalDependencies ) || !optional){
    			String id=createPackagedElementComponent(externalDependencies,dependencies.item(i),projectNode, moduleProperties, null, null);
	    		if(id!=null){
	    			if(processUsages && PROCESS_TRANSITIVE_DEPENDENCIES){
	    				
		    				String m2RepoPath=System.getenv().get(M2_REPO);
		    		    	String groupId=getGroupId(dependencies.item(i),moduleProperties);
		    				String artifactId=getArtifactId(dependencies.item(i),moduleProperties);
		    				String packaging=getPackaging(dependencies.item(i),moduleProperties);
		    				String version=getDependencyVersionInPomHierarchy(projectNode, groupId, artifactId, null,moduleProperties);
		    				String usageId=generateId(groupId, artifactId, packaging, version);
		    				File transitivePom=new File(m2RepoPath+File.separator
		    						+groupId.replaceAll("\\.", "\\/")+File.separator
		    						+artifactId+File.separator
		    						+version+File.separator
		    						+artifactId+"-"+version+".pom");
		    				if(groupId.matches(GROUP_ID_REGEXP) )
		    					processModulePomUsages(externalDependencies,transitivePom, false,false, usageId,moduleProperties);

	    			
			    			
	    			
	    			}

	    			finalizePackagedElementComponent(externalDependencies);
	    		}
	    		
    			String scope=getScope(dependencies.item(i));
    			if(!scope.equals("test"))
    				if(PROCESS_TRANSITIVE_DEPENDENCIES)
    					processTransitiveDependencies(dependencies.item(i),projectNode,moduleProperties);
    		}
    	}
	}



	private void createUsage(StringBuilder currentBuffer,String rootId, Node dependencyNode, Node projectNode, Map<String, String> moduleProperties) throws Exception {
    	String groupId=getGroupId(dependencyNode, moduleProperties);
		String artifactId=getArtifactId(dependencyNode, moduleProperties);
		String packaging=getPackaging(dependencyNode, moduleProperties);
		String version=getDependencyVersionInPomHierarchy(projectNode,groupId,artifactId, null, moduleProperties);
		String scope=getScope(dependencyNode);
		
		if(!groupId.matches(GROUP_ID_REGEXP)) 
			return;
		
		String usedId=generateId(
        		groupId
        		,artifactId
        		,packaging
        		,version
        	);
		if(alreadyDefinedUsages.get("usage_"+rootId+"_"+usedId+"_scope")==null){
			alreadyDefinedUsages.put("usage_"+rootId+"_"+usedId+"_scope","usage_"+rootId+"_"+usedId+"_scope");
			
			currentBuffer.append("<packagedElement xmi:type=\"uml:Usage\" xmi:id=\"usage_"+rootId+"_"+usedId+"\" client=\""+rootId+"\" supplier=\""+usedId+"\"/>");
	    	appliedStereotypes.append("<maven:"+scope+" xmi:id=\"usage_"+rootId+"_"+usedId+"_scope\" base_Dependency=\"usage_"+rootId+"_"+usedId+"\"/>");
		
			try {
		    	XPath xpath = xPathfactory.newXPath();
		    	XPathExpression expr = xpath.compile("optional");
		    	Node optional = (Node) expr.evaluate(dependencyNode, XPathConstants.NODE);
		    	if(optional!=null){
		    		appliedStereotypes.append("<maven:optional xmi:id=\"usage_"+rootId+"_"+usedId+"_optional\" base_Dependency=\"usage_"+rootId+"_"+usedId+"\"/>");
		    	}
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			}
		}
	}




	private void createRepositoryUsage(StringBuilder currentBuffer, Node projectNode, String repoXmiId, Map<String, String> moduleProperties) throws Exception {
    	String groupId=getGroupId(projectNode,moduleProperties);
		String artifactId=getArtifactId(projectNode,moduleProperties);
		String packaging=getPackaging(projectNode,moduleProperties);
		String version=getVersion(projectNode,moduleProperties);
		
		String sourceId=generateId(
        		groupId
        		,artifactId
        		,packaging
        		,version
        	);
		currentBuffer.append("<packagedElement xmi:type=\"uml:Usage\" xmi:id=\"usage_"+sourceId+"_"+repoXmiId+"\" client=\""+sourceId+"\" supplier=\""+repoXmiId+"\"/>");
	
	}

	private void finalizeXmi(StringBuilder currentBuffer) throws IOException {
		currentBuffer.append("</xmi:XMI>");
	}

	private void finalizeUmlModel(StringBuilder currentBuffer) throws IOException {
		currentBuffer.append("</uml:Model>");
	}

	private void applyStereotypes(StringBuilder currentBuffer) throws IOException {
		currentBuffer.append(appliedStereotypes.toString());
	}

	private void finalizeRootComponent(StringBuilder currentBuffer) throws IOException {
		currentBuffer.append(closePackagedElementComponent());
	}

	private String closePackagedElementComponent() throws IOException {
		return "</packagedElement>";
	}
	private void finalizePackagedElementComponent(StringBuilder currentBuffer) throws IOException {
		currentBuffer.append( closePackagedElementComponent());
	}
		
	private void finalizeNestedClassifierComponent(StringBuilder currentBuffer) throws IOException {
		currentBuffer.append( closeNestedClassifierComponent());
	}

	private String closeNestedClassifierComponent() throws IOException {
		return "</nestedClassifier>";
	}


	private String createRootComponent(StringBuilder currentBuffer,Node projectNode, Map<String, String> properties) throws Exception {
    	String groupId=getGroupId(projectNode,properties);
		String artifactId=getArtifactId(projectNode,properties);
		String packaging=getPackaging(projectNode,properties);
		String version=getVersion(projectNode,properties);
		
		
		String rootId=generateId(
        		groupId
        		,artifactId
        		,packaging
        		,version
        	);
		if(alreadyDefinedComponents.get(rootId)==null){
			alreadyDefinedComponents.put(rootId,rootId);
			
	    	currentBuffer.append(openPackagedElementComponent(
	    			rootId
	        		,artifactId
	    	));
	    	appliedStereotypes.append("<maven:maven xmi:id=\""+rootId+"_maven\" base_Component=\""+rootId+"\" groupId=\""+groupId+"\" artifactId=\""+artifactId+"\" version=\""+version+"\" packaging=\""+packaging+"\"/>");
	    	appliedStereotypes.append("<standard:Subsystem xmi:id=\""+rootId+"_subsystem\" base_Component=\""+rootId+"\"/>");
	    	appliedStereotypes.append("<standard:Realization xmi:id=\""+rootId+"_realization\" base_Classifier=\""+rootId+"\"/>");
	    			  
	    	return rootId;
		}else{
			return null;
		}
        	
	}

	private String createPackagedElementComponent(StringBuilder currentBuffer,Node dependenciesNode,Node projectNode, Map<String, String> moduleProperties, String groupId2, String version2) throws Exception {
    	String groupId=getGroupId(dependenciesNode,moduleProperties);
		String artifactId=getArtifactId(dependenciesNode,moduleProperties);
		String packaging=getPackaging(dependenciesNode,moduleProperties);
		String version=getDependencyVersionInPomHierarchy(projectNode,groupId,artifactId, null, moduleProperties);

		if(!groupId.matches(GROUP_ID_REGEXP)) 
			return null;	
		
		String rootId=generateId(
        		groupId
        		,artifactId
        		,packaging
        		,version
        	);
		if(alreadyDefinedComponents.get(rootId)==null){
			alreadyDefinedComponents.put(rootId,rootId);
			
	    	currentBuffer.append(openPackagedElementComponent(
	    			rootId
	        		,artifactId

	    	));
	    	appliedStereotypes.append("<maven:maven xmi:id=\""+rootId+"_maven\" base_Component=\""+rootId+"\" groupId=\""+groupId+"\" artifactId=\""+artifactId+"\" version=\""+version+"\" packaging=\""+packaging+"\"/>");
	
	    	return rootId;
		}else{
			return null;
		}
        	
	}

	private String createNestedClassifierComponent(StringBuilder currentBuffer,Node projectNode, Map<String, String> moduleProperties) throws Exception {
    	String groupId=getGroupId(projectNode,moduleProperties);
		String artifactId=getArtifactId(projectNode,moduleProperties);
		String packaging=getPackaging(projectNode,moduleProperties);
		String version=getVersion(projectNode,moduleProperties);
		
		if(!groupId.matches(GROUP_ID_REGEXP)) 
			return null;
		
		String rootId=generateId(
        		groupId
        		,artifactId
        		,packaging
        		,version
        	);
		if(alreadyDefinedComponents.get(rootId)==null){
			alreadyDefinedComponents.put(rootId,rootId);
			
	    	currentBuffer.append(openNestedClassifierComponent(
	    			rootId
	        		,artifactId
	    	));
	    	appliedStereotypes.append("<maven:maven xmi:id=\""+rootId+"_maven\" base_Component=\""+rootId+"\" groupId=\""+groupId+"\" artifactId=\""+artifactId+"\" version=\""+version+"\" packaging=\""+packaging+"\"/>");
	
	    	return rootId;
		}else{
			return null;
		}
        	
	}
	private String generateId(String groupId, String artifactId, String packaging, String version) {
		return groupId
		+"_"
		+artifactId
		+"_"
		+version
		+"_"
		+packaging;
	}



	private String openPackagedElementComponent(String id,String name) throws IOException {
		return "<packagedElement xmi:type=\"uml:Component\" xmi:id=\""
				+id
				+ "\" name=\""
				+name
				+"\">";
		
	}
	private String openNestedClassifierComponent(String id, String name) throws IOException {
		return "<nestedClassifier xmi:type=\"uml:Component\" xmi:id=\""
				+id
				+ "\" name=\""
				+name
				+"\">";
		
	}



	private String getGroupId(Node sourceNode, Map<String, String> moduleProperties) throws Exception {
		String g= getXpathString(sourceNode, "groupId");
		if( ("".equals(g) || g==null )) {
			XPath xpathp = xPathfactory.newXPath();
			XPathExpression exprp = xpathp.compile(XPATH_PARENT);
			Node parentNode=(Node) exprp.evaluate(sourceNode, XPathConstants.NODE);
			if(parentNode!=null){
				return getGroupId(parentNode, moduleProperties);
			}
		}
		
		if(g!=null) {
			for (String key : moduleProperties.keySet()) {
				g=g.replace("${"+key+"}", moduleProperties.get(key));
			}
		}
		return g;
	}
	private String getArtifactId(Node sourceNode, Map<String, String> moduleProperties) throws Exception {
		return getXpathString(sourceNode, "artifactId");
	}
	private String getPackaging(Node sourceNode, Map<String, String> moduleProperties) throws Exception {
		String packaging=getXpathString(sourceNode, "packaging");
		String type=getXpathString(sourceNode, "type");
		if("".equals(packaging) && "".equals(type)) return "jar";
		if("".equals(packaging) && !("".equals(type))) return type;
		return packaging;
	}
	private String getVersion(Node sourceNode, Map<String, String> moduleProperties) throws Exception {
		String v=getXpathString(sourceNode, "version");
		if( ("".equals(v) || v==null )) {
			XPath xpathp = xPathfactory.newXPath();
			XPathExpression exprp = xpathp.compile(XPATH_PARENT);
			Node parentNode=(Node) exprp.evaluate(sourceNode, XPathConstants.NODE);
			if(parentNode!=null){
				return getVersion(parentNode, moduleProperties);
			}
		}
		if(!"".equals(v) && v!=null) {
			for (String key : moduleProperties.keySet()) {
				v=v.replace("${"+key+"}", moduleProperties.get(key));
			}
		}
		return v;
	}
	private String getScope(Node sourceNode) throws Exception {
		String scope= getXpathString(sourceNode, "scope");
		if("".equals(scope)) return "compile";
		else return scope;
	}
	private boolean getOptional(Node sourceNode) throws Exception {
		try {
	    	XPath xpath = xPathfactory.newXPath();
	    	XPathExpression expr = xpath.compile("optional");
	    	Node pnl = (Node) expr.evaluate(sourceNode, XPathConstants.NODE);
			return pnl!=null;
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}
	private String getId(Node sourceNode) throws Exception {
		return getXpathString(sourceNode, "id");
	}
	private String getLayout(Node sourceNode) throws Exception {
		String layout= getXpathString(sourceNode, "layout");
		if("".equals(layout)) return "default";
		else return layout;
	}
	private String getUrl(Node sourceNode) throws Exception {
		return getXpathString(sourceNode, "url");
	}
	private String getRepositoryEnabledSnapshots(Node sourceNode) throws Exception {
		String enabled= getXpathString(sourceNode, "snapshots/enabled");
		if("".equals(enabled)) return "true";
		else return enabled;
	}
	private NodeList getXpathNodeList(Object sourceNode,String xpathStr) throws Exception {

		try {
	    	XPath xpath = xPathfactory.newXPath();
	    	XPathExpression expr = xpath.compile(xpathStr);
	    	NodeList pnl = (NodeList) expr.evaluate(sourceNode, XPathConstants.NODESET);
			return pnl;
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
    	
	}

	private String getXpathString(Object sourceNode,String xpathStr) throws Exception {

		try {
	    	XPath xpath = xPathfactory.newXPath();
	    	XPathExpression expr = xpath.compile(xpathStr);
	    	String out = (String) expr.evaluate(sourceNode, XPathConstants.STRING);
			return out;
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
    	
	}


	private final void configure() throws Exception {


    	try {
    		destino=new File(ficheroDestino);
    		destino.getParentFile().mkdirs();
    		salida=new OutputStreamWriter(new FileOutputStream(destino),"UTF-8");
    		
    		origen=new File(ficheroOrigen);
    		
    		factory = DocumentBuilderFactory.newInstance();
    		factory.setNamespaceAware(false);
    		factory.setValidating(false);

        	
        	xPathfactory = XPathFactory.newInstance();
        	


		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}


	private void initializeUmlModel(StringBuilder currentBuffer) throws IOException {
		currentBuffer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
		currentBuffer.append("<xmi:XMI xmi:version=\"20131001\" xmlns:xmi=\"http://www.omg.org/spec/XMI/20131001\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:ecore=\"http://www.eclipse.org/emf/2002/Ecore\"  xmlns:maven=\"http://www.samsarasoftware.net/maven.profile\" xmlns:standard=\"http://www.eclipse.org/uml2/5.0.0/UML/Profile/Standard\" xmlns:uml=\"http://www.eclipse.org/uml2/5.0.0/UML\" xsi:schemaLocation=\"http://www.samsarasoftware.net/maven.profile platform:/plugin/net.samsarasoftware.metamodels/profiles/maven.profile.uml#_nj-MoHNYEeaWPPFDHEFm0g\">");
		currentBuffer.append("<uml:Model xmi:id=\"model\" name=\"model\">");
		
		
	}



	private void parseParams(String[] args) throws Exception {
			if(args.length<4) printUsage();

		   for (int i=0;i<args.length;i++) {
				if("-url_origen".equals(args[i])){
					ficheroOrigen=args[++i];
				}else if("-url_destino".equals(args[i])){
					ficheroDestino=args[++i];
				}else{
					printUsage();
				}
			}
		   
		   if(ficheroDestino==null) printUsage();
		   if(ficheroOrigen==null) printUsage();
		}
	   
	private void printUsage() throws Exception {
		throw new Exception("Errores en los argumentos. Uso:\n java -jar <nombrejar>.jar \n "
				+ "[-url_origen <pom.xml de entrada >]\n "
				+ "[-url_destino <ficehro uml a generar>]"	
				+ "");
	}
}

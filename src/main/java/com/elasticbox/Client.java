/*
 * ElasticBox Confidential
 * Copyright (c) 2014 All Right Reserved, ElasticBox Inc.
 *
 * NOTICE:  All information contained herein is, and remains the property
 * of ElasticBox. The intellectual and technical concepts contained herein are
 * proprietary and may be covered by U.S. and Foreign Patents, patents in process,
 * and are protected by trade secret or copyright law. Dissemination of this
 * information or reproduction of this material is strictly forbidden unless prior
 * written permission is obtained from ElasticBox.
 */

package com.elasticbox;

import java.io.IOException;
import java.net.URLEncoder;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.sf.json.JSON;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.util.EntityUtils;

/**
 *
 * @author Phong Nguyen Le
 */
public class Client {
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String JSON_CONTENT_TYPE = "application/json";    
    private static final String UTF_8 = "UTF-8";

    private static final String BASE_ELASTICBOX_SCHEMA = "http://elasticbox.net/schemas/";
    private static final String DEPLOYMENT_REQUEST_SCHEMA_NAME = "deploy-instance-request";

    public static interface InstanceState {
        String PROCESSING = "processing";
        String DONE = "done";
        String UNAVAILABLE = "unavailable";
    }
    
    public static interface InstanceOperation {
        String DEPLOY = "deploy";
        String REINSTALL = "reinstall";
        String RECONFIGURE = "reconfigure";
        String POWERON = "poweron";
        String SHUTDOWN = "shutdown";
        String SHUTDOWN_SERVICE = "shutdown_service";
        String TERMINATE = "terminate";
        String TERMINATE_SERVICE = "terminate_service";
        String SNAPSHOT = "snapshot";
    }
    
    public static final Set FINISH_STATES = new HashSet(Arrays.asList(InstanceState.DONE, InstanceState.UNAVAILABLE));
    public static final Set SHUTDOWN_OPERATIONS = new HashSet(Arrays.asList(InstanceOperation.SHUTDOWN, InstanceOperation.SHUTDOWN_SERVICE));
    public static final Set TERMINATE_OPERATIONS = new HashSet(Arrays.asList(InstanceOperation.TERMINATE, InstanceOperation.TERMINATE_SERVICE));
    public static final Set ON_OPERATIONS = new HashSet(Arrays.asList(InstanceOperation.DEPLOY, InstanceOperation.POWERON, InstanceOperation.REINSTALL, InstanceOperation.RECONFIGURE, InstanceOperation.SNAPSHOT));
    public static final Set OFF_OPERATIONS = new HashSet(Arrays.asList(InstanceOperation.SHUTDOWN, InstanceOperation.SHUTDOWN_SERVICE, InstanceOperation.TERMINATE, InstanceOperation.TERMINATE_SERVICE));
    
    private static HttpClient httpClient = null;
    
    private static String getSchemaVersion(String url) {
        return url.substring(BASE_ELASTICBOX_SCHEMA.length(), url.indexOf('/', BASE_ELASTICBOX_SCHEMA.length()));
    }

    private final String endpointUrl;
    private final String username;
    private final String password;
    private String token = null;

    public Client(String endpointUrl, String username, String password) {
        createHttpClient();
        this.endpointUrl = endpointUrl.endsWith("/") ? endpointUrl.substring(0, endpointUrl.length() - 1) : endpointUrl;
        this.username = username;
        this.password = password;
    }
    
    public String getEndpointUrl() {
        return endpointUrl;
    }
        
    public void connect() throws IOException {
        HttpPost post = new HttpPost(MessageFormat.format("{0}/services/security/token", endpointUrl));
        JSONObject json = new JSONObject();
        json.put("email", this.username);
        json.put("password", this.password);
        post.setEntity(new StringEntity(json.toString(), ContentType.APPLICATION_JSON));
        HttpResponse response = httpClient.execute(post);
        int status = response.getStatusLine().getStatusCode();
        if (status != HttpStatus.SC_OK) {
            throw new ClientException(MessageFormat.format("Error {0} connecting to ElasticBox at {1}: {2}", status, 
                    this.endpointUrl, getErrorMessage(getResponseBodyAsString(response))), status);
        }
        token = getResponseBodyAsString(response);            
    }
    
    public JSONArray getWorkspaces() throws IOException {
        return (JSONArray) doGet(MessageFormat.format("{0}/services/workspaces", endpointUrl), true);
    }
    
    public JSONArray getBoxes(String workspaceId) throws IOException {
        return (JSONArray) doGet(MessageFormat.format("{0}/services/workspaces/{1}/boxes", endpointUrl, URLEncoder.encode(workspaceId, UTF_8)), true);
    }
    
    public JSONArray getBoxVersions(String boxId) throws IOException {
        return (JSONArray) doGet(MessageFormat.format("{0}/services/boxes/{1}/versions", endpointUrl, boxId), true);
    }

    public JSONObject getBox(String boxId) throws IOException {
        return (JSONObject) doGet(MessageFormat.format("{0}/services/boxes/{1}", endpointUrl, boxId), false);
    }
    
    private boolean canChange(String workspaceId, String boxId) throws IOException {
        JSONObject box = (JSONObject) doGet(MessageFormat.format("{0}/services/boxes/{1}", endpointUrl, boxId), false);
        if (workspaceId.equals(box.getString("owner"))) {
            return true;
        }
        
        for (Object json : box.getJSONArray("members")) {
            JSONObject member = (JSONObject) json;
            if (workspaceId.equals(member.getString("workspace")) && "collaborator".equals(member.getString("role"))) {
                return true;
            }
        }
        
        return false;
    }
    
    public JSONArray getProfiles(String workspaceId, String boxId) throws IOException {
        if (StringUtils.isBlank(workspaceId)) {
            throw new IOException("workspaceId cannot be blank");
        };

        JSONArray profiles = (JSONArray) doGet(MessageFormat.format("{0}/services/workspaces/{1}/profiles?box_version={2}", endpointUrl, URLEncoder.encode(workspaceId, UTF_8), boxId), true);
        if (!canChange(workspaceId, boxId)) {
            // this is a read-only box that could have profiles associated with the its versions
            JSONArray versions = getBoxVersions(boxId);
            if (!versions.isEmpty()) {
                Set<String> versionIDs = new HashSet<String>();
                for(Object version : versions) {
                    versionIDs.add(((JSONObject) version).getString("id"));
                }          

                JSONArray allProfiles = (JSONArray) doGet(MessageFormat.format("{0}/services/workspaces/{1}/profiles", endpointUrl, URLEncoder.encode(workspaceId, UTF_8), boxId), true);
                for (Object json : allProfiles) {
                    JSONObject profile = (JSONObject) json;
                    if (versionIDs.contains(profile.getJSONObject("box").getString("version"))) {
                        profiles.add(profile);
                    }
                }
            }
        }
        
        return profiles;
    }
    
    public JSONObject getInstance(String instanceId) throws IOException {
        if (StringUtils.isBlank(instanceId)) {
            throw new IOException("instanceId cannot be blank");
        };
        return (JSONObject) doGet(MessageFormat.format("{0}/services/instances/{1}", endpointUrl, instanceId), false);
    }

    public JSONObject getProfile(String profileId) throws IOException {
        if (StringUtils.isBlank(profileId)) {
            throw new IOException("profileId cannot be blank");
        };
        return (JSONObject) doGet(MessageFormat.format("{0}/services/profiles/{1}", endpointUrl, profileId), false);  
    }
    
    public JSONArray getInstances(String workspaceId) throws IOException {
        if (StringUtils.isBlank(workspaceId)) {
            throw new IOException("workspaceId cannot be blank");
        };
        return (JSONArray) doGet(MessageFormat.format("/services/workspaces/{0}/instances", workspaceId), true);
    }
    
    public JSONArray getInstances(String workspaceId, List<String> instanceIDs) throws IOException {
        if (StringUtils.isBlank(workspaceId)) {
            throw new IOException("workspaceId cannot be blank");
        };

        JSONArray instances = new JSONArray();
        for (int start = 0; start < instanceIDs.size();) {
            int end = Math.min(800, instanceIDs.size());            
            StringBuilder ids = new StringBuilder();
            for (int i = start; i < end; i++) {
                ids.append(instanceIDs.get(i)).append(',');
            }
            instances.addAll((JSONArray) doGet(MessageFormat.format("/services/workspaces/{0}/instances?ids={1}", workspaceId, ids.toString()), true));
            start = end;
        }

        return instances;
    }
    
    public JSONArray getInstances(List<String> instanceIDs) throws IOException {
        JSONArray instances = new JSONArray();
        Set<String> fetchedInstanceIDs = new HashSet<String>();
        JSONArray workspaces = getWorkspaces();
        for (Object workspace : workspaces) {
            JSONArray workspaceInstances = getInstances(((JSONObject) workspace).getString("id"), instanceIDs);            
            for (Object instance : workspaceInstances) {
                String instanceId = ((JSONObject) instance).getString("id");
                instanceIDs.remove(instanceId);
                if (!fetchedInstanceIDs.contains(instanceId)) {
                    instances.add(instance);
                    fetchedInstanceIDs.add(instanceId);
                }
            }
            
            if (instanceIDs.isEmpty()) {
                break;
            }
        }
        
        return instances;
    }
    
    public JSONArray getBoxStack(String boxId) throws IOException {
        return (JSONArray) doGet(MessageFormat.format("/services/boxes/{0}/stack", boxId), true);
    }

    public JSONObject updateInstance(JSONObject instance, JSONArray variables) throws IOException  {
        if (variables != null && !variables.isEmpty()) {
            JSONArray instanceBoxes = instance.getJSONArray("boxes");
            JSONObject mainBox = instanceBoxes.getJSONObject(0);
            JSONArray boxStack = new BoxStack(mainBox.getString("id"), instanceBoxes, this).toJSONArray();
            JSONArray boxVariables = new JSONArray();
            for (Object box : boxStack) {
                boxVariables.addAll(((JSONObject) box).getJSONArray("variables"));
            }
            JSONArray instanceVariables = instance.getJSONArray("variables");
            List<JSONObject> newVariables = new ArrayList<JSONObject>();
            for (Object variable : variables) {
                JSONObject variableJson = (JSONObject) variable;
                JSONObject instanceVariable = findVariable(variableJson, instanceVariables);            
                if (instanceVariable == null) {
                    JSONObject boxVariable = findVariable(variableJson, boxVariables);
                    if (boxVariable != null) {
                        instanceVariable = JSONObject.fromObject(boxVariable);
                        if (instanceVariable.getString("scope").isEmpty()) {
                            instanceVariable.remove("scope");
                        }
                        newVariables.add(instanceVariable);
                    }
                }
                if (instanceVariable != null) {
                    instanceVariable.put("value", variableJson.getString("value"));
                }
            }
            instanceVariables.addAll(newVariables);
            instance.put("variables", instanceVariables);
        }
        
        HttpPut put = new HttpPut(getInstanceUrl(instance.getString("id")));
        put.setEntity(new StringEntity(instance.toString(), ContentType.APPLICATION_JSON));
        try {
            HttpResponse response = execute(put);
            return JSONObject.fromObject(getResponseBodyAsString(response));
        } finally {
            put.reset();
        }        
    }
        
    protected class ProgressMonitor implements IProgressMonitor {
        private final String instanceUrl;
        private final long creationTime;
        private final Object waitLock = new Object();
        private final Set<String> operations;
        private final String lastModified;
        
        private ProgressMonitor(String instanceUrl, Set<String> operations, String lastModified) {
            this.instanceUrl = instanceUrl;
            this.operations = operations;
            this.lastModified = lastModified;
            creationTime = System.currentTimeMillis();            
        }
        
        private JSONObject getInstance() throws IOException, IncompleteException {
            try {
                return (JSONObject) doGet(instanceUrl, false);
            } catch (ClientException ex) {
                if (ex.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                    throw new IncompleteException(MessageFormat.format("The instance {0} cannot be found", instanceUrl));
                } else {
                    throw ex;
                }                
            }            
        }
        
        public String getResourceUrl() {
            return instanceUrl;
        }

        public boolean isDone() throws IncompleteException, IOException {
            return isDone(getInstance());
        }
        
        public boolean isDone(JSONObject instance) throws IncompleteException, IOException {
            String updated = instance.getString("updated");
            String state = instance.getString("state");
            String operation = instance.getString("operation");
            if (lastModified.equals(updated) || !FINISH_STATES.contains(state)) {
                return false;
            }

            if (state.equals(InstanceState.UNAVAILABLE)) {
                throw new IncompleteException(MessageFormat.format("The instance at {0} is unavailable", instanceUrl));
            } 

            if (operations != null && !operations.contains(operation)) {
                throw new IncompleteException(MessageFormat.format("Unexpected operation ''{0}'' has been performed for instance {1}", operation, instanceUrl));
            }
            
            return true;
        }

        public void waitForDone(int timeout) throws IncompleteException, IOException {
            long startTime = System.currentTimeMillis();
            long remainingTime = timeout * 60000;
            do {
                if (isDone()) {
                    return;
                }
                
                synchronized(waitLock) {
                    try {
                        waitLock.wait(1000);
                    } catch (InterruptedException ex) {
                    }
                }            

                long currentTime = System.currentTimeMillis();
                remainingTime =  remainingTime - (currentTime - startTime);
                startTime = currentTime;                
            } while (timeout == 0 || remainingTime > 0);

            JSONObject instance = getInstance();
            if (!isDone(instance)) {
                throw new TimeoutException(
                        MessageFormat.format("The instance at {0} is not in ready after waiting for {1} minutes. Current instance state: {2}",
                                instanceUrl, timeout, instance.getString("state")));
                
            }
        }
        
        public long getCreationTime() {
            return this.creationTime;
        }
    }
    
    public IProgressMonitor deploy(String profileId, String workspaceId, String environment, int instances, JSONArray variables) throws IOException {
        return deploy(null, profileId, workspaceId, environment, instances, variables);
    }
    
    public IProgressMonitor deploy(String boxVersion, String profileId, String workspaceId, String environment, int instances, JSONArray variables) throws IOException {        
        JSONObject profile = (JSONObject) doGet(MessageFormat.format("/services/profiles/{0}", profileId), false);
        JSONObject deployRequest = new JSONObject();
        
        String profileSchema = profile.getString("schema");
        String schemaVersion = getSchemaVersion(profileSchema);
        if (schemaVersion.compareTo("2014-05-23") > 0) {
            if (boxVersion != null) {
                profile.getJSONObject("box").put("version", boxVersion);
            }
            
            JSONObject serviceProfile = profile.getJSONObject("profile");
            if (serviceProfile.containsKey("instances")) {
                serviceProfile.put("instances", instances);
            }            
            deployRequest.put("schema", BASE_ELASTICBOX_SCHEMA + schemaVersion + '/' + DEPLOYMENT_REQUEST_SCHEMA_NAME);
            for (Object json : variables) {
                JSONObject variable = (JSONObject) json;
                if (variable.containsKey("scope") && variable.getString("scope").isEmpty()) {
                    variable.remove("scope");
                }
            }
            deployRequest.put("variables", variables);
        } else {
            JSONObject mainInstance = (JSONObject) profile.getJSONArray("instances").get(0);
            JSONArray jsonVars = mainInstance.getJSONArray("variables");
            for (Object json : variables) {
                JSONObject variable = (JSONObject) json;
                JSONObject jsonVar = findVariable(variable, jsonVars);
                if (jsonVar == null) {
                    jsonVars.add(variable);
                } else {
                    jsonVar.put("value", variable.getString("value"));
                }
            }
            JSONObject serviceProfile = mainInstance.getJSONObject("profile");
            if (serviceProfile.containsKey("instances")) {
                serviceProfile.put("instances", instances);
            }                        
            deployRequest.put("schema", BASE_ELASTICBOX_SCHEMA + schemaVersion + "/deploy-service-request");
        }
        deployRequest.put("environment", environment);
        deployRequest.put("profile", profile);
        deployRequest.put("owner", workspaceId);        
        
        HttpPost post = new HttpPost(MessageFormat.format("{0}/services/instances", endpointUrl));
        post.setEntity(new StringEntity(deployRequest.toString(), ContentType.APPLICATION_JSON));
        try {
            HttpResponse response = execute(post);
            JSONObject instance = JSONObject.fromObject(getResponseBodyAsString(response));
            return new ProgressMonitor(endpointUrl + instance.getString("uri"), 
                    Collections.singleton(InstanceOperation.DEPLOY), instance.getString("updated"));
        } finally {
            post.reset();
        }
    }

    public IProgressMonitor reconfigure(String instanceId, JSONArray variables) throws IOException {
        JSONObject instance = doOperation(instanceId, InstanceOperation.RECONFIGURE, variables);
        return new ProgressMonitor(getInstanceUrl(instanceId), Collections.singleton(InstanceOperation.RECONFIGURE),
                instance.getString("updated"));        
    }
    
    private JSONObject doOperation(String instanceId, String operation, JSONArray variables) throws IOException {
        return doOperation(getInstance(instanceId), operation, variables);
    }
        
    private JSONObject doOperation(JSONObject instance, String operation, JSONArray variables) throws IOException {
        String instanceId = instance.getString("id");
        String instanceUrl = getInstanceUrl(instanceId);
        if (variables != null && !variables.isEmpty()) {
            instance = updateInstance(instance, variables);
        }
        
        HttpPut put = new HttpPut(MessageFormat.format("{0}/{1}", instanceUrl, operation));
        try {
            execute(put);
            return getInstance(instanceId);
        } finally {
            put.reset();
        }
        
    }
    
    private IProgressMonitor doTerminate(String instanceUrl, String operation) throws IOException {
        JSONObject instance = (JSONObject) doGet(instanceUrl, false);
        HttpDelete delete = new HttpDelete(MessageFormat.format("{0}?operation={1}", instanceUrl, operation));
        try {
            execute(delete);
            return new ProgressMonitor(instanceUrl, TERMINATE_OPERATIONS, instance.getString("updated"));
        } finally {
            delete.reset();
        }        
    }
    
    public IProgressMonitor terminate(String instanceId) throws IOException {
        String instanceUrl = getInstanceUrl(instanceId);
        JSONObject instance = (JSONObject) doGet(instanceUrl, false);
        String state = instance.getString("state");  
        String operation = instance.getString("operation");
        String terminateOperation = (state.equals(InstanceState.DONE) && ON_OPERATIONS.contains(operation)) ||
                (state.equals(InstanceState.UNAVAILABLE) && operation.equals(InstanceOperation.TERMINATE))? 
                "terminate" : "force_terminate";
        return doTerminate(instanceUrl, terminateOperation);
    }
    
    public IProgressMonitor forceTerminate(String instanceId) throws IOException {
        return doTerminate(getInstanceUrl(instanceId), "force_terminate");
    }
    
    public IProgressMonitor poweron(String instanceId) throws IOException {
        JSONObject instance = getInstance(instanceId);
        String state = instance.getString("state");
        if (ON_OPERATIONS.contains(instance.getString("operation")) && (InstanceState.DONE.equals(state) || InstanceState.PROCESSING.equals(state))) {
            return new IProgressMonitor.DoneMonitor(getInstanceUrl(instanceId));
        }
        
        instance = doOperation(instance, InstanceOperation.POWERON, null);
        return new ProgressMonitor(getInstanceUrl(instanceId), Collections.singleton(InstanceOperation.POWERON),
            instance.getString("updated"));
    }

    public IProgressMonitor shutdown(String instanceId) throws IOException {
        JSONObject instance = doOperation(instanceId, InstanceOperation.SHUTDOWN, null);
        return new ProgressMonitor(getInstanceUrl(instanceId), SHUTDOWN_OPERATIONS, instance.getString("updated"));
    }

    public void delete(String instanceId) throws IOException {
        HttpDelete delete = new HttpDelete(MessageFormat.format("{0}?operation=delete", getInstanceUrl(instanceId)));
        try {
            execute(delete);
        } finally {
            delete.reset();
        }
    }

    public IProgressMonitor reinstall(String instanceId, JSONArray variables) throws IOException {
        JSONObject instance = doOperation(instanceId, InstanceOperation.REINSTALL, variables);
        return new ProgressMonitor(getInstanceUrl(instanceId), Collections.singleton(InstanceOperation.REINSTALL),
            instance.getString("updated"));
    }
        
    public JSON doGet(String url, boolean isArray) throws IOException {
        if (url.startsWith("/")) {
            url = endpointUrl + url;
        }
        HttpGet get = new HttpGet(url);
        try {
            HttpResponse response = execute(get);
            return isArray ? JSONArray.fromObject(getResponseBodyAsString(response)) : JSONObject.fromObject(getResponseBodyAsString(response));                    
        } finally {
            get.reset();
        }
    }

    public String getInstanceUrl(String instanceId) {
        return MessageFormat.format("{0}/services/instances/{1}", endpointUrl, instanceId);
    }
    
    public static final String getResourceId(String resourceUrl) {
        return resourceUrl != null ? resourceUrl.substring(resourceUrl.lastIndexOf('/') + 1) : null;
    }
    
    public static final String getPageUrl(String endpointUrl, String resourceUrl) {
        if (resourceUrl.startsWith(MessageFormat.format("{0}/services/instances/", endpointUrl))) {
            String instanceId = getResourceId(resourceUrl);
            if (instanceId != null) {
                return MessageFormat.format("{0}/#/instances/{1}/i", endpointUrl, instanceId);
            }
        }
        return null;
    }
    
    public static final String getPageUrl(String endpointUrl, JSONObject resource) {
        String resourceUri = resource.getString("uri");
        if (resourceUri.startsWith("/services/instances/")) {
            return MessageFormat.format("{0}/#/instances/{1}/{2}", endpointUrl, resource.getString("id"),
                    resource.getString("name").replaceAll("[^a-zA-Z0-9-]", "-"));
        }
        return null;
    }

    private JSONObject findVariable(JSONObject variable, JSONArray variables) {
        String name = variable.getString("name");
        String scope = variable.containsKey("scope") ? variable.getString("scope") : StringUtils.EMPTY;
        for (Object var : variables) {
            JSONObject json = (JSONObject) var;
            if (json.getString("name").equals(name)) {
                String varScope = json.containsKey("scope") ? json.getString("scope") : StringUtils.EMPTY;
                if (scope.equals(varScope)) {
                    return json;
                } 
            }
        }
        return null;
    }    
    
    private String getErrorMessage(String errorResponseBody) {
        JSONObject error = null;
        try {
            error = JSONObject.fromObject(errorResponseBody);
        } catch (JSONException ex) {
            //
        } 
        return error != null && error.containsKey("message")? error.getString("message") : errorResponseBody;
    }
    
    private void setRequiredHeaders(HttpRequestBase request) {
        request.setHeader(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE);
        request.setHeader("ElasticBox-Token", token);
    }
    
    private static String getResponseBodyAsString(HttpResponse response) throws IOException {
        HttpEntity entity = response.getEntity();
        return entity != null ? EntityUtils.toString(entity) : null;
    }
    
    protected HttpResponse execute(HttpRequestBase request) throws IOException {
        if (token == null) {
            connect();
        }
        setRequiredHeaders(request);
        HttpResponse response = httpClient.execute(request);
        int status = response.getStatusLine().getStatusCode();
        if (status == HttpStatus.SC_UNAUTHORIZED) {
            token = null;
            EntityUtils.consumeQuietly(response.getEntity());
            request.reset();
            connect();
            setRequiredHeaders(request);
            response = httpClient.execute(request);                
            status = response.getStatusLine().getStatusCode();
        }
        if (status < 200 || status > 299) {
            token = null;
            throw new ClientException(getErrorMessage(getResponseBodyAsString(response)), status);
        }            

        return response;
    }
    
    private static synchronized HttpClient createHttpClient() {
        if (httpClient == null) {
            try {
                SSLSocketFactory sslSocketFactory = new SSLSocketFactory(new TrustStrategy() {

                    public boolean isTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                        return true;
                    }

                }, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

                SchemeRegistry registry = new SchemeRegistry();
                registry.register(
                        new Scheme("http", 80, PlainSocketFactory.getSocketFactory()));
                registry.register(
                        new Scheme("https", 443, sslSocketFactory));

                ClientConnectionManager ccm = new PoolingClientConnectionManager(registry);

                httpClient = new DefaultHttpClient(ccm);
            } catch (Exception e) {
                httpClient = new DefaultHttpClient();
            }
        }
        
        return httpClient;
    }
}
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

package com.elasticbox.jenkins.builders;

import com.elasticbox.Client;
import com.elasticbox.IProgressMonitor;
import com.elasticbox.jenkins.ElasticBoxCloud;
import com.elasticbox.jenkins.DescriptorHelper;
import com.elasticbox.jenkins.util.TaskLogger;
import com.elasticbox.jenkins.util.VariableResolver;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.Builder;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 *
 * @author Phong Nguyen Le
 */
public class ReconfigureBox extends InstanceBuildStep implements IInstanceProvider {
    private final String id;
    private final String variables;
    private final String buildStepVariables;

    private transient InstanceManager instanceManager;
    private transient ElasticBoxCloud ebCloud;
    
    @DataBoundConstructor
    public ReconfigureBox(String id, String cloud, String workspace, String box, String instance, String variables, String buildStep, String buildStepVariables) {
        super(cloud, workspace, box, instance, buildStep);
        assert id != null && id.startsWith(getClass().getName() + '-');
        this.id = id;
        this.variables = variables;
        this.buildStepVariables = buildStepVariables;
        
        readResolve();
    }
    
    static void reconfigure(List<String> instanceIDs, ElasticBoxCloud ebCloud, Client client, JSONArray jsonVariables, 
            boolean waitForCompletion, TaskLogger logger) throws IOException {
        List<IProgressMonitor> monitors = new ArrayList<IProgressMonitor>();
        for (String instanceId : instanceIDs) {
            JSONArray validVariables = jsonVariables != null ? JSONArray.fromObject(jsonVariables) : new JSONArray();
            DescriptorHelper.removeInvalidVariables(validVariables, 
                    ((DescriptorImpl) Jenkins.getInstance().getDescriptorOrDie(ReconfigureBox.class)).doGetBoxStack(ebCloud.name, instanceId).getJsonArray());
            IProgressMonitor monitor = client.reconfigure(instanceId, validVariables);
            monitors.add(monitor);
            String instancePageUrl = Client.getPageUrl(ebCloud.getEndpointUrl(), monitor.getResourceUrl());
            logger.info(MessageFormat.format("Reconfiguring box instance {0}", instancePageUrl));            
        }
        if (waitForCompletion) {
            logger.info(MessageFormat.format("Waiting for {0} to be reconfigured", monitors.size() > 0 ? "the instances" : "the instance"));        
            InstanceBuildStep.waitForCompletion(Client.InstanceOperation.RECONFIGURE, monitors, ebCloud, client, logger);
        }
    }
    
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        TaskLogger logger = new TaskLogger(listener);
        logger.info("Executing Reconfigure Box build step");
        
        IInstanceProvider instanceProvider = getInstanceProvider(build);
        if (instanceProvider == null || instanceProvider.getElasticBoxCloud() == null) {
            throw new IOException("No valid ElasticBox cloud is selected for this build step.");
        }
        
        ebCloud = instanceProvider.getElasticBoxCloud();
        VariableResolver resolver = new VariableResolver(ebCloud.name, null, build, listener);
        String varStr = getBuildStep() == null ? variables : buildStepVariables;
        JSONArray jsonVariables = resolver.resolveVariables(varStr);
        
        Client client = ebCloud.createClient();
        String instanceId = instanceProvider.getInstanceId(build);
        reconfigure(Collections.singletonList(instanceId), ebCloud, client, jsonVariables, true, logger);
        instanceManager.setInstance(build, client.getInstance(instanceId));
        return true;
    }    

    public String getId() {
        return id;
    }

    public String getVariables() {
        return variables;
    }

    public String getBuildStepVariables() {
        return buildStepVariables;
    }
    
    public String getInstanceId(AbstractBuild build) {
        JSONObject instance = instanceManager.getInstance(build);
        return instance != null ? instance.getString("id") : null;
    }
    
    @Override
    public ElasticBoxCloud getElasticBoxCloud() {
        return ebCloud;
    }

    @Override
    protected Object readResolve() {
        if (instanceManager == null) {
            instanceManager = new InstanceManager();
        }
        
        return super.readResolve();
    }
    
    @Extension
    public static final class DescriptorImpl extends Descriptor {
        @Override
        public String getDisplayName() {
            return "ElasticBox - Reconfigure Box";
        }
        
        @Override
        public Builder newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            if (isPreviousBuildStepSelected(formData)) {
                formData.remove("variables");
            } else {
                formData.remove("buildStepVariables");
                if (formData.containsKey("variables")) {
                    JSONArray boxStack = doGetBoxStack(formData.getString("cloud"), formData.getString("instance")).getJsonArray();
                    formData.put("variables", DescriptorHelper.fixVariables(formData.getString("variables"), boxStack));
                }                            
            }
            
            return super.newInstance(req, formData);
        }

        public DescriptorHelper.JSONArrayResponse doGetBoxStack(@QueryParameter String cloud, 
                @QueryParameter String instance) {
            return DescriptorHelper.getInstanceBoxStack(cloud, instance);
        }
        
        public DescriptorHelper.JSONArrayResponse doGetInstances(@QueryParameter String cloud, 
                @QueryParameter String workspace, @QueryParameter String box) {
            return DescriptorHelper.getInstancesAsJSONArrayResponse(cloud, workspace, box);
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return false;
        }

    }
}
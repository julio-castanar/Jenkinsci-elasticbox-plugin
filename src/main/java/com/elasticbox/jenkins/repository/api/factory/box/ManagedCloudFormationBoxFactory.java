package com.elasticbox.jenkins.repository.api.factory.box;

import com.elasticbox.jenkins.model.box.BoxType;
import com.elasticbox.jenkins.model.box.cloudformation.CloudFormationBoxType;
import com.elasticbox.jenkins.model.box.cloudformation.ManagedCloudFormationBox;
import com.elasticbox.jenkins.model.error.ElasticBoxModelException;
import net.sf.json.JSONObject;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by serna on 11/29/15.
 */
public class ManagedCloudFormationBoxFactory extends AbstractBoxFactory<ManagedCloudFormationBox> {

    private static final Logger logger = Logger.getLogger(ManagedCloudFormationBoxFactory.class.getName());

    @Override
    public ManagedCloudFormationBox create(JSONObject jsonObject) throws ElasticBoxModelException {

        ManagedCloudFormationBox managedCloudFormationBox = new ManagedCloudFormationBox.ComplexBuilder()
                .withManagedCloudFormationType(jsonObject.getJSONObject("profile").getString("schema"))
                .withId(jsonObject.getString("id"))
                .withName(jsonObject.getString("name"))
                .build();

        return managedCloudFormationBox;
    }

    @Override
    public boolean canCreate(JSONObject jsonObject) {

        if(super.canCreate(jsonObject, BoxType.CLOUDFORMATION)){
            final String type = jsonObject.getString("type");
            try {
                final CloudFormationBoxType cloudFormationBoxType = CloudFormationBoxType.getType(type);
                return cloudFormationBoxType == CloudFormationBoxType.MANAGED;
            } catch (ElasticBoxModelException e) {
                logger.log(Level.SEVERE, "There is no CloudFormation type for type: "+type);
                e.printStackTrace();
            }

        }
        return false;
    }
}
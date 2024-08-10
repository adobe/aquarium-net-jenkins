package com.adobe.ci.aquarium.net;

import com.adobe.ci.aquarium.fish.client.ApiException;
import com.adobe.ci.aquarium.fish.client.model.Label;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import jenkins.model.TransientActionFactory;
import net.sf.json.JSONArray;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import java.util.*;

/**
 * Attach available labels to the AquariumCloud status/index page
 */
@Restricted(NoExternalUse.class)
public class AquariumCloudLabelsAction implements Action {
    public final AquariumCloud cloud;

    public AquariumCloudLabelsAction(AquariumCloud cloud) {
        this.cloud = cloud;
    }

    // We don't need to display the menu item - just to show summary, so returning null for required methods
    @CheckForNull
    @Override
    public String getIconFileName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return null;
    }

    @Nullable
    @Override
    public String getUrlName() {
        return null;
    }

    // Used in summary.jelly
    public JSONArray getLabelsLatest(){
        // Using SortedMap for in-place sorting
        TreeMap<String, Label> latestLabels = new TreeMap<String, Label>();
        try {
            // TODO: For now getting all of them, but will need to switch to latest ones, when API will be here
            List<Label> labels = cloud.getClient().labelGet();

            // Filtering to keep only the latest ones
            for( Label label : labels ) {
                Label latestLabel = latestLabels.get(label.getName());
                if( latestLabel == null || latestLabel.getVersion() < label.getVersion() ) {
                    latestLabels.put(label.getName(), label);
                }
            }
        } catch (ApiException e) {
            throw new RuntimeException(e);
        }
        return JSONArray.fromObject(latestLabels.values());
    }

    @Extension
    public static final class CloudActionFactory extends TransientActionFactory<AquariumCloud> {
        @Override
        public Class<AquariumCloud> type() {
            return AquariumCloud.class;
        }

        @NonNull
        @Override
        public Collection<? extends Action> createFor(@NonNull AquariumCloud cloud) {
            return Collections.singletonList(new AquariumCloudLabelsAction(cloud));
        }
    }
}

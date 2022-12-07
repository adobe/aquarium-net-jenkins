/**
 * Copyright 2021 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.adobe.ci.aquarium.net.pipeline;

import com.adobe.ci.aquarium.net.AquariumComputer;
import com.adobe.ci.aquarium.net.AquariumSlave;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.SlaveComputer;
import hudson.util.LogTaskListener;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.PrintStream;
import java.io.Serializable;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AquariumNodeInfoStep extends Step implements Serializable {
    private static final long serialVersionUID = 1L;

    @DataBoundConstructor public AquariumNodeInfoStep() {}

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new AquariumNodeInfoStepExecution(this, context);
    }

    public static class AquariumNodeInfoStepExecution extends SynchronousNonBlockingStepExecution<Object> {
        private static final long serialVersionUID = 1L;
        private static final transient Logger LOGGER = Logger.getLogger(AquariumNodeInfoStepExecution.class.getName());

        private final AquariumNodeInfoStep step;

        AquariumNodeInfoStepExecution(AquariumNodeInfoStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        private PrintStream logger() {
            TaskListener l = null;
            StepContext context = getContext();
            try {
                l = context.get(TaskListener.class);
            } catch (Exception x) {
                LOGGER.log(Level.WARNING, "Failed to find TaskListener in context");
            } finally {
                if (l == null) {
                    l = new LogTaskListener(LOGGER, Level.FINE);
                }
            }
            return l.getLogger();
        }

        @Override
        protected Object run() throws Exception {
            JSONObject json = null;
            try {
                LOGGER.log(Level.FINE, "Starting nodeInfo step.");

                Node node = getContext().get(Node.class);
                if( !(node instanceof AquariumSlave) ) {
                    throw new AbortException(
                            String.format("Node is not an Aquarium node: %s", node != null ? node.getNodeName() : null));
                }

                SlaveComputer comp = ((AquariumSlave) node).getComputer();
                if( !(comp instanceof AquariumComputer) ) {
                    throw new AbortException(
                            String.format("Node is not an Aquarium computer: %s", comp != null ? comp.getName() : null));
                }

                JSONObject app_info = ((AquariumComputer) comp).getAppInfo();
                JSONObject def_info = ((AquariumComputer) comp).getDefinitionInfo();
                json.put("ApplicationInfo", app_info);
                json.put("DefinitionInfo", def_info);
            } catch (InterruptedException e) {
                String msg = "Interrupted while getting node info from the node";
                logger().println(msg);
                LOGGER.log(Level.FINE, msg);
            } catch (Exception e) {
                String msg = "Failed to get node info from the node";
                logger().println(msg);
                LOGGER.log(Level.WARNING, msg, e);
            }
            return json;
        }

        @Override
        public void stop(Throwable cause) throws Exception {
            LOGGER.log(Level.FINE, "Stopping Aquarium NodeInfo step.");
            super.stop(cause);
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "aquariumNodeInfo";
        }

        @Override
        public String getDisplayName() {
            return "Get node info about the current node";
        }

        @Override
        public boolean isAdvanced() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(Node.class)));
        }
    }
}

/**
 * Copyright 2024-2025 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

// Author: Sergei Parshev (@sparshev)

package com.adobe.ci.aquarium.net.pipeline;

import com.adobe.ci.aquarium.net.AquariumCloud;
import com.adobe.ci.aquarium.net.config.AquariumLabelTemplate;
import hudson.model.TaskListener;
import hudson.util.LogTaskListener;
import hudson.AbortException;
import com.adobe.ci.aquarium.net.AquariumSlave;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import jenkins.model.Jenkins;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.Timestamp;
import aquarium.v2.LabelOuterClass;
import aquarium.v2.Common;

import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.logging.Logger;
import java.util.logging.Level;

public class AquariumCreateLabelStepExecution extends SynchronousNonBlockingStepExecution<String> {
    private static final long serialVersionUID = 1L;
    private static final transient Logger LOGGER = Logger.getLogger(AquariumCreateLabelStepExecution.class.getName());

    private final AquariumCreateLabelStep step;

    // Predefined variables
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("\\$\\{TIMESTAMP\\}");
    private static final Pattern NOW_PATTERN = Pattern.compile("\\$\\{NOW\\+([0-9]+)\\*([A-Z]+)\\}");
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    AquariumCreateLabelStepExecution(AquariumCreateLabelStep step, StepContext context) {
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
    protected String run() throws Exception {
        String templateId = step.getTemplateId();
        Map<String, String> variables = step.getVariablesAsMap();

        LOGGER.fine("Starting Aquarium Create Label step with template: " + templateId);

        // Since it makes sense to execute the step outside the Aquarium worker, we get through the available clouds
        // to find the right one where the task exists. That should not cause any issues because jenkins is usually
        // connected to just one Aquarium cluster at a time.
        List<AquariumCloud> clouds = Jenkins.get().clouds.getAll(AquariumCloud.class);
        Exception lastException = null;

        for( AquariumCloud cloud : clouds ) {
            try {
                // Find the template
                AquariumLabelTemplate template = findTemplate(cloud, templateId);
                if (template == null) {
                    continue; // Try next cloud
                }

                // Process the template with variables
                String processedTemplate = processTemplate(template.getTemplateContent(), variables);
                logger().println("Processed template content: " + processedTemplate);

                // Parse the YAML template to create Label proto
                LabelOuterClass.Label labelProto = parseTemplateToLabel(processedTemplate);

                // Create the label using the client
                String labelUid = cloud.getClient().createLabel(labelProto);

                logger().println("Created label with UID: " + labelUid);
                return labelUid;
            } catch (Exception e) {
                lastException = e;
                String msg = e.getMessage();
                logger().println(msg);
                LOGGER.log(Level.INFO, msg, e);
                // Continue to try next cloud
            }
        }

        // If we get here, no cloud succeeded
        if (lastException != null) {
            throw new AbortException("Failed to create Aquarium label: " + lastException.getMessage());
        } else {
            throw new AbortException("Failed to create Aquarium label: Template not found: " + templateId);
        }
    }

    private AquariumLabelTemplate findTemplate(AquariumCloud cloud, String templateId) {
        // First check the current cloud
        List<AquariumLabelTemplate> templates = cloud.getLabelTemplates();
        if (templates != null) {
            for (AquariumLabelTemplate template : templates) {
                if (templateId.equals(template.getId())) {
                    return template;
                }
            }
        }

        // If not found in current cloud, search all clouds
        for (hudson.slaves.Cloud c : Jenkins.get().clouds) {
            if (c instanceof AquariumCloud && !c.equals(cloud)) {
                AquariumCloud otherCloud = (AquariumCloud) c;
                List<AquariumLabelTemplate> otherTemplates = otherCloud.getLabelTemplates();
                if (otherTemplates != null) {
                    for (AquariumLabelTemplate template : otherTemplates) {
                        if (templateId.equals(template.getId())) {
                            return template;
                        }
                    }
                }
            }
        }

        return null;
    }

    private String processTemplate(String template, Map<String, String> variables) throws Exception {
        String result = template;

        // Process predefined variables first
        result = processPredefinedVariables(result);

        // Process user variables
        result = processUserVariables(result, variables);

        // Check for unresolved variables
        Matcher matcher = VARIABLE_PATTERN.matcher(result);
        if (matcher.find()) {
            throw new AbortException("Unresolved variable in template: ${" + matcher.group(1) + "}");
        }

        return result;
    }

    private String processPredefinedVariables(String template) {
        String result = template;

        // Process TIMESTAMP variable
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(DateTimeFormatter.ofPattern("yyMMdd.HHmmss"));
        result = TIMESTAMP_PATTERN.matcher(result).replaceAll(timestamp);

        // Process NOW+offset variables
        Matcher nowMatcher = NOW_PATTERN.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (nowMatcher.find()) {
            int amount = Integer.parseInt(nowMatcher.group(1));
            String unit = nowMatcher.group(2);

            Instant targetTime = calculateTargetTime(now, amount, unit);
            String isoString = targetTime.toString();
            nowMatcher.appendReplacement(sb, isoString);
        }
        nowMatcher.appendTail(sb);
        result = sb.toString();

        return result;
    }

    private Instant calculateTargetTime(LocalDateTime base, int amount, String unit) {
        Instant baseInstant = base.atZone(ZoneId.systemDefault()).toInstant();

        switch (unit.toUpperCase()) {
            case "SECOND":
                return baseInstant.plusSeconds(amount);
            case "MINUTE":
                return baseInstant.plusSeconds(amount * 60L);
            case "HOUR":
                return baseInstant.plusSeconds(amount * 3600L);
            case "DAY":
                return baseInstant.plusSeconds(amount * 86400L);
            case "WEEK":
                return baseInstant.plusSeconds(amount * 604800L);
            case "MONTH":
                return baseInstant.plusSeconds(amount * 2629746L); // Average month
            case "YEAR":
                return baseInstant.plusSeconds(amount * 31556952L); // Average year
            default:
                throw new IllegalArgumentException("Unknown time unit: " + unit);
        }
    }

    private String processUserVariables(String template, Map<String, String> variables) {
        String result = template;

        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            result = result.replace(placeholder, entry.getValue());
        }

        return result;
    }

    private LabelOuterClass.Label parseTemplateToLabel(String yamlContent) throws Exception {
        Yaml yaml = new Yaml();
        Map<String, Object> data = yaml.load(yamlContent);

        LabelOuterClass.Label.Builder labelBuilder = LabelOuterClass.Label.newBuilder();

        // Set basic fields
        if (data.containsKey("name")) {
            labelBuilder.setName((String) data.get("name"));
        }

        if (data.containsKey("version")) {
            Object versionObj = data.get("version");
            if (versionObj instanceof Integer) {
                labelBuilder.setVersion((Integer) versionObj);
            } else if (versionObj instanceof String) {
                labelBuilder.setVersion(Integer.parseInt((String) versionObj));
            }
        }

        if (data.containsKey("visible_for")) {
            @SuppressWarnings("unchecked")
            List<String> visibleFor = (List<String>) data.get("visible_for");
            labelBuilder.addAllVisibleFor(visibleFor);
        }

        if (data.containsKey("remove_at")) {
            String removeAtStr = (String) data.get("remove_at");
            try {
                Instant removeAtInstant = Instant.parse(removeAtStr);
                Timestamp removeAtTimestamp = Timestamp.newBuilder()
                    .setSeconds(removeAtInstant.getEpochSecond())
                    .setNanos(removeAtInstant.getNano())
                    .build();
                labelBuilder.setRemoveAt(removeAtTimestamp);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to parse remove_at timestamp: " + removeAtStr, e);
            }
        }

        // Set definitions
        if (data.containsKey("definitions")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> definitions = (List<Map<String, Object>>) data.get("definitions");
            for (Map<String, Object> def : definitions) {
                LabelOuterClass.LabelDefinition.Builder defBuilder = LabelOuterClass.LabelDefinition.newBuilder();

                if (def.containsKey("driver")) {
                    defBuilder.setDriver((String) def.get("driver"));
                }

                // Add images
                if (def.containsKey("images")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> images = (List<Map<String, Object>>) def.get("images");
                    for (Map<String, Object> img : images) {
                        LabelOuterClass.Image.Builder imgBuilder = LabelOuterClass.Image.newBuilder();
                        if (img.containsKey("name")) {
                            imgBuilder.setName((String) img.get("name"));
                        }
                        if (img.containsKey("url")) {
                            imgBuilder.setUrl((String) img.get("url"));
                        }
                        if (img.containsKey("version")) {
                            imgBuilder.setVersion((String) img.get("version"));
                        }
                        if (img.containsKey("tag")) {
                            imgBuilder.setTag((String) img.get("tag"));
                        }
                        defBuilder.addImages(imgBuilder.build());
                    }
                }

                // Add options as Struct
                if (def.containsKey("options")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> options = (Map<String, Object>) def.get("options");
                    defBuilder.setOptions(mapToStruct(options));
                }

                // Add resources
                if (def.containsKey("resources")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resources = (Map<String, Object>) def.get("resources");
                    LabelOuterClass.Resources.Builder resBuilder = LabelOuterClass.Resources.newBuilder();

                    if (resources.containsKey("cpu")) {
                        Object cpuObj = resources.get("cpu");
                        if (cpuObj instanceof Integer) {
                            resBuilder.setCpu((Integer) cpuObj);
                        } else if (cpuObj instanceof String) {
                            resBuilder.setCpu(Integer.parseInt((String) cpuObj));
                        }
                    }

                    if (resources.containsKey("ram")) {
                        Object ramObj = resources.get("ram");
                        if (ramObj instanceof Integer) {
                            resBuilder.setRam((Integer) ramObj);
                        } else if (ramObj instanceof String) {
                            resBuilder.setRam(Integer.parseInt((String) ramObj));
                        }
                    }

                    if (resources.containsKey("network")) {
                        resBuilder.setNetwork((String) resources.get("network"));
                    }

                    if (resources.containsKey("lifetime")) {
                        resBuilder.setLifetime((String) resources.get("lifetime"));
                    }

                    defBuilder.setResources(resBuilder.build());
                }

                // Add authentication
                if (def.containsKey("authentication")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> authentication = (Map<String, Object>) def.get("authentication");
                    Common.Authentication.Builder authBuilder = Common.Authentication.newBuilder();

                    if (authentication.containsKey("username")) {
                        authBuilder.setUsername((String) authentication.get("username"));
                    }

                    if (authentication.containsKey("password")) {
                        authBuilder.setPassword((String) authentication.get("password"));
                    }

                    if (authentication.containsKey("key")) {
                        authBuilder.setKey((String) authentication.get("key"));
                    }

                    if (authentication.containsKey("port")) {
                        Object portObj = authentication.get("port");
                        if (portObj instanceof Integer) {
                            authBuilder.setPort((Integer) portObj);
                        } else if (portObj instanceof String) {
                            authBuilder.setPort(Integer.parseInt((String) portObj));
                        }
                    }

                    defBuilder.setAuthentication(authBuilder.build());
                }

                labelBuilder.addDefinitions(defBuilder.build());
            }
        }

        // Set metadata
        if (data.containsKey("metadata")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) data.get("metadata");
            labelBuilder.setMetadata(mapToStruct(metadata));
        }

        return labelBuilder.build();
    }

    private Struct mapToStruct(Map<String, Object> map) {
        Struct.Builder structBuilder = Struct.newBuilder();

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            structBuilder.putFields(entry.getKey(), objectToValue(entry.getValue()));
        }

        return structBuilder.build();
    }

    private Value objectToValue(Object obj) {
        Value.Builder valueBuilder = Value.newBuilder();

        if (obj == null) {
            valueBuilder.setNullValue(com.google.protobuf.NullValue.NULL_VALUE);
        } else if (obj instanceof String) {
            valueBuilder.setStringValue((String) obj);
        } else if (obj instanceof Integer) {
            valueBuilder.setNumberValue((Integer) obj);
        } else if (obj instanceof Long) {
            valueBuilder.setNumberValue((Long) obj);
        } else if (obj instanceof Float) {
            valueBuilder.setNumberValue((Float) obj);
        } else if (obj instanceof Double) {
            valueBuilder.setNumberValue((Double) obj);
        } else if (obj instanceof Boolean) {
            valueBuilder.setBoolValue((Boolean) obj);
        } else if (obj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) obj;
            valueBuilder.setListValue(listToListValue(list));
        } else if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) obj;
            valueBuilder.setStructValue(mapToStruct(map));
        } else {
            // Fallback for unknown types
            valueBuilder.setStringValue(obj.toString());
        }

        return valueBuilder.build();
    }

    private ListValue listToListValue(List<Object> list) {
        ListValue.Builder listBuilder = ListValue.newBuilder();

        for (Object item : list) {
            listBuilder.addValues(objectToValue(item));
        }

        return listBuilder.build();
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        LOGGER.log(Level.FINE, "Stopping Aquarium Create Label step.");
        super.stop(cause);
    }
}

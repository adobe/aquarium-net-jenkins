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

package com.adobe.ci.aquarium.net.util;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.ListValue;
import com.google.protobuf.Message;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import net.sf.json.JSONNull;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Utility methods to convert Protobuf messages to net.sf.json JSON structures.
 */
public final class ProtobufJsonUtil {
    private ProtobufJsonUtil() {}

    /**
     * Converts an entire Protobuf {@link Message} into a {@link JSONObject} recursively.
     * - Repeated fields become {@link JSONArray}
     * - Map fields become {@link JSONObject}
     * - Enums become their name strings
     * - Bytes become base64 strings
     * - google.protobuf.Struct/ListValue/Value are converted to their natural JSON shapes
     * - google.protobuf.Timestamp becomes an object with seconds and nanos
     * - google.protobuf.Any becomes an object with type_url and value (base64)
     */
    public static JSONObject toJson(Message message) {
        JSONObject jsonObject = new JSONObject();
        if (message == null) {
            return jsonObject;
        }

        // Special-cases for well-known types that map directly to JSON
        if (message instanceof Struct) {
            return structToJsonObject((Struct) message);
        }
        if (message instanceof ListValue) {
            JSONObject wrapper = new JSONObject();
            wrapper.put("values", listToJsonArray((ListValue) message));
            return wrapper;
        }
        if (message instanceof Value) {
            Object v = valueToJson((Value) message);
            JSONObject wrapper = new JSONObject();
            wrapper.put("value", v);
            return wrapper;
        }

        for (Map.Entry<FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
            FieldDescriptor field = entry.getKey();
            Object value = entry.getValue();
            String fieldName = field.getName();

            if (field.isRepeated()) {
                if (field.isMapField()) {
                    // Map fields are represented as a list of entry messages
                    JSONObject mapObject = new JSONObject();
                    @SuppressWarnings("unchecked")
                    List<Message> entries = (List<Message>) value;
                    for (Message mapEntry : entries) {
                        FieldDescriptor keyField = mapEntry.getDescriptorForType().findFieldByName("key");
                        FieldDescriptor valueField = mapEntry.getDescriptorForType().findFieldByName("value");
                        Object key = mapEntry.getField(keyField);
                        Object mapVal = convertSingularValue(valueField, mapEntry.getField(valueField));
                        mapObject.put(String.valueOf(key), mapVal);
                    }
                    jsonObject.put(fieldName, mapObject);
                } else {
                    JSONArray array = new JSONArray();
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) value;
                    for (Object listItem : list) {
                        array.add(convertSingularValue(field, listItem));
                    }
                    jsonObject.put(fieldName, array);
                }
            } else {
                jsonObject.put(fieldName, convertSingularValue(field, value));
            }
        }

        return jsonObject;
    }

    private static Object convertSingularValue(FieldDescriptor field, Object value) {
        if (value == null) {
            return JSONNull.getInstance();
        }

        switch (field.getJavaType()) {
            case INT:
            case LONG:
            case FLOAT:
            case DOUBLE:
            case BOOLEAN:
            case STRING:
                return value;
            case BYTE_STRING:
                return Base64.getEncoder().encodeToString(((ByteString) value).toByteArray());
            case ENUM:
                return field.getEnumType().findValueByNumber(((com.google.protobuf.Descriptors.EnumValueDescriptor) value).getNumber()).getName();
            case MESSAGE:
                Message msg = (Message) value;
                // Handle well-known types
                if (msg instanceof Struct) {
                    return structToJsonObject((Struct) msg);
                }
                if (msg instanceof ListValue) {
                    return listToJsonArray((ListValue) msg);
                }
                if (msg instanceof Value) {
                    return valueToJson((Value) msg);
                }
                if (msg instanceof Timestamp) {
                    Timestamp t = (Timestamp) msg;
                    return formatTimestampRfc3339(t);
                }
                if (msg instanceof Any) {
                    Any any = (Any) msg;
                    JSONObject anyObj = new JSONObject();
                    anyObj.put("type_url", any.getTypeUrl());
                    anyObj.put("value", Base64.getEncoder().encodeToString(any.getValue().toByteArray()));
                    return anyObj;
                }
                return toJson(msg);
            default:
                return JSONNull.getInstance();
        }
    }

    private static JSONObject structToJsonObject(Struct structValue) {
        JSONObject jsonObject = new JSONObject();
        if (structValue == null) {
            return jsonObject;
        }
        for (Map.Entry<String, Value> entry : structValue.getFieldsMap().entrySet()) {
            String key = entry.getKey();
            Object converted = valueToJson(entry.getValue());
            jsonObject.put(key, converted);
        }
        return jsonObject;
    }

    private static Object valueToJson(Value value) {
        if (value == null) {
            return JSONNull.getInstance();
        }
        switch (value.getKindCase()) {
            case NULL_VALUE:
                return JSONNull.getInstance();
            case BOOL_VALUE:
                return Boolean.valueOf(value.getBoolValue());
            case NUMBER_VALUE:
                return Double.valueOf(value.getNumberValue());
            case STRING_VALUE:
                return value.getStringValue();
            case LIST_VALUE:
                return listToJsonArray(value.getListValue());
            case STRUCT_VALUE:
                return structToJsonObject(value.getStructValue());
            case KIND_NOT_SET:
            default:
                return JSONNull.getInstance();
        }
    }

    private static JSONArray listToJsonArray(ListValue listValue) {
        JSONArray jsonArray = new JSONArray();
        if (listValue == null) {
            return jsonArray;
        }
        for (Value v : listValue.getValuesList()) {
            jsonArray.add(valueToJson(v));
        }
        return jsonArray;
    }

    public static String toYaml(Message message) {
        JSONObject json = toJson(message);
        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);
        return yaml.dump(json);
    }

    /**
     * Format a protobuf {@link Timestamp} as an RFC 3339 string in UTC.
     * Example: 2025-01-02T03:04:05Z or with fractional seconds when nanos present.
     */
    public static String formatTimestampRfc3339(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return formatTimestampRfc3339(timestamp.getSeconds(), timestamp.getNanos());
    }

    /**
     * Format epoch seconds and nanoseconds as an RFC 3339 string in UTC.
     */
    public static String formatTimestampRfc3339(long epochSeconds, int nanos) {
        Instant instant = Instant.ofEpochSecond(epochSeconds, nanos);
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}



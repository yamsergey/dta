package io.yamsergey.dta.workspace.kotlin.serializer;

import java.io.File;
import java.io.IOException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import io.yamsergey.dta.workspace.kotlin.model.Workspace;

/**
 * JSON serializer for Kotlin LSP workspace format.
 *
 * Handles serialization of Workspace objects to JSON format compatible
 * with Kotlin LSP workspace.json requirements.
 */
public class WorkspaceJsonSerializer {

    private final ObjectMapper objectMapper;

    public WorkspaceJsonSerializer() {
        this.objectMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
            .build();
    }

    /**
     * Serializes a Workspace to JSON string.
     *
     * @param workspace the workspace to serialize
     * @return JSON string representation
     * @throws IOException if serialization fails
     */
    public String toJson(Workspace workspace) throws IOException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(workspace);
    }

    /**
     * Serializes a Workspace to JSON file.
     *
     * @param workspace the workspace to serialize
     * @param outputFile the file to write to
     * @throws IOException if writing fails
     */
    public void toJsonFile(Workspace workspace, File outputFile) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, workspace);
    }

    /**
     * Deserializes JSON string to Workspace.
     *
     * @param json the JSON string to deserialize
     * @return Workspace object
     * @throws IOException if deserialization fails
     */
    public Workspace fromJson(String json) throws IOException {
        return objectMapper.readValue(json, Workspace.class);
    }

    /**
     * Deserializes JSON file to Workspace.
     *
     * @param jsonFile the JSON file to read
     * @return Workspace object
     * @throws IOException if reading fails
     */
    public Workspace fromJsonFile(File jsonFile) throws IOException {
        return objectMapper.readValue(jsonFile, Workspace.class);
    }
}
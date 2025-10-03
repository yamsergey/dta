package io.yamsergey.adt.tools.android.cli.serialization.jackson;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

/**
 * Jackson serializer modifier that filters properties based on include/exclude lists.
 *
 * When includeFields is set, only those fields are serialized (whitelist mode).
 * When excludeFields is set, all fields except those are serialized (blacklist mode).
 * When neither is set, all fields are serialized (no filtering).
 */
public class PropertyFilterSerializerModifier extends BeanSerializerModifier {

  private final Set<String> includeFields;
  private final Set<String> excludeFields;
  private final boolean isIncludeMode;

  private PropertyFilterSerializerModifier(List<String> includeFields, List<String> excludeFields) {
    if (includeFields != null) {
      this.includeFields = includeFields.stream()
          .map(String::toLowerCase)
          .collect(Collectors.toSet());
      this.excludeFields = null;
      this.isIncludeMode = true;
    } else if (excludeFields != null) {
      this.excludeFields = excludeFields.stream()
          .map(String::toLowerCase)
          .collect(Collectors.toSet());
      this.includeFields = null;
      this.isIncludeMode = false;
    } else {
      this.includeFields = null;
      this.excludeFields = null;
      this.isIncludeMode = false;
    }
  }

  /**
   * Creates a modifier that includes only the specified fields.
   */
  public static PropertyFilterSerializerModifier includeOnly(List<String> includeFields) {
    return new PropertyFilterSerializerModifier(includeFields, null);
  }

  /**
   * Creates a modifier that excludes the specified fields.
   */
  public static PropertyFilterSerializerModifier excludeFields(List<String> excludeFields) {
    return new PropertyFilterSerializerModifier(null, excludeFields);
  }

  /**
   * Creates a no-op modifier that allows all fields.
   */
  public static PropertyFilterSerializerModifier noFilter() {
    return new PropertyFilterSerializerModifier(null, null);
  }

  @Override
  public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
      BeanDescription beanDesc,
      List<BeanPropertyWriter> beanProperties) {

    // No filtering configured - return all properties
    if (includeFields == null && excludeFields == null) {
      return beanProperties;
    }

    List<BeanPropertyWriter> filteredProperties = new ArrayList<>();

    for (BeanPropertyWriter writer : beanProperties) {
      if (shouldInclude(writer.getName())) {
        filteredProperties.add(writer);
      }
    }

    return filteredProperties;
  }

  private boolean shouldInclude(String propertyName) {
    // No filtering configured - include everything
    if (includeFields == null && excludeFields == null) {
      return true;
    }

    String lowerPropertyName = propertyName.toLowerCase();

    if (isIncludeMode) {
      // Include mode: only include fields in the whitelist
      return includeFields.contains(lowerPropertyName);
    } else {
      // Exclude mode: include everything except fields in the blacklist
      return !excludeFields.contains(lowerPropertyName);
    }
  }
}

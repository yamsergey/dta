package io.yamsergey.dta.tools.android.cli.serialization.jackson;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

/**
 * Custom Jackson serializer modifier that gracefully handles properties
 * that throw exceptions during serialization (e.g., UnsupportedMethodException
 * from Gradle Tooling API proxy objects).
 *
 * When a property getter throws an exception, this modifier will serialize
 * the property as null instead of failing the entire serialization.
 */
public class SafeSerializerModifier extends ValueSerializerModifier {

  @Override
  public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
      BeanDescription.Supplier beanDesc,
      List<BeanPropertyWriter> beanProperties) {
    List<BeanPropertyWriter> modifiedProperties = new ArrayList<>();

    for (BeanPropertyWriter writer : beanProperties) {
      modifiedProperties.add(new SafePropertyWriter(writer));
    }

    return modifiedProperties;
  }

  /**
   * Wrapper for BeanPropertyWriter that catches and suppresses exceptions
   * during property serialization.
   */
  private static class SafePropertyWriter extends BeanPropertyWriter {

    public SafePropertyWriter(BeanPropertyWriter base) {
      super(base);
    }

    @Override
    public void serializeAsProperty(Object bean, JsonGenerator gen, SerializationContext ctxt) throws Exception {
      try {
        super.serializeAsProperty(bean, gen, ctxt);
      } catch (Exception e) {
        // If serialization fails (e.g., UnsupportedMethodException),
        // write null instead
        gen.writeName(getName());
        gen.writeNull();
      }
    }
  }
}

package io.yamsergey.adt.tools.android.cli.serialization.jackson;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

/**
 * Custom Jackson serializer modifier that gracefully handles properties
 * that throw exceptions during serialization (e.g., UnsupportedMethodException
 * from Gradle Tooling API proxy objects).
 *
 * When a property getter throws an exception, this modifier will serialize
 * the property as null instead of failing the entire serialization.
 */
public class SafeSerializerModifier extends BeanSerializerModifier {

  @Override
  public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
      BeanDescription beanDesc,
      List<BeanPropertyWriter> beanProperties) {
    List<BeanPropertyWriter> modifiedProperties = new ArrayList<>();

    for (BeanPropertyWriter writer : beanProperties) {
      modifiedProperties.add(new SafeBeanPropertyWriter(writer));
    }

    return modifiedProperties;
  }

  /**
   * Wrapper for BeanPropertyWriter that catches and suppresses exceptions
   * during property serialization.
   */
  private static class SafeBeanPropertyWriter extends BeanPropertyWriter {

    public SafeBeanPropertyWriter(BeanPropertyWriter base) {
      super(base);
    }

    @Override
    public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
      try {
        super.serializeAsField(bean, gen, prov);
      } catch (Exception e) {
        // If serialization fails (e.g., UnsupportedMethodException),
        // write null instead
        gen.writeFieldName(getName());
        gen.writeNull();
      }
    }
  }
}

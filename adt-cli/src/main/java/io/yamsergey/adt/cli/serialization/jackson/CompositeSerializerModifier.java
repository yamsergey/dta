package io.yamsergey.adt.cli.serialization.jackson;

import java.util.List;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

/**
 * Composite serializer modifier that chains multiple modifiers together.
 * Each modifier is applied in sequence to the list of properties.
 */
public class CompositeSerializerModifier extends BeanSerializerModifier {

  private final List<BeanSerializerModifier> modifiers;

  public CompositeSerializerModifier(List<BeanSerializerModifier> modifiers) {
    this.modifiers = modifiers;
  }

  @Override
  public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
      BeanDescription beanDesc,
      List<BeanPropertyWriter> beanProperties) {

    List<BeanPropertyWriter> result = beanProperties;

    // Apply each modifier in sequence
    for (BeanSerializerModifier modifier : modifiers) {
      result = modifier.changeProperties(config, beanDesc, result);
    }

    return result;
  }
}

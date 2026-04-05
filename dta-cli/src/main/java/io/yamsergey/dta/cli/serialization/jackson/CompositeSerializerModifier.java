package io.yamsergey.dta.cli.serialization.jackson;

import java.util.List;

import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

/**
 * Composite serializer modifier that chains multiple modifiers together.
 * Each modifier is applied in sequence to the list of properties.
 */
public class CompositeSerializerModifier extends ValueSerializerModifier {

  private final List<ValueSerializerModifier> modifiers;

  public CompositeSerializerModifier(List<ValueSerializerModifier> modifiers) {
    this.modifiers = modifiers;
  }

  @Override
  public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
      BeanDescription.Supplier beanDesc,
      List<BeanPropertyWriter> beanProperties) {

    List<BeanPropertyWriter> result = beanProperties;

    // Apply each modifier in sequence
    for (ValueSerializerModifier modifier : modifiers) {
      result = modifier.changeProperties(config, beanDesc, result);
    }

    return result;
  }
}

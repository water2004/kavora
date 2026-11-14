/* SPDX-License-Identifier: MIT */
module material_effects;

// Compile-time composition shared by material evaluation stages.
public interface IMaterialEffect<Inputs, Output> {
  static void apply(Inputs inputs, inout Output output);
};

[ForceInline]
public void applyMaterialEffects<Inputs, Output, each Effect : IMaterialEffect<Inputs, Output>>(
  Inputs inputs, inout Output output) {
  expand (each Effect).apply(inputs, output);
}

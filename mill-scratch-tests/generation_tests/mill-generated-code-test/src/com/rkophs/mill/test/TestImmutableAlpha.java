package com.rkophs.mill.test;

import org.immutables.value.Value;

@Value.Immutable
public interface TestImmutableAlpha {
    int getSomeInteger();
    ImmutableTestImmutableBeta getBeta();
}

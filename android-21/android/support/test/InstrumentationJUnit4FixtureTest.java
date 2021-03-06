/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.support.test;

import android.support.test.InjectInstrumentation;
import android.util.Log;

import junit.framework.Assert;

import org.junit.Test;

/**
 * Placeholder test to verify {@link InjectInstrumentation} for fixtures.
 */
public class InstrumentationJUnit4FixtureTest extends InstrumentationJUnit4Fixture {

    public InstrumentationJUnit4FixtureTest() {
        Log.d("InstrumentationJUnit4FixtureTest", "I'm created");
    }

    /**
     * Verify that the instrumentation in the fixture class has been correctly injected.
     */
    @Test
    public void verifyInstrumentationInjected() {
        Assert.assertNotNull(mInstrumentation);
    }
}

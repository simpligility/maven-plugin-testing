package org.apache.maven.shared.tools.easymock;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.List;

import org.easymock.MockControl;

/**
 * Manager of MockControl
 *
 * @version $Id: MockManager.java 1345444 2012-06-02 05:36:05Z hboutemy $
 * @see MockControl
 */
public class MockManager
{
    private List<MockControl> mockControls = new ArrayList<MockControl>();

    /**
     * @param control to be add to the manager
     */
    public void add( MockControl control )
    {
        mockControls.add( control );
    }

    /**
     * Clear all controls from the manager
     */
    public void clear()
    {
        mockControls.clear();
    }

    /**
     * @see MockControl#replay()
     */
    public void replayAll()
    {
        for ( MockControl control : mockControls )
        {
            control.replay();
        }
    }

    /**
     * @see MockControl#verify()
     */
    public void verifyAll()
    {
        for ( MockControl control : mockControls )
        {
            control.verify();
        }
    }
}

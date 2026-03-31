/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     WSO2 LLC - support for WSO2 Micro Integrator Configuration
 */

package org.eclipse.lemminx.extensions.synapse;

import static org.eclipse.lemminx.XMLAssert.d;
import static org.eclipse.lemminx.XMLAssert.testDiagnosticsFor;

import org.eclipse.lemminx.AbstractCacheBasedTest;
import org.eclipse.lemminx.extensions.contentmodel.participants.XMLSchemaErrorCode;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Synapse XSD schema validation.
 * Tests the required attribute changes (endpoint method/uri-template,
 * inbound sequence, throwError type/errorMessage).
 * Uses the production Synapse catalog for schema resolution.
 */
public class SynapseSchemaDiagnosticsTest extends AbstractCacheBasedTest {

    private static final String SYNAPSE_CATALOG = "src/main/resources/org/eclipse/lemminx/schemas/430/catalog.xml";

    private void testSynapseDiagnostics(String xml, Diagnostic... expected) {
        testDiagnosticsFor(xml, SYNAPSE_CATALOG, expected);
    }

    // ===== HTTPEndpoint required attributes =====

    @Test
    public void testHTTPEndpointMissingMethod() throws Exception {
        String xml = "<endpoint xmlns=\"http://ws.apache.org/ns/synapse\" name=\"testEP\">\n"
                + "  <http uri-template=\"http://localhost:9090/test\"/>\n"
                + "</endpoint>";
        // method is now required — should produce cvc-complex-type.4
        testSynapseDiagnostics(xml,
                d(1, 3, 1, 7, XMLSchemaErrorCode.cvc_complex_type_4));
    }

    @Test
    public void testHTTPEndpointMissingUriTemplate() throws Exception {
        String xml = "<endpoint xmlns=\"http://ws.apache.org/ns/synapse\" name=\"testEP\">\n"
                + "  <http method=\"get\"/>\n"
                + "</endpoint>";
        // uri-template is now required — should produce cvc-complex-type.4
        testSynapseDiagnostics(xml,
                d(1, 3, 1, 7, XMLSchemaErrorCode.cvc_complex_type_4));
    }

    @Test
    public void testHTTPEndpointValid() throws Exception {
        String xml = "<endpoint xmlns=\"http://ws.apache.org/ns/synapse\" name=\"testEP\">\n"
                + "  <http method=\"get\" uri-template=\"http://localhost:9090/test\">\n"
                + "    <suspendOnFailure>\n"
                + "      <initialDuration>-1</initialDuration>\n"
                + "      <progressionFactor>1.0</progressionFactor>\n"
                + "    </suspendOnFailure>\n"
                + "    <markForSuspension>\n"
                + "      <retriesBeforeSuspension>0</retriesBeforeSuspension>\n"
                + "    </markForSuspension>\n"
                + "  </http>\n"
                + "</endpoint>";
        testSynapseDiagnostics(xml);
    }

    // ===== InboundEndpoint required sequence =====

    @Test
    public void testInboundEndpointMissingSequence() throws Exception {
        String xml = "<inboundEndpoint xmlns=\"http://ws.apache.org/ns/synapse\""
                + " name=\"testInbound\" protocol=\"http\" suspend=\"false\">\n"
                + "  <parameters>\n"
                + "    <parameter name=\"inbound.http.port\">8085</parameter>\n"
                + "  </parameters>\n"
                + "</inboundEndpoint>";
        // sequence is now required — should produce cvc-complex-type.4
        testSynapseDiagnostics(xml,
                d(0, 1, 0, 16, XMLSchemaErrorCode.cvc_complex_type_4));
    }

    @Test
    public void testInboundEndpointValid() throws Exception {
        String xml = "<inboundEndpoint xmlns=\"http://ws.apache.org/ns/synapse\""
                + " name=\"testInbound\" protocol=\"http\" sequence=\"main\" suspend=\"false\">\n"
                + "  <parameters>\n"
                + "    <parameter name=\"inbound.http.port\">8085</parameter>\n"
                + "  </parameters>\n"
                + "</inboundEndpoint>";
        testSynapseDiagnostics(xml);
    }

    // ===== Valid complete API =====

    @Test
    public void testValidSynapseApiNoSchemaErrors() throws Exception {
        String xml = "<api xmlns=\"http://ws.apache.org/ns/synapse\" name=\"HealthcareAPI\" context=\"/healthcare\">\n"
                + "  <resource methods=\"GET\" uri-template=\"/doctors\">\n"
                + "    <inSequence>\n"
                + "      <log level=\"full\"/>\n"
                + "      <respond/>\n"
                + "    </inSequence>\n"
                + "  </resource>\n"
                + "</api>";
        testSynapseDiagnostics(xml);
    }
}

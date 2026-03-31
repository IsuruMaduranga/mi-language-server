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

import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMParser;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for SynapseDiagnosticsParticipant: semantic validation rules for
 * Synapse XML that XSD cannot express.
 */
public class SynapseDiagnosticsParticipantTest {

    private static final String SYNAPSE_NS = "http://ws.apache.org/ns/synapse";

    /**
     * Parses XML and runs the SynapseDiagnosticsParticipant, returning all diagnostics.
     */
    private List<Diagnostic> diagnose(String xml) {
        TextDocument textDocument = new TextDocument(xml, "test.xml");
        DOMDocument document = DOMParser.getInstance().parse(textDocument, null);
        List<Diagnostic> diagnostics = new ArrayList<>();
        SynapseDiagnosticsParticipant participant = new SynapseDiagnosticsParticipant();
        participant.doDiagnostics(document, diagnostics, null, () -> {});
        return diagnostics;
    }

    private List<Diagnostic> diagnosticsWithCode(List<Diagnostic> diagnostics, String code) {
        return diagnostics.stream()
                .filter(d -> d.getCode() != null && code.equals(d.getCode().getLeft()))
                .collect(Collectors.toList());
    }

    private String synapseWrap(String inner) {
        return "<sequence xmlns=\"" + SYNAPSE_NS + "\" name=\"test\">" + inner + "</sequence>";
    }

    // ===== API Resource validation =====

    @Test
    public void testAPIResourceMissingBothAttributes() {
        String xml = "<api xmlns=\"" + SYNAPSE_NS + "\" name=\"TestAPI\" context=\"/test\">"
                + "<resource methods=\"GET\"><inSequence><respond/></inSequence></resource>"
                + "</api>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "ResourceMissingUriTemplateOrUrlMapping");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Error, diags.get(0).getSeverity());
    }

    @Test
    public void testAPIResourceWithUriTemplate() {
        String xml = "<api xmlns=\"" + SYNAPSE_NS + "\" name=\"TestAPI\" context=\"/test\">"
                + "<resource methods=\"GET\" uri-template=\"/foo\"><inSequence><respond/></inSequence></resource>"
                + "</api>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "ResourceMissingUriTemplateOrUrlMapping");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testAPIResourceWithUrlMapping() {
        String xml = "<api xmlns=\"" + SYNAPSE_NS + "\" name=\"TestAPI\" context=\"/test\">"
                + "<resource methods=\"GET\" url-mapping=\"/foo\"><inSequence><respond/></inSequence></resource>"
                + "</api>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "ResourceMissingUriTemplateOrUrlMapping");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testResourceNotUnderApiSkipped() {
        // <resource> not under <api> should not trigger the check
        String xml = "<proxy xmlns=\"" + SYNAPSE_NS + "\" name=\"TestProxy\">"
                + "<resource methods=\"GET\"/>"
                + "</proxy>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "ResourceMissingUriTemplateOrUrlMapping");
        assertTrue(diags.isEmpty());
    }

    // ===== Filter mediator validation =====

    @Test
    public void testFilterMissingCondition() {
        String xml = synapseWrap("<filter><then><respond/></then></filter>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "FilterMissingCondition");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Error, diags.get(0).getSeverity());
    }

    @Test
    public void testFilterWithSourceAndRegex() {
        String xml = synapseWrap("<filter source=\"get-property('type')\" regex=\"premium\">"
                + "<then><respond/></then></filter>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "FilterMissingCondition");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testFilterWithXpath() {
        String xml = synapseWrap("<filter xpath=\"//name\">"
                + "<then><respond/></then></filter>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "FilterMissingCondition");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testFilterWithSourceButNoRegex() {
        String xml = synapseWrap("<filter source=\"get-property('type')\">"
                + "<then><respond/></then></filter>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "FilterMissingCondition");
        assertEquals(1, diags.size());
    }

    // ===== Property mediator validation =====

    @Test
    public void testPropertySetMissingValue() {
        String xml = synapseWrap("<property name=\"foo\" scope=\"synapse\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "PropertySetMissingValue");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Warning, diags.get(0).getSeverity());
    }

    @Test
    public void testPropertySetWithValue() {
        String xml = synapseWrap("<property name=\"foo\" value=\"bar\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "PropertySetMissingValue");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testPropertySetWithExpression() {
        String xml = synapseWrap("<property name=\"foo\" expression=\"${payload.x}\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "PropertySetMissingValue");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testPropertyRemoveAction() {
        String xml = synapseWrap("<property name=\"foo\" action=\"remove\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "PropertySetMissingValue");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testPropertyInsideLogSkipped() {
        String xml = synapseWrap("<log level=\"custom\"><property name=\"msg\" value=\"test\"/></log>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "PropertySetMissingValue");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testPropertyWithInlineXmlChild() {
        String xml = synapseWrap("<property name=\"foo\"><inline>content</inline></property>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "PropertySetMissingValue");
        assertTrue(diags.isEmpty());
    }

    // ===== Header mediator validation =====

    @Test
    public void testHeaderSetMissingValue() {
        String xml = synapseWrap("<header name=\"Content-Type\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "HeaderSetMissingValue");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Warning, diags.get(0).getSeverity());
    }

    @Test
    public void testHeaderSetWithValue() {
        String xml = synapseWrap("<header name=\"Content-Type\" value=\"text/xml\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "HeaderSetMissingValue");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testHeaderRemoveAction() {
        String xml = synapseWrap("<header name=\"Content-Type\" action=\"remove\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "HeaderSetMissingValue");
        assertTrue(diags.isEmpty());
    }

    // ===== Log mediator validation =====

    @Test
    public void testLogCustomMissingProperties() {
        String xml = synapseWrap("<log level=\"custom\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "LogCustomMissingProperties");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Warning, diags.get(0).getSeverity());
    }

    @Test
    public void testLogCustomWithProperty() {
        String xml = synapseWrap("<log level=\"custom\"><property name=\"msg\" value=\"test\"/></log>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "LogCustomMissingProperties");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testLogNonCustomLevelNoWarning() {
        String xml = synapseWrap("<log level=\"full\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "LogCustomMissingProperties");
        assertTrue(diags.isEmpty());
    }

    // ===== Switch mediator validation =====

    @Test
    public void testSwitchDuplicateCaseRegex() {
        String xml = synapseWrap("<switch source=\"get-property('type')\">"
                + "<case regex=\"foo\"><respond/></case>"
                + "<case regex=\"foo\"><respond/></case>"
                + "</switch>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "DuplicateSwitchCase");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Warning, diags.get(0).getSeverity());
    }

    @Test
    public void testSwitchUniqueCaseRegex() {
        String xml = synapseWrap("<switch source=\"get-property('type')\">"
                + "<case regex=\"foo\"><respond/></case>"
                + "<case regex=\"bar\"><respond/></case>"
                + "</switch>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "DuplicateSwitchCase");
        assertTrue(diags.isEmpty());
    }

    // ===== Inbound endpoint validation =====

    @Test
    public void testInboundMissingProtocolAndClass() {
        String xml = "<inboundEndpoint xmlns=\"" + SYNAPSE_NS + "\" name=\"test\" sequence=\"main\" suspend=\"false\"/>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "InboundMissingProtocolOrClass");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Error, diags.get(0).getSeverity());
    }

    @Test
    public void testInboundWithProtocol() {
        String xml = "<inboundEndpoint xmlns=\"" + SYNAPSE_NS
                + "\" name=\"test\" protocol=\"http\" sequence=\"main\" suspend=\"false\"/>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "InboundMissingProtocolOrClass");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testInboundWithClass() {
        String xml = "<inboundEndpoint xmlns=\"" + SYNAPSE_NS
                + "\" name=\"test\" class=\"com.example.Custom\" sequence=\"main\" suspend=\"false\"/>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "InboundMissingProtocolOrClass");
        assertTrue(diags.isEmpty());
    }

    // ===== Enrich source validation =====

    @Test
    public void testEnrichSourceCustomMissingXpath() {
        String xml = synapseWrap("<enrich><source type=\"custom\"/><target type=\"body\"/></enrich>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "EnrichSourceCustomMissingXpath");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Error, diags.get(0).getSeverity());
    }

    @Test
    public void testEnrichSourceCustomWithXpath() {
        String xml = synapseWrap("<enrich><source type=\"custom\" xpath=\"//foo\"/><target type=\"body\"/></enrich>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "EnrichSourceCustomMissingXpath");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testEnrichSourceDefaultTypeIsCustom() {
        // No type attr defaults to "custom", so xpath is required
        String xml = synapseWrap("<enrich><source/><target type=\"body\"/></enrich>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "EnrichSourceCustomMissingXpath");
        assertEquals(1, diags.size());
    }

    @Test
    public void testEnrichSourcePropertyMissingProperty() {
        String xml = synapseWrap("<enrich><source type=\"property\"/><target type=\"body\"/></enrich>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "EnrichSourcePropertyMissingProperty");
        assertEquals(1, diags.size());
    }

    @Test
    public void testEnrichSourceInlineMissingContent() {
        String xml = synapseWrap("<enrich><source type=\"inline\"/><target type=\"body\"/></enrich>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "EnrichSourceInlineMissingContent");
        assertEquals(1, diags.size());
    }

    @Test
    public void testEnrichSourceInlineWithChild() {
        String xml = synapseWrap("<enrich><source type=\"inline\"><foo/></source><target type=\"body\"/></enrich>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "EnrichSourceInlineMissingContent");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testEnrichSourceInlineWithJsonText() {
        // M5 fix: inline with JSON text content should not be flagged
        String xml = synapseWrap(
                "<enrich><source type=\"inline\">{\"key\": \"value\"}</source><target type=\"body\"/></enrich>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "EnrichSourceInlineMissingContent");
        assertTrue(diags.isEmpty(), "Inline source with JSON text content should be valid");
    }

    @Test
    public void testSourceNotInsideEnrichSkipped() {
        // <source> not under <enrich> should not trigger enrich validation
        String xml = synapseWrap("<mediator><source type=\"custom\"/></mediator>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "EnrichSourceCustomMissingXpath");
        assertTrue(diags.isEmpty());
    }

    // ===== Unreachable code detection =====

    @Test
    public void testUnreachableCodeAfterRespond() {
        String xml = synapseWrap("<respond/><log level=\"full\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "UnreachableCode");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Warning, diags.get(0).getSeverity());
        assertTrue(diags.get(0).getMessage().contains("respond"));
    }

    @Test
    public void testUnreachableCodeAfterDrop() {
        String xml = synapseWrap("<drop/><property name=\"x\" value=\"y\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "UnreachableCode");
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).getMessage().contains("drop"));
    }

    @Test
    public void testNoUnreachableCodeBeforeTerminal() {
        String xml = synapseWrap("<log level=\"full\"/><respond/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "UnreachableCode");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testMultipleUnreachableMediators() {
        String xml = synapseWrap("<respond/><log level=\"full\"/><property name=\"x\" value=\"y\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "UnreachableCode");
        assertEquals(2, diags.size(), "Both mediators after respond should be flagged");
    }

    @Test
    public void testUnreachableCodeAfterLoopback() {
        String xml = synapseWrap("<loopback/><log level=\"full\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "UnreachableCode");
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).getMessage().contains("loopback"));
    }

    // ===== Missing Synapse namespace =====

    @Test
    public void testMissingSynapseNamespaceWarning() {
        // Synapse root element without xmlns
        String xml = "<api name=\"TestAPI\" context=\"/test\"/>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "MissingSynapseNamespace");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Warning, diags.get(0).getSeverity());
    }

    @Test
    public void testNonSynapseRootElementNoWarning() {
        // Not a Synapse root element name — should not trigger warning
        String xml = "<beans/>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "MissingSynapseNamespace");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testCorrectNamespaceNoWarning() {
        String xml = "<api xmlns=\"" + SYNAPSE_NS + "\" name=\"TestAPI\" context=\"/test\"/>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "MissingSynapseNamespace");
        assertTrue(diags.isEmpty());
    }

    // ===== Variable reference validation =====

    @Test
    public void testUndefinedVariableWarning() {
        String xml = "<sequence xmlns=\"" + SYNAPSE_NS + "\" name=\"test\">"
                + "<log level=\"custom\"><property name=\"x\" expression=\"${vars.myVar}\"/></log>"
                + "</sequence>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "UndefinedVariable");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Warning, diags.get(0).getSeverity());
        assertTrue(diags.get(0).getMessage().contains("myVar"));
    }

    @Test
    public void testDefinedVariableNoWarning() {
        String xml = "<sequence xmlns=\"" + SYNAPSE_NS + "\" name=\"test\">"
                + "<variable name=\"myVar\" value=\"test\"/>"
                + "<log level=\"custom\"><property name=\"x\" expression=\"${vars.myVar}\"/></log>"
                + "</sequence>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "UndefinedVariable");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testPropertyScopeDefaultDefinesVariable() {
        String xml = "<sequence xmlns=\"" + SYNAPSE_NS + "\" name=\"test\">"
                + "<property name=\"myVar\" scope=\"default\" value=\"x\"/>"
                + "<log level=\"custom\"><property name=\"x\" expression=\"${vars.myVar}\"/></log>"
                + "</sequence>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "UndefinedVariable");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testVariableRemoveActionDoesNotDefine() {
        String xml = "<sequence xmlns=\"" + SYNAPSE_NS + "\" name=\"test\">"
                + "<variable name=\"myVar\" action=\"remove\"/>"
                + "<log level=\"custom\"><property name=\"x\" expression=\"${vars.myVar}\"/></log>"
                + "</sequence>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "UndefinedVariable");
        assertEquals(1, diags.size(), "Variable with action=remove should not count as defined");
    }

    // ===== Non-Synapse document skipping =====

    @Test
    public void testNonSynapseDocumentSkipped() {
        String xml = "<beans xmlns=\"http://www.springframework.org/schema/beans\"/>";
        List<Diagnostic> diags = diagnose(xml);
        assertTrue(diags.isEmpty(), "Non-Synapse document should produce zero diagnostics");
    }

    @Test
    public void testWrongNamespaceSkipped() {
        String xml = "<api xmlns=\"http://example.com/wrong\" name=\"test\" context=\"/test\"/>";
        List<Diagnostic> diags = diagnose(xml);
        assertTrue(diags.isEmpty(), "Wrong namespace should produce zero diagnostics");
    }

    // ===== P0-1: with-param value or expression =====

    @Test
    public void testWithParamMissingBothValueAndExpression() {
        String xml = synapseWrap("<call-template target=\"MyTemplate\">"
                + "<with-param name=\"ep\"/>"
                + "</call-template>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "WithParamMissingValueOrExpression");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Error, diags.get(0).getSeverity());
        assertTrue(diags.get(0).getMessage().contains("'ep'"));
    }

    @Test
    public void testWithParamWithValue() {
        String xml = synapseWrap("<call-template target=\"MyTemplate\">"
                + "<with-param name=\"ep\" value=\"HospitalEP\"/>"
                + "</call-template>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "WithParamMissingValueOrExpression");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testWithParamWithExpression() {
        String xml = synapseWrap("<call-template target=\"MyTemplate\">"
                + "<with-param name=\"ep\" expression=\"${vars.ep}\"/>"
                + "</call-template>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "WithParamMissingValueOrExpression");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testMultipleWithParamsMixedValidity() {
        String xml = synapseWrap("<call-template target=\"MyTemplate\">"
                + "<with-param name=\"good\" value=\"ok\"/>"
                + "<with-param name=\"bad\"/>"
                + "</call-template>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "WithParamMissingValueOrExpression");
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).getMessage().contains("'bad'"));
    }

    // ===== P0-2: Task trigger scheduling =====

    @Test
    public void testTriggerMissingSchedule() {
        String xml = "<task xmlns=\"" + SYNAPSE_NS + "\" name=\"MyTask\" class=\"com.example.Task\" group=\"synapse.simple.quartz\">"
                + "<trigger/>"
                + "</task>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "TriggerMissingSchedule");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Error, diags.get(0).getSeverity());
    }

    @Test
    public void testTriggerWithInterval() {
        String xml = "<task xmlns=\"" + SYNAPSE_NS + "\" name=\"MyTask\" class=\"com.example.Task\" group=\"synapse.simple.quartz\">"
                + "<trigger interval=\"5\"/>"
                + "</task>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "TriggerMissingSchedule");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testTriggerWithCron() {
        String xml = "<task xmlns=\"" + SYNAPSE_NS + "\" name=\"MyTask\" class=\"com.example.Task\" group=\"synapse.simple.quartz\">"
                + "<trigger cron=\"0 0/5 * * * ?\"/>"
                + "</task>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "TriggerMissingSchedule");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testTriggerWithOnce() {
        String xml = "<task xmlns=\"" + SYNAPSE_NS + "\" name=\"MyTask\" class=\"com.example.Task\" group=\"synapse.simple.quartz\">"
                + "<trigger once=\"true\"/>"
                + "</task>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "TriggerMissingSchedule");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testTriggerOutsideTaskNotValidated() {
        // <trigger> not inside <task> should be ignored
        String xml = synapseWrap("<trigger/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "TriggerMissingSchedule");
        assertTrue(diags.isEmpty());
    }

    // ===== P0-5: DB parameter value or expression =====

    @Test
    public void testDbParameterMissingValue() {
        String xml = synapseWrap("<dblookup>"
                + "<connection/>"
                + "<statement>"
                + "<sql>SELECT * FROM users WHERE id = ?</sql>"
                + "<parameter type=\"INTEGER\"/>"
                + "</statement>"
                + "</dblookup>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "DbParameterMissingValue");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Error, diags.get(0).getSeverity());
    }

    @Test
    public void testDbParameterWithValue() {
        String xml = synapseWrap("<dblookup>"
                + "<connection/>"
                + "<statement>"
                + "<sql>SELECT * FROM users WHERE id = ?</sql>"
                + "<parameter type=\"INTEGER\" value=\"1\"/>"
                + "</statement>"
                + "</dblookup>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "DbParameterMissingValue");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testDbParameterWithExpression() {
        String xml = synapseWrap("<dblookup>"
                + "<connection/>"
                + "<statement>"
                + "<sql>SELECT * FROM users WHERE id = ?</sql>"
                + "<parameter type=\"INTEGER\" expression=\"${payload.id}\"/>"
                + "</statement>"
                + "</dblookup>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "DbParameterMissingValue");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testDbParameterOutsideStatementNotValidated() {
        // <parameter> under <parameters> (inbound endpoint) should be ignored
        String xml = "<inboundEndpoint xmlns=\"" + SYNAPSE_NS + "\" name=\"test\" protocol=\"http\" sequence=\"testSeq\">"
                + "<parameters>"
                + "<parameter name=\"inbound.http.port\">8085</parameter>"
                + "</parameters>"
                + "</inboundEndpoint>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "DbParameterMissingValue");
        assertTrue(diags.isEmpty());
    }

    // ===== P0-6: PayloadFactory args mismatch =====

    @Test
    public void testPayloadFactoryArgsMismatch() {
        String xml = synapseWrap("<payloadFactory media-type=\"json\">"
                + "<format>{\"name\": \"$1\", \"age\": \"$2\", \"city\": \"$3\"}</format>"
                + "<args>"
                + "<arg expression=\"${payload.name}\"/>"
                + "<arg expression=\"${payload.age}\"/>"
                + "</args>"
                + "</payloadFactory>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "PayloadFactoryArgsMismatch");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Warning, diags.get(0).getSeverity());
        assertTrue(diags.get(0).getMessage().contains("$3"));
        assertTrue(diags.get(0).getMessage().contains("2 arg(s)"));
    }

    @Test
    public void testPayloadFactoryArgsMatch() {
        String xml = synapseWrap("<payloadFactory media-type=\"json\">"
                + "<format>{\"name\": \"$1\", \"age\": \"$2\"}</format>"
                + "<args>"
                + "<arg expression=\"${payload.name}\"/>"
                + "<arg expression=\"${payload.age}\"/>"
                + "</args>"
                + "</payloadFactory>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "PayloadFactoryArgsMismatch");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testPayloadFactoryNoPlaceholders() {
        String xml = synapseWrap("<payloadFactory media-type=\"json\">"
                + "<format>{\"status\": \"ok\"}</format>"
                + "<args/>"
                + "</payloadFactory>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "PayloadFactoryArgsMismatch");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testPayloadFactoryFreemarkerSkipped() {
        String xml = synapseWrap("<payloadFactory media-type=\"json\" template-type=\"freemarker\">"
                + "<format>{\"name\": \"$1\"}</format>"
                + "</payloadFactory>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "PayloadFactoryArgsMismatch");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testPayloadFactoryNoArgsElement() {
        String xml = synapseWrap("<payloadFactory media-type=\"json\">"
                + "<format>{\"name\": \"$1\", \"age\": \"$2\"}</format>"
                + "</payloadFactory>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "PayloadFactoryArgsMismatch");
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).getMessage().contains("$2"));
        assertTrue(diags.get(0).getMessage().contains("0 arg(s)"));
    }

    @Test
    public void testPayloadFactoryExtraArgsNoWarning() {
        // Extra args are not a problem — only missing args for used placeholders
        String xml = synapseWrap("<payloadFactory media-type=\"json\">"
                + "<format>{\"name\": \"$1\"}</format>"
                + "<args>"
                + "<arg expression=\"${payload.name}\"/>"
                + "<arg expression=\"${payload.extra}\"/>"
                + "</args>"
                + "</payloadFactory>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "PayloadFactoryArgsMismatch");
        assertTrue(diags.isEmpty());
    }

    // ===== P1-11: Enrich target-source compatibility =====

    @Test
    public void testEnrichBodyToBodyChild() {
        String xml = synapseWrap("<enrich>"
                + "<source type=\"body\"/>"
                + "<target type=\"body\" action=\"child\"/>"
                + "</enrich>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "EnrichCircularBodyReference");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Warning, diags.get(0).getSeverity());
    }

    @Test
    public void testEnrichBodyToBodyReplace() {
        // Replace (default action) is valid — only child/sibling is circular
        String xml = synapseWrap("<enrich>"
                + "<source type=\"body\"/>"
                + "<target type=\"body\"/>"
                + "</enrich>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "EnrichCircularBodyReference");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testEnrichEnvelopeToProperty() {
        String xml = synapseWrap("<enrich>"
                + "<source type=\"envelope\"/>"
                + "<target type=\"property\" property=\"myProp\"/>"
                + "</enrich>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "EnrichIncompatibleSourceTarget");
        assertEquals(1, diags.size());
    }

    @Test
    public void testEnrichValidCombination() {
        String xml = synapseWrap("<enrich>"
                + "<source type=\"custom\" xpath=\"//result\"/>"
                + "<target type=\"body\"/>"
                + "</enrich>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "EnrichCircularBodyReference");
        assertTrue(diags.isEmpty());
        diags = diagnosticsWithCode(diagnose(xml), "EnrichIncompatibleSourceTarget");
        assertTrue(diags.isEmpty());
    }

    // ===== P1-12: Throttle mediator needs policy =====

    @Test
    public void testThrottleMissingPolicy() {
        String xml = synapseWrap("<throttle id=\"my-throttle\">"
                + "<onAccept><log/></onAccept>"
                + "<onReject><drop/></onReject>"
                + "</throttle>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "ThrottleMissingPolicy");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Warning, diags.get(0).getSeverity());
    }

    @Test
    public void testThrottleWithPolicyKey() {
        String xml = synapseWrap("<throttle id=\"my-throttle\">"
                + "<policy key=\"conf:/throttle-policy.xml\"/>"
                + "<onAccept><log/></onAccept>"
                + "<onReject><drop/></onReject>"
                + "</throttle>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "ThrottleMissingPolicy");
        assertTrue(diags.isEmpty());
    }

    // ===== P1-13: Clone/iterate target must have sequence or endpoint =====

    @Test
    public void testCloneTargetEmpty() {
        String xml = synapseWrap("<clone>"
                + "<target to=\"http://example.com\"/>"
                + "</clone>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "CloneIterateTargetEmpty");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Warning, diags.get(0).getSeverity());
    }

    @Test
    public void testCloneTargetWithSequenceAttr() {
        String xml = synapseWrap("<clone>"
                + "<target sequence=\"mySeq\"/>"
                + "</clone>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "CloneIterateTargetEmpty");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testCloneTargetWithInlineSequence() {
        String xml = synapseWrap("<clone>"
                + "<target><sequence><log/></sequence></target>"
                + "</clone>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "CloneIterateTargetEmpty");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testIterateTargetEmpty() {
        String xml = synapseWrap("<iterate expression=\"//items/item\">"
                + "<target/>"
                + "</iterate>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "CloneIterateTargetEmpty");
        assertEquals(1, diags.size());
    }

    @Test
    public void testTargetOutsideCloneNotValidated() {
        // <target> inside <proxy> should not be validated
        String xml = "<proxy xmlns=\"" + SYNAPSE_NS + "\" name=\"test\">"
                + "<target><inSequence><respond/></inSequence></target>"
                + "</proxy>";
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "CloneIterateTargetEmpty");
        assertTrue(diags.isEmpty());
    }

    // ===== P1-17: Script mediator must have key or content =====

    @Test
    public void testScriptMissingContent() {
        String xml = synapseWrap("<script language=\"js\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "ScriptMissingContent");
        assertEquals(1, diags.size());
        assertEquals(DiagnosticSeverity.Error, diags.get(0).getSeverity());
    }

    @Test
    public void testScriptWithKey() {
        String xml = synapseWrap("<script language=\"js\" key=\"conf:/scripts/transform.js\"/>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "ScriptMissingContent");
        assertTrue(diags.isEmpty());
    }

    @Test
    public void testScriptWithInlineContent() {
        String xml = synapseWrap("<script language=\"js\">mc.setPayloadJSON({});</script>");
        List<Diagnostic> diags = diagnosticsWithCode(diagnose(xml), "ScriptMissingContent");
        assertTrue(diags.isEmpty());
    }
}
